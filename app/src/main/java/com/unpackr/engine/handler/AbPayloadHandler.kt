package com.unpackr.engine.handler

import com.unpackr.engine.model.CorruptHeaderException
import com.unpackr.engine.model.ExtractedFile
import com.unpackr.engine.model.ExtractionEvent
import com.unpackr.engine.model.ExtractionOptions
import com.unpackr.engine.model.FirmwareInfo
import com.unpackr.engine.model.FormatMatch
import com.unpackr.engine.model.HashMismatchException
import com.unpackr.engine.model.OutOfSpaceException
import com.unpackr.engine.model.PartitionEntry
import com.unpackr.engine.model.UnsupportedPackageException
import com.unpackr.engine.payload.DeltaArchiveManifest
import com.unpackr.engine.payload.DeltaManifestParser
import com.unpackr.engine.payload.InstallOpType
import com.unpackr.engine.payload.InstallOperation
import com.unpackr.engine.payload.PartitionUpdate
import com.unpackr.engine.source.ByteArraySource
import com.unpackr.engine.source.FileChannelSource
import com.unpackr.engine.source.FirmwareSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.tukaani.xz.XZInputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

class AbPayloadHandler : FirmwareHandler {

    companion object {
        const val PAYLOAD_MAGIC = 0x43724155 // "CrAU"
        val COMMON_PARTITIONS = setOf(
            "boot", "init_boot", "vendor_boot", "recovery", "dtbo", "vbmeta",
            "vbmeta_system", "vbmeta_vendor", "super"
        )
    }

    data class PayloadHeader(
        val magic: Int,
        val version: Long,
        val manifestSize: Long,
        val signatureSize: Int,
        val manifestOffset: Long,
        val dataOffset: Long,
        val manifest: DeltaArchiveManifest
    )

    private fun parseHeader(src: FirmwareSource, parseOperations: Boolean = false): PayloadHeader? {
        if (src.size < 24) return null
        val magic = src.readIntBe(0)
        if (magic != PAYLOAD_MAGIC) return null

        val version = src.readLongBe(4)
        val manifestSize = src.readLongBe(12)
        val signatureSize = if (version >= 2) src.readIntBe(20) else 0

        val manifestOffset = if (version >= 2) 24L else 20L
        if (src.size < manifestOffset + manifestSize) return null

        val manifestBytes = src.readBytes(manifestOffset, manifestSize.toInt())
        val manifest = DeltaManifestParser.parse(manifestBytes, parseOperations = parseOperations)
        val dataOffset = manifestOffset + manifestSize + signatureSize

        return PayloadHeader(
            magic = magic,
            version = version,
            manifestSize = manifestSize,
            signatureSize = signatureSize,
            manifestOffset = manifestOffset,
            dataOffset = dataOffset,
            manifest = manifest
        )
    }

    override fun probe(src: FirmwareSource): FormatMatch? {
        // Direct payload.bin
        val directHeader = parseHeader(src)
        if (directHeader != null) {
            val isInc = directHeader.manifest.partitions.any { it.isIncremental }
            return FormatMatch(
                handler = this,
                confidence = 1.0f,
                formatName = "Android A/B Payload (CrAU)",
                details = "A/B Payload v${directHeader.version}, ${directHeader.manifest.partitions.size} partitions, patch: ${directHeader.manifest.securityPatchLevel ?: "N/A"}",
                isIncremental = isInc
            )
        }

        // Check if ZIP containing payload.bin (standard ZIP or ZIP64)
        if (com.unpackr.engine.zip.ZipReader.isZip(src)) {
            val entries = com.unpackr.engine.zip.ZipReader.listEntries(src)
            val payloadEntry = entries.firstOrNull { it.name == "payload.bin" }
            if (payloadEntry != null) {
                val isStored = (payloadEntry.method == 0)
                return FormatMatch(
                    handler = this,
                    confidence = 0.98f,
                    formatName = "Android OTA Zip (A/B Payload)",
                    details = "OTA ZIP archive with payload.bin (${payloadEntry.uncompressedSize / (1024 * 1024)} MB, compression: ${if (isStored) "STORED" else "DEFLATED"})"
                )
            }
        }

        return null
    }

