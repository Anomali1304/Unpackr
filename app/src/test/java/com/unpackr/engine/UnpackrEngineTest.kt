package com.unpackr.engine

import com.unpackr.engine.detector.FormatDetector
import com.unpackr.engine.handler.AbPayloadHandler
import com.unpackr.engine.handler.NewDatHandler
import com.unpackr.engine.handler.SparseImageHandler
import com.unpackr.engine.model.ExtractionOptions
import com.unpackr.engine.payload.DeltaManifestParser
import com.unpackr.engine.payload.InstallOpType
import com.unpackr.engine.source.ByteArraySource
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.File
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.ByteOrder

class UnpackrEngineTest {

    @Test
    fun testFirmwareSourceOperations() {
        val bytes = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08)
        val src = ByteArraySource(bytes)

        assertEquals(8L, src.size)
        assertEquals(0x04030201, src.readIntLe(0))
        assertEquals(0x01020304, src.readIntBe(0))
        assertEquals(0x0201.toShort(), src.readShortLe(0))

        val slice = src.slice(2, 4)
        assertEquals(4L, slice.size)
        assertEquals(0x06050403, slice.readIntLe(0))
    }

    @Test
    fun testSparseImageDetectionAndExtraction() = runBlocking {
        // Construct valid synthetic Android sparse image
        val totalBlks = 2
        val blkSz = 4096
        val rawData = ByteArray(blkSz) { (it % 128).toByte() }

        val buf = ByteBuffer.allocate(28 + 12 + blkSz + 12).order(ByteOrder.LITTLE_ENDIAN)
        // File header
        buf.putInt(0xED26FF3A.toInt()) // magic
        buf.putShort(1) // major
        buf.putShort(0) // minor
        buf.putShort(28) // file_hdr_sz
        buf.putShort(12) // chunk_hdr_sz
        buf.putInt(blkSz)
        buf.putInt(totalBlks)
        buf.putInt(2) // 2 chunks
        buf.putInt(0)

        // Chunk 1: RAW
        buf.putShort(0xCAC1.toShort()) // CHUNK_TYPE_RAW
        buf.putShort(0)
        buf.putInt(1) // 1 block
        buf.putInt(12 + blkSz)
        buf.put(rawData)

        // Chunk 2: DONT_CARE
        buf.putShort(0xCAC3.toShort()) // CHUNK_TYPE_DONT_CARE
        buf.putShort(0)
        buf.putInt(1) // 1 block
        buf.putInt(12)

        val src = ByteArraySource(buf.array())
        val detector = FormatDetector()
        val match = detector.detect(src)

        assertEquals("Android Sparse Image", match.formatName)
        assertTrue(match.confidence >= 0.95f)

        val handler = match.handler as SparseImageHandler
        val info = handler.inspect(src)
        assertEquals(1, info.partitions.size)
        assertEquals((totalBlks * blkSz).toLong(), info.partitions.first().size)

        // Test extraction
        val tempDir = File(System.getProperty("java.io.tmpdir"), "unpackr_sparse_test_${System.currentTimeMillis()}").apply { mkdirs() }
        try {
            val events = handler.extract(src, listOf("boot.raw"), tempDir, ExtractionOptions(convertSparseToRaw = true)).toList()
            assertTrue(events.isNotEmpty())
            val outFile = File(tempDir, "boot.raw")
            assertTrue(outFile.exists())
            assertEquals((totalBlks * blkSz).toLong(), outFile.length())
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testDeltaArchiveManifestParser() {
        // Correct protobuf wire bytes for DeltaArchiveManifest
        val manifestBytes = byteArrayOf(
            0x18.toByte(), 0x80.toByte(), 0x20.toByte(), // field 3: block_size = 4096 (varint 4096 is 0x80, 0x20)
            0x22.toByte(), 0x0B.toByte(), // field 4: partition (length 11)
            0x0A.toByte(), 0x04.toByte(), 'b'.code.toByte(), 'o'.code.toByte(), 'o'.code.toByte(), 't'.code.toByte(), // 1: name="boot" (6 bytes)
            0x2A.toByte(), 0x03.toByte(), // 5: new_partition_info (length 3)
            0x08.toByte(), 0x80.toByte(), 0x20.toByte() // 1: size=4096 (3 bytes)
        )

        val manifest = DeltaManifestParser.parse(manifestBytes)
        assertEquals(4096L, manifest.blockSize)
        assertEquals(1, manifest.partitions.size)
        assertEquals("boot", manifest.partitions.first().partitionName)
        assertEquals(4096L, manifest.partitions.first().newPartitionInfo?.size)
    }

    @Test
    fun testTransferListParser() {
        val transferListText = """
            4
            1024
            0
            0
            erase 2,0,1024
            new 2,0,512
            zero 2,512,1024
        """.trimIndent()

        val handler = NewDatHandler()
        val reader = BufferedReader(StringReader(transferListText))
        val parsed = handler.parseTransferList(reader)

        assertEquals(4, parsed.version)
        assertEquals(1024L, parsed.totalBlocks)
        assertEquals(3, parsed.commands.size)
        assertEquals("erase", parsed.commands[0].type)
        assertEquals("new", parsed.commands[1].type)
        assertEquals("zero", parsed.commands[2].type)
    }

    @Test
    fun testVendorEncryptedDetection() {
        val detector = FormatDetector()

        // 1. Oppo OZIP
        val ozipBytes = "OPPOENCRYPT!FAKE_DATA_FOR_TEST".toByteArray(Charsets.US_ASCII)
        val ozipMatch = detector.detect(ByteArraySource(ozipBytes))
        assertTrue(ozipMatch.isEncrypted)
        assertTrue(ozipMatch.formatName.contains("OZIP", ignoreCase = true))

        // 2. Huawei UPDATE.APP
        val huaweiBytes = ByteArray(100)
        huaweiBytes[0] = 0x55.toByte()
        huaweiBytes[1] = 0xAA.toByte()
        huaweiBytes[2] = 0x5A.toByte()
        huaweiBytes[3] = 0xA5.toByte()
        val huaweiMatch = detector.detect(ByteArraySource(huaweiBytes))
        assertTrue(huaweiMatch.isEncrypted)
        assertTrue(huaweiMatch.formatName.contains("Huawei", ignoreCase = true))

        // 3. LG KDZ
        val kdzBytes = ByteArray(100)
        kdzBytes[0] = 0x28.toByte()
        kdzBytes[1] = 0x05.toByte()
        kdzBytes[2] = 0x00.toByte()
        kdzBytes[3] = 0x00.toByte()
        val kdzMatch = detector.detect(ByteArraySource(kdzBytes))
        assertTrue(kdzMatch.isEncrypted)
        assertTrue(kdzMatch.formatName.contains("KDZ", ignoreCase = true))
    }

    @Test
    fun testZip64Reader() {
        // Construct a synthetic ZIP64 with payload.bin
        val localOffset = 0L
        val uncompSize = 4_938_988_403L
        val compSize = 4_938_988_403L
        val nameBytes = "payload.bin".toByteArray(Charsets.UTF_8)

        // Local header at 0
        val localBuf = ByteBuffer.allocate(30 + nameBytes.size + 20).order(ByteOrder.LITTLE_ENDIAN)
        localBuf.putInt(0x04034B50) // sig
        localBuf.putShort(45) // ver
        localBuf.putShort(0) // flags
        localBuf.putShort(0) // method = STORED
        localBuf.putShort(0)
        localBuf.putShort(0)
        localBuf.putInt(0) // crc
        localBuf.putInt(0xFFFFFFFF.toInt()) // comp
        localBuf.putInt(0xFFFFFFFF.toInt()) // uncomp
        localBuf.putShort(nameBytes.size.toShort())
        localBuf.putShort(0) // extra len
        localBuf.put(nameBytes)

        val localHeaderBytes = localBuf.array()
        val cdOffset = localHeaderBytes.size.toLong()

        // Central Directory Entry
        // Zip64 extra tag 0x0001 (16 bytes: uncomp, comp)
        val cdBuf = ByteBuffer.allocate(46 + nameBytes.size + 20).order(ByteOrder.LITTLE_ENDIAN)
        cdBuf.putInt(0x02014B50) // sig
        cdBuf.putShort(45) // ver made
        cdBuf.putShort(45) // ver need
        cdBuf.putShort(0) // flags
        cdBuf.putShort(0) // method
        cdBuf.putShort(0)
        cdBuf.putShort(0)
        cdBuf.putInt(0) // crc
        cdBuf.putInt(0xFFFFFFFF.toInt()) // comp
        cdBuf.putInt(0xFFFFFFFF.toInt()) // uncomp
        cdBuf.putShort(nameBytes.size.toShort())
        cdBuf.putShort(20.toShort()) // extra len
        cdBuf.putShort(0) // comment len
        cdBuf.putShort(0) // disk
        cdBuf.putShort(0) // int attr
        cdBuf.putInt(0) // ext attr
        cdBuf.putInt(0) // local header offset = 0
        cdBuf.put(nameBytes)
        // Zip64 extra field: tag 1, size 16
        cdBuf.putShort(1.toShort())
        cdBuf.putShort(16.toShort())
        cdBuf.putLong(uncompSize)
        cdBuf.putLong(compSize)

        val cdBytes = cdBuf.array()
        val cdSize = cdBytes.size.toLong()
        val zip64EocdOffset = cdOffset + cdSize

        // Zip64 EOCD Record (56 bytes)
        val z64EocdBuf = ByteBuffer.allocate(56).order(ByteOrder.LITTLE_ENDIAN)
        z64EocdBuf.putInt(0x06064B50) // sig
        z64EocdBuf.putLong(44L) // size of remaining record
        z64EocdBuf.putShort(45)
        z64EocdBuf.putShort(45)
        z64EocdBuf.putInt(0)
        z64EocdBuf.putInt(0)
        z64EocdBuf.putLong(1L) // entries this disk
        z64EocdBuf.putLong(1L) // total entries
        z64EocdBuf.putLong(cdSize)
        z64EocdBuf.putLong(cdOffset)
        val z64EocdBytes = z64EocdBuf.array()

        // Zip64 Locator (20 bytes)
        val z64LocBuf = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN)
        z64LocBuf.putInt(0x07064B50) // sig
        z64LocBuf.putInt(0) // disk
        z64LocBuf.putLong(zip64EocdOffset)
        z64LocBuf.putInt(1) // total disks
        val z64LocBytes = z64LocBuf.array()

        // Standard EOCD (22 bytes)
        val eocdBuf = ByteBuffer.allocate(22).order(ByteOrder.LITTLE_ENDIAN)
        eocdBuf.putInt(0x06054B50) // sig
        eocdBuf.putShort(0)
        eocdBuf.putShort(0)
        eocdBuf.putShort(0xFFFF.toShort()) // entries disk
        eocdBuf.putShort(0xFFFF.toShort()) // total entries
        eocdBuf.putInt(0xFFFFFFFF.toInt()) // cd size
        eocdBuf.putInt(0xFFFFFFFF.toInt()) // cd offset
        eocdBuf.putShort(0) // comment len
        val eocdBytes = eocdBuf.array()

        val fullArchive = localHeaderBytes + cdBytes + z64EocdBytes + z64LocBytes + eocdBytes
        val src = ByteArraySource(fullArchive)

        val entries = com.unpackr.engine.zip.ZipReader.listEntries(src)
        assertEquals(1, entries.size)
        val payload = entries.first()
        assertEquals("payload.bin", payload.name)
        assertEquals(uncompSize, payload.uncompressedSize)
        assertEquals(compSize, payload.compressedSize)
        assertEquals(0L, payload.localHeaderOffset)

        val dataOffset = com.unpackr.engine.zip.ZipReader.getDataOffset(src, payload)
        assertEquals((30 + nameBytes.size).toLong(), dataOffset)

        // Verify AbPayloadHandler probes this successfully
        val handler = AbPayloadHandler()
        val match = handler.probe(src)
        assertNotNull(match)
        assertEquals("Android OTA Zip (A/B Payload)", match!!.formatName)
    }

    @Test
    fun testFastbootTarHandlerTgz() = runBlocking {
        // Build synthetic .tgz containing images/boot.img
        val byteOut = java.io.ByteArrayOutputStream()
        java.util.zip.GZIPOutputStream(byteOut).use { gzOut ->
            org.apache.commons.compress.archivers.tar.TarArchiveOutputStream(gzOut).use { tarOut ->
                val entry = org.apache.commons.compress.archivers.tar.TarArchiveEntry("images/boot.img")
                val content = "ANDROID_BOOT_IMAGE_TEST_PAYLOAD".toByteArray(Charsets.UTF_8)
                entry.size = content.size.toLong()
                tarOut.putArchiveEntry(entry)
                tarOut.write(content)
                tarOut.closeArchiveEntry()
            }
        }

        val src = ByteArraySource(byteOut.toByteArray())
        val detector = FormatDetector()
        val match = detector.detect(src)

        assertNotNull(match)
        assertTrue(match.formatName.contains("Fastboot ROM TGZ", ignoreCase = true))

        val info = match.handler.inspect(src)
        assertEquals(1, info.partitions.size)
        assertEquals("boot.img", info.partitions.first().name)
    }

    @Test
    fun testXiaomiRemoteUrlInspection() = runBlocking {
        val url = "https://cdnorg.d.miui.com/OS3.0.303.0.WNTIDXM/moon_id_global-ota_full-OS3.0.303.0.WNTIDXM-user-16.0-5125bb3a05.zip"
        try {
            val source = com.unpackr.engine.source.HttpFirmwareSource(url)
            assertTrue("Remote file should be ~4.9GB", source.size > 4_000_000_000L)
            assertTrue("CDN should support HTTP Range", source.supportsRange)

            val detector = FormatDetector()
            val match = detector.detect(source)
            assertNotNull("Format detector must match Xiaomi OTA ZIP", match)
            assertEquals("Android OTA Zip (A/B Payload)", match.formatName)

            val info = match.handler.inspect(source)
            assertTrue("Partitions must be parsed from payload.bin", info.partitions.isNotEmpty())

            val partitionNames = info.partitions.map { it.name }
            assertTrue("Must contain boot.img", partitionNames.contains("boot.img"))
            assertTrue("Must contain vendor_boot.img", partitionNames.contains("vendor_boot.img"))
            assertTrue("Must contain vbmeta.img", partitionNames.contains("vbmeta.img"))
        } catch (e: java.io.IOException) {
            // If offline in specific environment, log and pass gracefully
            println("Skipping live network test due to network connectivity: ${e.message}")
        }
    }

    @Test
    fun testLargeFirmwareSourceBounds() {
        val largeSize = 3_899_499_384L // Exact size of user's Xiaomi ROM
        val fakeSource = object : com.unpackr.engine.source.FirmwareSource {
            override val size: Long = largeSize
            override fun read(position: Long, dst: ByteBuffer): Int {
                val toPut = minOf(dst.remaining().toLong(), size - position).toInt()
                for (i in 0 until toPut) {
                    dst.put(0x42.toByte())
                }
                return toPut
            }
            override fun close() {}
        }

        // Must not throw NegativeArraySizeException (-395467912)
        val bytes = fakeSource.readBytes(0, 12)
        assertEquals(12, bytes.size)
        assertEquals(0x42.toByte(), bytes[0])

        // Slice must also handle >2GB cleanly
        val sliced = fakeSource.slice(100, 3_899_492_001L)
        assertEquals(3_899_492_001L, sliced.size)
        val slicedBytes = sliced.readBytes(0, 4)
        assertEquals(4, slicedBytes.size)
    }

    @Test
    fun testUserXiaomiRomDirectUrl() = runBlocking {
        val url = "https://bigota.d.miui.com/OS1.0.7.0.ULUIDXM/miui_ROCKIDGlobal_OS1.0.7.0.ULUIDXM_71390f0723_14.0.zip"
        try {
            val source = com.unpackr.engine.source.HttpFirmwareSource(url)
            assertEquals(3899499384L, source.size)
            assertTrue(source.supportsRange)

            val detector = FormatDetector()
            val match = detector.detect(source)
            assertNotNull(match)
            assertEquals("Android OTA Zip (A/B Payload)", match.formatName)

            val info = match.handler.inspect(source)
            assertTrue("Partitions must be parsed from payload.bin", info.partitions.isNotEmpty())
            val names = info.partitions.map { it.name }
            assertTrue("Must contain boot.img", names.contains("boot.img"))
        } catch (e: java.io.IOException) {
            println("Network test skipped/failed: ${e.message}")
        }
    }
}

