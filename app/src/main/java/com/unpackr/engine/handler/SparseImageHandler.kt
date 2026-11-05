package com.unpackr.engine.handler

import com.unpackr.engine.model.CorruptHeaderException
import com.unpackr.engine.model.ExtractedFile
import com.unpackr.engine.model.ExtractionEvent
import com.unpackr.engine.model.ExtractionOptions
import com.unpackr.engine.model.FirmwareInfo
import com.unpackr.engine.model.FormatMatch
import com.unpackr.engine.model.PartitionEntry
import com.unpackr.engine.source.FirmwareSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

class SparseImageHandler : FirmwareHandler {

    companion object {
        const val SPARSE_HEADER_MAGIC = 0xED26FF3Au
        const val CHUNK_TYPE_RAW = 0xCAC1.toShort()
        const val CHUNK_TYPE_FILL = 0xCAC2.toShort()
        const val CHUNK_TYPE_DONT_CARE = 0xCAC3.toShort()
        const val CHUNK_TYPE_CRC32 = 0xCAC4.toShort()
    }

    data class SparseHeader(
        val magic: UInt,
        val majorVersion: Short,
        val minorVersion: Short,
        val fileHdrSz: Short,
        val chunkHdrSz: Short,
        val blkSz: Int,
        val totalBlks: Long,
        val totalChunks: Long,
        val imageChecksum: Long
    ) {
        val uncompressedSize: Long get() = totalBlks * blkSz
    }

    fun parseHeader(src: FirmwareSource): SparseHeader? {
        if (src.size < 28) return null
        val magic = src.readIntLe(0).toUInt()
        if (magic != SPARSE_HEADER_MAGIC) return null

        val major = src.readShortLe(4)
        val minor = src.readShortLe(6)
        val fileHdrSz = src.readShortLe(8)
        val chunkHdrSz = src.readShortLe(10)
        val blkSz = src.readIntLe(12)
        val totalBlks = src.readIntLe(16).toLong() and 0xFFFFFFFFL
        val totalChunks = src.readIntLe(20).toLong() and 0xFFFFFFFFL
        val checksum = src.readIntLe(24).toLong() and 0xFFFFFFFFL

        return SparseHeader(
            magic = magic,
            majorVersion = major,
            minorVersion = minor,
            fileHdrSz = fileHdrSz,
            chunkHdrSz = chunkHdrSz,
            blkSz = blkSz,
            totalBlks = totalBlks,
            totalChunks = totalChunks,
            imageChecksum = checksum
        )
    }

    override fun probe(src: FirmwareSource): FormatMatch? {
        val header = parseHeader(src) ?: return null
        return FormatMatch(
            handler = this,
            confidence = 1.0f,
            formatName = "Android Sparse Image",
            details = "Android Sparse v${header.majorVersion}.${header.minorVersion}, ${header.totalBlks} blocks (${header.blkSz} bytes/block), ${header.totalChunks} chunks"
        )
    }

    override suspend fun inspect(src: FirmwareSource): FirmwareInfo {
        val header = parseHeader(src)
            ?: throw CorruptHeaderException("Invalid Android Sparse image header")

        val partition = PartitionEntry(
            name = "image.raw",
            size = header.uncompressedSize,
            compression = "Sparse (Android)",
            hashAvailable = false,
            isCommon = true,
            isSparse = true
        )

        return FirmwareInfo(
            formatId = "sparse",
            formatName = "Android Sparse Image",
            partitions = listOf(partition),
            rawDetails = mapOf(
                "Uncompressed Size" to "${header.uncompressedSize / (1024 * 1024)} MB",
                "Block Size" to "${header.blkSz} bytes",
                "Total Blocks" to "${header.totalBlks}",
                "Chunk Count" to "${header.totalChunks}"
            )
        )
    }