    private fun resolvePayloadSource(src: FirmwareSource, cacheDir: File?): Pair<FirmwareSource, Boolean> {
        val direct = parseHeader(src)
        if (direct != null) {
            return Pair(src, false)
        }

        if (com.unpackr.engine.zip.ZipReader.isZip(src)) {
            val entries = com.unpackr.engine.zip.ZipReader.listEntries(src)
            val payloadEntry = entries.firstOrNull { it.name == "payload.bin" }
                ?: throw CorruptHeaderException("payload.bin entry not found inside ZIP archive")

            val dataStart = com.unpackr.engine.zip.ZipReader.getDataOffset(src, payloadEntry)
            val uncompSize = payloadEntry.uncompressedSize
            val compSize = payloadEntry.compressedSize

            if (payloadEntry.method == 0) {
                // STORED! Zero copy slice directly
                return Pair(src.slice(dataStart, uncompSize), false)
            } else {
                // DEFLATE: Stream to cache file
                val cDir = cacheDir ?: File(System.getProperty("java.io.tmpdir"), "unpackr_cache").apply { mkdirs() }
                if (cDir.usableSpace < uncompSize + 100 * 1024 * 1024) {
                    throw OutOfSpaceException("Insufficient disk space to buffer payload.bin (${uncompSize / (1024 * 1024)} MB required)")
                }
                val cacheFile = File(cDir, "payload_${System.currentTimeMillis()}.bin")
                cacheFile.deleteOnExit()

                // Stream decompress deflated data
                val inStream = object : InputStream() {
                    var current = dataStart
                    val end = dataStart + compSize
                    override fun read(): Int {
                        if (current >= end) return -1
                        val b = src.readBytes(current, 1)
                        current++
                        return b[0].toInt() and 0xFF
                    }
                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        if (current >= end) return -1
                        val toRead = ((end - current).coerceAtMost(len.toLong())).toInt()
                        src.readFully(current, b, off, toRead)
                        current += toRead
                        return toRead
                    }
                }
                val inflaterStream = java.util.zip.InflaterInputStream(inStream, java.util.zip.Inflater(true))
                cacheFile.outputStream().use { out ->
                    inflaterStream.copyTo(out)
                }
                return Pair(FileChannelSource(cacheFile), true)
            }
        }

