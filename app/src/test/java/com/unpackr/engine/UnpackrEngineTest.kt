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
}
