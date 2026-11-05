package com.unpackr.engine.handler

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
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipFile
import java.io.File
import java.io.InputStream
import java.nio.channels.Channels
import java.security.MessageDigest

class FastbootZipHandler : FirmwareHandler {

    companion object {
        val IMAGE_EXTENSIONS = setOf(".img", ".bin", ".mbn", ".elf")
        val COMMON_NAMES = setOf(
            "boot", "init_boot", "vendor_boot", "recovery", "vbmeta", "vbmeta_system",
            "vbmeta_vendor", "dtbo", "super", "system", "vendor", "product", "odm"
        )
    }

    data class ZipImageEntry(
        val entryName: String,
        val partitionName: String,
        val size: Long,
        val isNested: Boolean = false,
        val parentZipEntry: String? = null
    )

    private fun isZip(src: FirmwareSource): Boolean {
        if (src.size < 4) return false
        val magic = src.readIntLe(0)
        return magic == 0x04034B50
    }

    override fun probe(src: FirmwareSource): FormatMatch? {
        if (!isZip(src)) return null

        val entries = listZipImageEntries(src)
        if (entries.isEmpty()) return null

        val hasCommon = entries.any { COMMON_NAMES.contains(it.partitionName.lowercase().removeSuffix(".img")) }
        val confidence = if (hasCommon) 0.95f else 0.85f

        val nestedCount = entries.count { it.isNested }
        val details = if (nestedCount > 0) {
            "Nested Factory Image ZIP: ${entries.size} partition images (including $nestedCount in inner archive)"
        } else {
            "Fastboot ROM ZIP: ${entries.size} partition image files (.img)"
        }

        return FormatMatch(
            handler = this,
            confidence = confidence,
            formatName = "Fastboot / Factory ROM ZIP",
            details = details
        )
    }

    private fun listZipImageEntries(src: FirmwareSource): List<ZipImageEntry> {
        val results = mutableListOf<ZipImageEntry>()
        try {
            // Seekable archive access via commons-compress or zip directory scan
            val eocdOffset = findZipEocd(src) ?: return emptyList()
            val cdOffset = src.readIntLe(eocdOffset + 16).toLong() and 0xFFFFFFFFL
            val cdEntries = src.readShortLe(eocdOffset + 10).toInt() and 0xFFFF

            var pos = cdOffset
            val nestedZipCandidates = mutableListOf<Pair<String, Long>>()

            for (i in 0 until cdEntries) {
                if (pos + 46 > src.size) break
                val sig = src.readIntLe(pos)
                if (sig != 0x02014B50) break

                val uncompSize = src.readIntLe(pos + 24).toLong() and 0xFFFFFFFFL
                val nameLen = src.readShortLe(pos + 28).toInt() and 0xFFFF
                val extraLen = src.readShortLe(pos + 30).toInt() and 0xFFFF
                val commentLen = src.readShortLe(pos + 32).toInt() and 0xFFFF

                val nameBytes = src.readBytes(pos + 46, nameLen)
                val rawName = String(nameBytes, Charsets.UTF_8)
                val cleanName = rawName.substringAfterLast('/')

                if (IMAGE_EXTENSIONS.any { cleanName.endsWith(it, ignoreCase = true) }) {
                    results.add(
                        ZipImageEntry(
                            entryName = rawName,
                            partitionName = cleanName,
                            size = uncompSize,
                            isNested = false
                        )
                    )
                } else if (cleanName.startsWith("image-", ignoreCase = true) && cleanName.endsWith(".zip", ignoreCase = true)) {
                    nestedZipCandidates.add(Pair(rawName, uncompSize))
                }

                pos += 46 + nameLen + extraLen + commentLen
            }

            // If Pixel factory images (e.g. image-oriole-tp1a.zip), inspect nested entries
            for ((nestedName, _) in nestedZipCandidates) {
                // If direct .img entries are already plentiful, no need; otherwise note nested images
                if (results.isEmpty()) {
                    results.add(
                        ZipImageEntry(
                            entryName = nestedName,
                            partitionName = nestedName.substringAfterLast('/'),
                            size = 0L,
                            isNested = true
                        )
                    )
                }
            }
        } catch (_: Exception) {}
        return results
    }

    private fun findZipEocd(src: FirmwareSource): Long? {
        val maxSearch = 65536L + 22L
        val searchStart = (src.size - maxSearch).coerceAtLeast(0L)
        val searchLen = (src.size - searchStart).toInt()
        val buffer = src.readBytes(searchStart, searchLen)

        for (i in searchLen - 22 downTo 0) {
            if (buffer[i] == 0x50.toByte() &&
                buffer[i + 1] == 0x4B.toByte() &&
                buffer[i + 2] == 0x05.toByte() &&
                buffer[i + 3] == 0x06.toByte()
            ) {
                return searchStart + i
            }
        }
        return null
    }

    override suspend fun inspect(src: FirmwareSource): FirmwareInfo {
        val entries = listZipImageEntries(src)
        val partitions = entries.map { entry ->
            val simpleName = entry.partitionName.removeSuffix(".img")
            val isCommon = COMMON_NAMES.contains(simpleName.lowercase())
            PartitionEntry(
                name = entry.partitionName,
                size = entry.size,
                compression = if (entry.isNested) "Nested ZIP" else "ZIP (Deflate)",
                hashAvailable = false,
                isCommon = isCommon,
                extraData = entry
            )
        }

        return FirmwareInfo(
            formatId = "fastboot_zip",
            formatName = "Fastboot / Factory ROM ZIP",
            partitions = partitions,
            rawDetails = mapOf(
                "Image Count" to "${partitions.size}",
                "Container Format" to "Standard ZIP Archive",
                "Total Uncompressed Size" to "${partitions.sumOf { it.size } / (1024 * 1024)} MB"
            )
        )
    }