        throw CorruptHeaderException("Source is neither a valid payload.bin nor an OTA ZIP archive")
    }

    override suspend fun inspect(src: FirmwareSource): FirmwareInfo {
        val (payloadSrc, isTemp) = resolvePayloadSource(src, null)
        try {
            val header = parseHeader(payloadSrc)
                ?: throw CorruptHeaderException("Invalid payload.bin header structure")

            val partitions = header.manifest.partitions.mapIndexed { index, part ->
                val size = part.newPartitionInfo?.size ?: 0L
                val hash = part.newPartitionInfo?.hashHex
                
                val rawName = part.partitionName.trim().removePrefix(".")
                val cleanPartName = if (rawName.isBlank() || rawName.all { it.isDigit() }) "partition_${index + 1}" else rawName
                val displayName = if (cleanPartName.endsWith(".img", ignoreCase = true)) cleanPartName else "$cleanPartName.img"
                val simpleName = displayName.removeSuffix(".img").lowercase()

                val isCommon = COMMON_PARTITIONS.contains(simpleName)
                val compressionTypes = part.operations.map { it.type.name }.distinct().joinToString(", ")

                PartitionEntry(
                    name = displayName,
                    size = size,
                    compression = if (compressionTypes.isBlank()) "None" else compressionTypes,
                    hashAvailable = (hash != null),
                    expectedSha256 = hash,
                    isCommon = isCommon,
                    extraData = part
                )
            }

            return FirmwareInfo(
                formatId = "ab_payload",
                formatName = "Android A/B Payload (CrAU)",
                brandOrDevice = null,
                buildVersion = "A/B Payload v${header.version}",
                securityPatch = header.manifest.securityPatchLevel,
                partitions = partitions,
                rawDetails = mapOf(
                    "Block Size" to "${header.manifest.blockSize} bytes",
                    "Partition Count" to "${header.manifest.partitions.size}",
                    "Security Patch" to (header.manifest.securityPatchLevel ?: "Not specified"),
                    "Manifest Size" to "${header.manifestSize} bytes"
                )
            )
        } finally {
            if (isTemp) {
                payloadSrc.close()
            }
        }
    }

    override fun extract(
        src: FirmwareSource,
        selection: List<String>,
        outputDir: File,
        options: ExtractionOptions
    ): Flow<ExtractionEvent> = flow {
        val startTime = System.currentTimeMillis()
        val (payloadSrc, isTemp) = resolvePayloadSource(src, outputDir)
        val tempFilesToClean = mutableListOf<File>()

        try {
            val header = parseHeader(payloadSrc, parseOperations = true)
                ?: throw CorruptHeaderException("Corrupt payload.bin header structure")

            val blockSize = header.manifest.blockSize
            val partitionsToExtract = header.manifest.partitions.filter {
                selection.isEmpty() || selection.contains("${it.partitionName}.img") || selection.contains(it.partitionName)
            }

            val totalPartitions = partitionsToExtract.size
            val successfulFiles = mutableListOf<ExtractedFile>()
            val failedFiles = mutableMapOf<String, String>()

            partitionsToExtract.forEachIndexed { index, part ->
                val partName = "${part.partitionName}.img"
                val outFile = File(outputDir, partName)
                val expectedSize = part.newPartitionInfo?.size ?: 0L
                val expectedHash = part.newPartitionInfo?.hashHex

                if (part.isIncremental) {
                    emit(ExtractionEvent.PartitionFailed(partName, "Partition contains incremental delta operations (SOURCE_COPY/BSDIFF)"))
                    failedFiles[partName] = "Incremental delta packages require original base images"
                    return@forEachIndexed
                }

                emit(ExtractionEvent.PartitionStarted(partName, index + 1, totalPartitions, expectedSize))

                val digest = MessageDigest.getInstance("SHA-256")
                var bytesWritten = 0L
                var lastSpeedCalcTime = System.currentTimeMillis()
                var bytesSinceLastSpeedCalc = 0L

                try {
                    RandomAccessFile(outFile, "rw").use { raf ->
                        raf.setLength(expectedSize)

                        for (op in part.operations) {
                            val opData = if (op.dataOffset != null && op.dataLength != null) {
                                val opOffset = header.dataOffset + op.dataOffset
                                payloadSrc.readBytes(opOffset, op.dataLength.toInt())
                            } else null

                            // Verify operation SHA-256 hash if present and requested
                            if (options.verifyHashes && op.dataSha256Hash != null && opData != null) {
                                val opDigest = MessageDigest.getInstance("SHA-256")
                                opDigest.update(opData)
                                if (!opDigest.digest().contentEquals(op.dataSha256Hash)) {
                                    throw HashMismatchException(partName, "OpHashMatch", "OpHashMismatch")
                                }
                            }

                            when (op.type) {
                                InstallOpType.REPLACE -> {
                                    if (opData != null) {
                                        writeExtents(raf, op.dstExtents, blockSize, opData, digest) { written ->
                                            bytesWritten += written
                                            bytesSinceLastSpeedCalc += written
                                        }
                                    }
                                }
                                InstallOpType.REPLACE_BZ -> {
                                    if (opData != null) {
                                        val decompressed = decompressBZip2(opData)
                                        writeExtents(raf, op.dstExtents, blockSize, decompressed, digest) { written ->
                                            bytesWritten += written
                                            bytesSinceLastSpeedCalc += written
                                        }
                                    }
                                }
                                InstallOpType.REPLACE_XZ -> {
                                    if (opData != null) {
                                        val decompressed = decompressXZ(opData)
                                        writeExtents(raf, op.dstExtents, blockSize, decompressed, digest) { written ->
                                            bytesWritten += written
                                            bytesSinceLastSpeedCalc += written
                                        }
                                    }
                                }
                                InstallOpType.REPLACE_ZSTD -> {
                                    if (opData != null) {
                                        val decompressed = decompressZstd(opData)
                                        writeExtents(raf, op.dstExtents, blockSize, decompressed, digest) { written ->
                                            bytesWritten += written
                                            bytesSinceLastSpeedCalc += written
                                        }
                                    }
                                }
                                InstallOpType.ZERO, InstallOpType.DISCARD -> {
                                    // Zero fill the destination extents
                                    zeroExtents(raf, op.dstExtents, blockSize, digest) { written ->
                                        bytesWritten += written
                                        bytesSinceLastSpeedCalc += written
                                    }
                                }
                                else -> {
                                    throw UnsupportedPackageException(
                                        op.type.name,
                                        "Payload operation ${op.type} is not supported in standalone Full OTA",
                                        "Use official Full OTA package"
                                    )
                                }
                            }

                            val now = System.currentTimeMillis()
                            if (now - lastSpeedCalcTime >= 400) {
                                val speed = (bytesSinceLastSpeedCalc * 1000L) / (now - lastSpeedCalcTime).coerceAtLeast(1)
                                bytesSinceLastSpeedCalc = 0L
                                lastSpeedCalcTime = now
                                val fraction = (bytesWritten.toFloat() / expectedSize.coerceAtLeast(1L)).coerceIn(0f, 1f)
                                emit(
                                    ExtractionEvent.BytesWritten(
                                        partition = partName,
                                        currentBytes = bytesWritten,
                                        totalBytes = expectedSize,
                                        speedBytesPerSec = speed,
                                        overallProgressFraction = ((index + fraction) / totalPartitions).coerceIn(0f, 1f)
                                    )
                                )
                            }
                        }
                    }

                    // Compute partition SHA-256
                    val actualSha256 = computeFileSha256(outFile)
                    var hashVerified = true

                    if (options.verifyHashes && expectedHash != null) {
                        if (!actualSha256.equals(expectedHash, ignoreCase = true)) {
                            hashVerified = false
                            if (options.strictMode) {
                                outFile.delete()
                                throw HashMismatchException(partName, expectedHash, actualSha256)
                            }
                        }
                    } else if (options.strictMode && expectedHash == null) {
                        outFile.delete()
                        throw HashMismatchException(partName, "Strict Mode SHA-256", "Missing in manifest")
                    }

                    emit(ExtractionEvent.PartitionVerified(partName, actualSha256, hashVerified))
                    emit(ExtractionEvent.PartitionFinished(partName, outFile.absolutePath, outFile.length(), actualSha256))
                    successfulFiles.add(ExtractedFile(partName, outFile, outFile.length(), actualSha256))

                } catch (e: Exception) {
                    outFile.delete() // Guaranteed cleanup of partially written file
                    emit(ExtractionEvent.PartitionFailed(partName, e.message ?: "Failed to write partition image"))
                    failedFiles[partName] = e.message ?: "Failed to write partition image"
                }
            }

            val totalDuration = System.currentTimeMillis() - startTime
            emit(
                ExtractionEvent.AllFinished(
                    successful = successfulFiles,
                    failed = failedFiles,
                    totalDurationMs = totalDuration
                )
            )
        } finally {
            if (isTemp) {
                payloadSrc.close()
            }
        }
    }.flowOn(Dispatchers.IO)

    private fun writeExtents(
        raf: RandomAccessFile,
        extents: List<com.unpackr.engine.payload.Extent>,
        blockSize: Long,
        data: ByteArray,
        digest: MessageDigest,
        onWritten: (Long) -> Unit
    ) {
        var dataPos = 0
        for (ext in extents) {
            val extLengthBytes = (ext.numBlocks * blockSize).toInt()
            val toWrite = (extLengthBytes).coerceAtMost(data.size - dataPos)
            if (toWrite <= 0) break

            val writeOffset = ext.startBlock * blockSize
            raf.seek(writeOffset)
            raf.write(data, dataPos, toWrite)
            digest.update(data, dataPos, toWrite)

            dataPos += toWrite
            onWritten(toWrite.toLong())
        }
    }

    private fun zeroExtents(
        raf: RandomAccessFile,
        extents: List<com.unpackr.engine.payload.Extent>,
        blockSize: Long,
        digest: MessageDigest,
        onWritten: (Long) -> Unit
    ) {
        val zeroBlock = ByteArray(blockSize.toInt().coerceAtMost(64 * 1024))
        for (ext in extents) {
            val writeOffset = ext.startBlock * blockSize
            raf.seek(writeOffset)
            var remaining = ext.numBlocks * blockSize
            while (remaining > 0) {
                val step = remaining.coerceAtMost(zeroBlock.size.toLong()).toInt()
                raf.write(zeroBlock, 0, step)
                digest.update(zeroBlock, 0, step)
                remaining -= step
                onWritten(step.toLong())
            }
        }
    }

    private fun decompressBZip2(compressed: ByteArray): ByteArray {
        BZip2CompressorInputStream(ByteArrayInputStream(compressed)).use { stream ->
            return stream.readBytes()
        }
    }

    private fun decompressXZ(compressed: ByteArray): ByteArray {
        XZInputStream(ByteArrayInputStream(compressed)).use { stream ->
            return stream.readBytes()
        }
    }

    private fun decompressZstd(compressed: ByteArray): ByteArray {
        com.github.luben.zstd.ZstdInputStream(ByteArrayInputStream(compressed)).use { stream ->
            return stream.readBytes()
        }
    }

    private fun computeFileSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        file.inputStream().use { input ->
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