    override fun extract(
        src: FirmwareSource,
        selection: List<String>,
        outputDir: File,
        options: ExtractionOptions
    ): Flow<ExtractionEvent> = flow {
        val header = parseHeader(src)
            ?: throw CorruptHeaderException("Header Android Sparse rusak")

        val startTime = System.currentTimeMillis()
        val targetName = selection.firstOrNull() ?: "image.raw"
        val outFileName = if (options.convertSparseToRaw) {
            targetName.removeSuffix(".sparse")
        } else {
            if (targetName.endsWith(".sparse")) targetName else "$targetName.sparse"
        }
        val outFile = File(outputDir, outFileName)
        val totalExpectedBytes = if (options.convertSparseToRaw) header.uncompressedSize else src.size

        emit(ExtractionEvent.PartitionStarted(outFileName, 1, 1, totalExpectedBytes))

        val digest = MessageDigest.getInstance("SHA-256")
        var bytesWritten = 0L
        var lastSpeedCalcTime = System.currentTimeMillis()
        var bytesSinceLastSpeedCalc = 0L
        var currentSpeed = 0L

        if (!options.convertSparseToRaw) {
            // Raw copy of sparse image
            RandomAccessFile(outFile, "rw").use { raf ->
                raf.setLength(0)
                val buffer = ByteArray(64 * 1024)
                var pos = 0L
                while (pos < src.size) {
                    val toRead = ((src.size - pos).coerceAtMost(buffer.size.toLong())).toInt()
                    src.readFully(pos, buffer, 0, toRead)
                    raf.write(buffer, 0, toRead)
                    digest.update(buffer, 0, toRead)
                    pos += toRead
                    bytesWritten += toRead
                    bytesSinceLastSpeedCalc += toRead

                    val now = System.currentTimeMillis()
                    if (now - lastSpeedCalcTime >= 500) {
                        currentSpeed = (bytesSinceLastSpeedCalc * 1000L) / (now - lastSpeedCalcTime).coerceAtLeast(1)
                        bytesSinceLastSpeedCalc = 0L
                        lastSpeedCalcTime = now
                        emit(
                            ExtractionEvent.BytesWritten(
                                partition = outFileName,
                                currentBytes = bytesWritten,
                                totalBytes = totalExpectedBytes,
                                speedBytesPerSec = currentSpeed,
                                overallProgressFraction = (bytesWritten.toFloat() / totalExpectedBytes).coerceIn(0f, 1f)
                            )
                        )
                    }
                }
            }
        } else {
            // Convert sparse to raw image streaming
            RandomAccessFile(outFile, "rw").use { raf ->
                raf.setLength(header.uncompressedSize) // Pre-allocate raw size
                var srcPos = header.fileHdrSz.toLong()
                var dstPos = 0L
                val blockBuffer = ByteArray(header.blkSz)

                for (chunkIdx in 0 until header.totalChunks) {
                    val chunkType = src.readShortLe(srcPos)
                    val chunkSzBlocks = src.readIntLe(srcPos + 4).toLong() and 0xFFFFFFFFL
                    val totalChunkSz = src.readIntLe(srcPos + 8).toLong() and 0xFFFFFFFFL
                    val chunkDataBytes = chunkSzBlocks * header.blkSz

                    srcPos += header.chunkHdrSz

                    when (chunkType) {
                        CHUNK_TYPE_RAW -> {
                            val expectedDataSize = totalChunkSz - header.chunkHdrSz
                            var chunkBytesRead = 0L
                            val tempBuf = ByteArray(64 * 1024)
                            while (chunkBytesRead < expectedDataSize) {
                                val toRead = (expectedDataSize - chunkBytesRead).coerceAtMost(tempBuf.size.toLong()).toInt()
                                src.readFully(srcPos + chunkBytesRead, tempBuf, 0, toRead)
                                raf.seek(dstPos + chunkBytesRead)
                                raf.write(tempBuf, 0, toRead)
                                digest.update(tempBuf, 0, toRead)
                                chunkBytesRead += toRead

                                bytesWritten += toRead
                                bytesSinceLastSpeedCalc += toRead

                                val now = System.currentTimeMillis()
                                if (now - lastSpeedCalcTime >= 500) {
                                    currentSpeed = (bytesSinceLastSpeedCalc * 1000L) / (now - lastSpeedCalcTime).coerceAtLeast(1)
                                    bytesSinceLastSpeedCalc = 0L
                                    lastSpeedCalcTime = now
                                    emit(
                                        ExtractionEvent.BytesWritten(
                                            partition = outFileName,
                                            currentBytes = bytesWritten,
                                            totalBytes = totalExpectedBytes,
                                            speedBytesPerSec = currentSpeed,
                                            overallProgressFraction = (bytesWritten.toFloat() / totalExpectedBytes).coerceIn(0f, 1f)
                                        )
                                    )
                                }
                            }
                            srcPos += expectedDataSize
                            dstPos += chunkDataBytes
                        }
                        CHUNK_TYPE_FILL -> {
                            val fillVal = src.readIntLe(srcPos)
                            srcPos += 4
                            val fillBuf = ByteBuffer.allocate(header.blkSz).order(ByteOrder.LITTLE_ENDIAN)
                            while (fillBuf.hasRemaining()) {
                                fillBuf.putInt(fillVal)
                            }
                            val fillArray = fillBuf.array()

                            raf.seek(dstPos)
                            for (b in 0 until chunkSzBlocks) {
                                raf.write(fillArray)
                                digest.update(fillArray)
                                bytesWritten += header.blkSz
                            }
                            dstPos += chunkDataBytes
                        }
                        CHUNK_TYPE_DONT_CARE -> {
                            // Leave as sparse / zeroed
                            dstPos += chunkDataBytes
                            bytesWritten += chunkDataBytes
                        }
                        CHUNK_TYPE_CRC32 -> {
                            srcPos += 4
                        }
                        else -> {
                            // Skip unknown chunk
                            srcPos += (totalChunkSz - header.chunkHdrSz)
                        }
                    }
                }
            }
        }

        val sha256Hex = digest.digest().joinToString("") { "%02x".format(it) }
        emit(ExtractionEvent.PartitionVerified(outFileName, sha256Hex, true))
        emit(ExtractionEvent.PartitionFinished(outFileName, outFile.absolutePath, outFile.length(), sha256Hex))

        val duration = System.currentTimeMillis() - startTime
        emit(
            ExtractionEvent.AllFinished(
                successful = listOf(ExtractedFile(outFileName, outFile, outFile.length(), sha256Hex)),
                failed = emptyMap(),
                totalDurationMs = duration
            )
        )
    }.flowOn(Dispatchers.IO)
}