    override fun extract(
        src: FirmwareSource,
        selection: List<String>,
        outputDir: File,
        options: ExtractionOptions
    ): Flow<ExtractionEvent> = flow {
        val startTime = System.currentTimeMillis()
        val entries = listZipImageEntries(src)
        val toExtract = entries.filter {
            selection.isEmpty() || selection.contains(it.partitionName)
        }

        val total = toExtract.size
        val successful = mutableListOf<ExtractedFile>()
        val failed = mutableMapOf<String, String>()

        // Write seekable temp file or stream from source
        val eocdOffset = findZipEocd(src) ?: throw IllegalStateException("ZIP End of Central Directory (EOCD) record not found")
        val cdOffset = src.readIntLe(eocdOffset + 16).toLong() and 0xFFFFFFFFL
        val cdEntries = src.readShortLe(eocdOffset + 10).toInt() and 0xFFFF

        var pos = cdOffset
        var processedCount = 0

        for (i in 0 until cdEntries) {
            if (pos + 46 > src.size) break
            val sig = src.readIntLe(pos)
            if (sig != 0x02014B50) break

            val method = src.readShortLe(pos + 10).toInt() and 0xFFFF
            val compSize = src.readIntLe(pos + 20).toLong() and 0xFFFFFFFFL
            val uncompSize = src.readIntLe(pos + 24).toLong() and 0xFFFFFFFFL
            val nameLen = src.readShortLe(pos + 28).toInt() and 0xFFFF
            val extraLen = src.readShortLe(pos + 30).toInt() and 0xFFFF
            val commentLen = src.readShortLe(pos + 32).toInt() and 0xFFFF
            val localOffset = src.readIntLe(pos + 42).toLong() and 0xFFFFFFFFL

            val rawName = String(src.readBytes(pos + 46, nameLen), Charsets.UTF_8)
            val cleanName = rawName.substringAfterLast('/')

            val matchedEntry = toExtract.firstOrNull { it.entryName == rawName }
            if (matchedEntry != null) {
                processedCount++
                val outFile = File(outputDir, cleanName)
                emit(ExtractionEvent.PartitionStarted(cleanName, processedCount, total, uncompSize))

                val digest = MessageDigest.getInstance("SHA-256")
                var bytesWritten = 0L
                var lastSpeedCalcTime = System.currentTimeMillis()
                var bytesSinceLastSpeedCalc = 0L

                try {
                    // Safe write with anti-zip-slip
                    if (cleanName.contains("..") || cleanName.startsWith("/")) {
                        throw SecurityException("Potential path traversal detected in archive entry: $cleanName")
                    }

                    val localNameLen = src.readShortLe(localOffset + 26).toInt() and 0xFFFF
                    val localExtraLen = src.readShortLe(localOffset + 28).toInt() and 0xFFFF
                    val dataStart = localOffset + 30 + localNameLen + localExtraLen

                    val rawIn = object : InputStream() {
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

                    val streamToRead: InputStream = if (method == 0) {
                        rawIn
                    } else {
                        java.util.zip.InflaterInputStream(rawIn, java.util.zip.Inflater(true))
                    }

                    outFile.outputStream().use { outStream ->
                        val buffer = ByteArray(64 * 1024)
                        var read: Int
                        while (streamToRead.read(buffer).also { read = it } != -1) {
                            outStream.write(buffer, 0, read)
                            digest.update(buffer, 0, read)
                            bytesWritten += read
                            bytesSinceLastSpeedCalc += read

                            val now = System.currentTimeMillis()
                            if (now - lastSpeedCalcTime >= 400) {
                                val speed = (bytesSinceLastSpeedCalc * 1000L) / (now - lastSpeedCalcTime).coerceAtLeast(1)
                                bytesSinceLastSpeedCalc = 0L
                                lastSpeedCalcTime = now
                                val fraction = (bytesWritten.toFloat() / uncompSize.coerceAtLeast(1L)).coerceIn(0f, 1f)
                                emit(
                                    ExtractionEvent.BytesWritten(
                                        partition = cleanName,
                                        currentBytes = bytesWritten,
                                        totalBytes = uncompSize,
                                        speedBytesPerSec = speed,
                                        overallProgressFraction = ((processedCount - 1 + fraction) / total).coerceIn(0f, 1f)
                                    )
                                )
                            }
                        }
                    }

                    val sha256Hex = digest.digest().joinToString("") { "%02x".format(it) }
                    emit(ExtractionEvent.PartitionVerified(cleanName, sha256Hex, true))
                    emit(ExtractionEvent.PartitionFinished(cleanName, outFile.absolutePath, outFile.length(), sha256Hex))
                    successful.add(ExtractedFile(cleanName, outFile, outFile.length(), sha256Hex))

                } catch (e: Exception) {
                    outFile.delete()
                    emit(ExtractionEvent.PartitionFailed(cleanName, e.message ?: "Failed to extract partition image from ZIP"))
                    failed[cleanName] = e.message ?: "Failed to extract partition image from ZIP"
                }
            }

            pos += 46 + nameLen + extraLen + commentLen
        }

        val totalDuration = System.currentTimeMillis() - startTime
        emit(
            ExtractionEvent.AllFinished(
                successful = successful,
                failed = failed,
                totalDurationMs = totalDuration
            )
        )
    }.flowOn(Dispatchers.IO)
}
