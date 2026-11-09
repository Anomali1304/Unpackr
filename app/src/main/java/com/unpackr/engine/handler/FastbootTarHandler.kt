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
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * Handler for Android Fastboot ROM archives in TAR and TGZ (.tar.gz / .tgz) format.
 * Commonly used by Xiaomi/MIUI/HyperOS fastboot ROMs, OnePlus, and Google Pixel factory archives.
 */
class FastbootTarHandler : FirmwareHandler {

    companion object {
        val IMAGE_EXTS = setOf(".img", ".bin", ".mbn", ".elf")
        val COMMON_NAMES = setOf(
            "boot", "init_boot", "vendor_boot", "recovery", "vbmeta", "vbmeta_system",
            "vbmeta_vendor", "dtbo", "super", "system", "vendor", "cust", "userdata"
        )
    }

    data class TarImageEntry(
        val entryName: String,
        val partitionName: String,
        val size: Long,
        val dataOffset: Long = -1L // Only used for uncompressed seekable TAR
    )

    private fun isGzip(src: FirmwareSource): Boolean {
        if (src.size < 10) return false
        val b0 = src.readBytes(0, 1)[0].toInt() and 0xFF
        val b1 = src.readBytes(1, 1)[0].toInt() and 0xFF
        return b0 == 0x1F && b1 == 0x8B
    }

    private fun isTar(src: FirmwareSource): Boolean {
        if (src.size < 512) return false
        val magicBytes = src.readBytes(257, 5)
        val magicStr = String(magicBytes, Charsets.US_ASCII)
        if (magicStr == "ustar") return true

        val header = src.readBytes(0, 100)
        val name = String(header, Charsets.US_ASCII).trim { it <= ' ' || it == '\u0000' }
        return name.isNotBlank() && (IMAGE_EXTS.any { name.endsWith(it, ignoreCase = true) } || name.contains("flash_all"))
    }

    override fun probe(src: FirmwareSource): FormatMatch? {
        if (isGzip(src)) {
            // Check if GZIP stream wraps a TAR archive
            try {
                val inStream = createInputStream(src, 0, src.size)
                GzipCompressorInputStream(inStream).use { gzStream ->
                    val tarInput = TarArchiveInputStream(gzStream)
                    var entry = tarInput.nextTarEntry
                    var checkedCount = 0
                    var foundImages = 0
                    var isFastboot = false

                    while (entry != null && checkedCount < 3) {
                        checkedCount++
                        val cleanName = entry.name.substringAfterLast('/')
                        if (IMAGE_EXTS.any { cleanName.endsWith(it, ignoreCase = true) }) {
                            foundImages++
                        }
                        if (cleanName.startsWith("flash_all") || cleanName == "images") {
                            isFastboot = true
                        }
                        if (foundImages > 0 || isFastboot) break
                        entry = tarInput.nextTarEntry
                    }

                    if (foundImages > 0 || isFastboot) {
                        return FormatMatch(
                            handler = this,
                            confidence = 0.96f,
                            formatName = "Fastboot ROM TGZ Archive (.tgz / .tar.gz)",
                            details = "GZIP compressed Fastboot TAR archive with partition images"
                        )
                    }
                }
            } catch (_: Exception) {}
        } else if (isTar(src)) {
            // Uncompressed fastboot TAR
            val entries = listUncompressedTarEntries(src)
            val imageEntries = entries.filter { IMAGE_EXTS.any { ext -> it.partitionName.endsWith(ext, ignoreCase = true) } }
            if (imageEntries.isNotEmpty()) {
                val hasFastboot = entries.any { it.partitionName.startsWith("flash_all") }
                val hasCommons = imageEntries.any { COMMON_NAMES.contains(it.partitionName.removeSuffix(".img").lowercase()) }
                val confidence = if (hasFastboot) 0.96f else if (hasCommons) 0.88f else 0.70f

                return FormatMatch(
                    handler = this,
                    confidence = confidence,
                    formatName = "Fastboot ROM TAR Archive (.tar)",
                    details = "POSIX TAR fastboot archive with ${imageEntries.size} partition images"
                )
            }
        }

        return null
    }

    private fun listUncompressedTarEntries(src: FirmwareSource): List<TarImageEntry> {
        val list = mutableListOf<TarImageEntry>()
        var offset = 0L

        while (offset + 512 <= src.size) {
            val header = src.readBytes(offset, 512)
            if (header.all { it == 0.toByte() }) break

            val name = String(header, 0, 100, Charsets.US_ASCII).trim { it <= ' ' || it == '\u0000' }
            if (name.isBlank()) break

            val sizeStr = String(header, 124, 12, Charsets.US_ASCII).trim { it <= ' ' || it == '\u0000' }
            val size = sizeStr.toLongOrNull(8) ?: 0L
            val cleanName = name.substringAfterLast('/')

            list.add(
                TarImageEntry(
                    entryName = name,
                    partitionName = cleanName,
                    size = size,
                    dataOffset = offset + 512
                )
            )

            val blocks = (size + 511) / 512
            offset += 512 + (blocks * 512)
        }
        return list
    }

    override suspend fun inspect(src: FirmwareSource): FirmwareInfo {
        val isCompressed = isGzip(src)
        val imageEntries = mutableListOf<TarImageEntry>()

        if (isCompressed) {
            val isRemote = src is com.unpackr.engine.source.HttpFirmwareSource
            val inStream = createInputStream(src, 0, src.size)
            GzipCompressorInputStream(inStream).use { gzStream ->
                val tarInput = TarArchiveInputStream(gzStream)
                var entry = tarInput.nextTarEntry
                while (entry != null) {
                    val cleanName = entry.name.substringAfterLast('/')
                    if (IMAGE_EXTS.any { cleanName.endsWith(it, ignoreCase = true) }) {
                        imageEntries.add(
                            TarImageEntry(
                                entryName = entry.name,
                                partitionName = cleanName,
                                size = entry.size
                            )
                        )
                        // On remote streams, after recording a major system/data partition (userdata/super/cust/system or >300MB),
                        // stop further stream decompression so inspect remains capped at ~55MB network data.
                        if (isRemote) {
                            val lower = cleanName.lowercase()
                            if (lower.startsWith("super") || lower.startsWith("cust") || lower.startsWith("userdata") || lower.startsWith("system") || entry.size > 300 * 1024 * 1024L) {
                                break
                            }
                        }
                    }
                    entry = tarInput.nextTarEntry
                }
            }
        } else {
            val all = listUncompressedTarEntries(src)
            imageEntries.addAll(all.filter { IMAGE_EXTS.any { ext -> it.partitionName.endsWith(ext, ignoreCase = true) } })
        }

        val partitions = imageEntries.map { entry ->
            val simpleName = entry.partitionName.removeSuffix(".img").lowercase()
            val isCommon = COMMON_NAMES.contains(simpleName)
            PartitionEntry(
                name = entry.partitionName,
                size = entry.size,
                compression = if (isCompressed) "GZIP (TGZ)" else "Plain TAR",
                hashAvailable = false,
                isCommon = isCommon,
                extraData = entry
            )
        }

        return FirmwareInfo(
            formatId = "fastboot_tar",
            formatName = if (isCompressed) "Fastboot ROM TGZ Archive (.tgz / .tar.gz)" else "Fastboot ROM TAR Archive (.tar)",
            partitions = partitions,
            rawDetails = mapOf(
                "Image Count" to "${partitions.size}",
                "Archive Format" to if (isCompressed) "GZIP TAR (.tgz)" else "POSIX USTAR (.tar)",
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
        val isCompressed = isGzip(src)
        val successful = mutableListOf<ExtractedFile>()
        val failed = mutableMapOf<String, String>()

        if (isCompressed) {
            // Stream extraction through Gzip and Tar
            val inStream = createInputStream(src, 0, src.size)
            GzipCompressorInputStream(inStream).use { gzStream ->
                val tarInput = TarArchiveInputStream(gzStream)
                var entry: TarArchiveEntry? = tarInput.nextTarEntry
                var processedCount = 0
                val pendingSelection = selection.toMutableSet()

                while (entry != null) {
                    val cleanName = entry.name.substringAfterLast('/')
                    val shouldExtract = selection.isEmpty() || selection.contains(cleanName) || selection.contains(entry.name)

                    if (shouldExtract && IMAGE_EXTS.any { cleanName.endsWith(it, ignoreCase = true) }) {
                        processedCount++
                        pendingSelection.remove(cleanName)
                        pendingSelection.remove(entry.name)

                        val outFile = File(outputDir, cleanName)
                        val totalSize = entry.size
                        emit(ExtractionEvent.PartitionStarted(cleanName, processedCount, selection.size.coerceAtLeast(1), totalSize))

                        val digest = MessageDigest.getInstance("SHA-256")
                        var bytesWritten = 0L
                        var lastSpeedCalcTime = System.currentTimeMillis()
                        var bytesSinceLastSpeedCalc = 0L

                        try {
                            if (cleanName.contains("..") || cleanName.startsWith("/")) {
                                throw SecurityException("Potential path traversal detected in archive entry: $cleanName")
                            }

                            outFile.outputStream().use { outStream ->
                                val buffer = ByteArray(64 * 1024)
                                var read: Int
                                while (tarInput.read(buffer).also { read = it } != -1) {
                                    outStream.write(buffer, 0, read)
                                    digest.update(buffer, 0, read)
                                    bytesWritten += read
                                    bytesSinceLastSpeedCalc += read

                                    val now = System.currentTimeMillis()
                                    if (now - lastSpeedCalcTime >= 400) {
                                        val speed = (bytesSinceLastSpeedCalc * 1000L) / (now - lastSpeedCalcTime).coerceAtLeast(1)
                                        bytesSinceLastSpeedCalc = 0L
                                        lastSpeedCalcTime = now
                                        val fraction = (bytesWritten.toFloat() / totalSize.coerceAtLeast(1L)).coerceIn(0f, 1f)
                                        emit(
                                            ExtractionEvent.BytesWritten(
                                                partition = cleanName,
                                                currentBytes = bytesWritten,
                                                totalBytes = totalSize,
                                                speedBytesPerSec = speed,
                                                overallProgressFraction = ((processedCount - 1 + fraction) / selection.size.coerceAtLeast(1)).coerceIn(0f, 1f)
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
                            emit(ExtractionEvent.PartitionFailed(cleanName, e.message ?: "Extraction failed"))
                            failed[cleanName] = e.message ?: "Extraction failed"
                        }

                        // Stop early if all selected partitions have been extracted
                        if (selection.isNotEmpty() && pendingSelection.isEmpty()) {
                            break
                        }
                    }

                    entry = tarInput.nextTarEntry
                }
            }
        } else {
            // Seekable uncompressed TAR extraction
            val entries = listUncompressedTarEntries(src)
            val toExtract = entries.filter {
                selection.isEmpty() || selection.contains(it.partitionName) || selection.contains(it.entryName)
            }
            val total = toExtract.size

            toExtract.forEachIndexed { index, item ->
                val outFile = File(outputDir, item.partitionName)
                emit(ExtractionEvent.PartitionStarted(item.partitionName, index + 1, total, item.size))

                val digest = MessageDigest.getInstance("SHA-256")
                var bytesWritten = 0L
                var lastSpeedCalcTime = System.currentTimeMillis()
                var bytesSinceLastSpeedCalc = 0L

                try {
                    val rawIn = createInputStream(src, item.dataOffset, item.size)
                    outFile.outputStream().use { outStream ->
                        val buffer = ByteArray(64 * 1024)
                        var read: Int
                        while (rawIn.read(buffer).also { read = it } != -1) {
                            outStream.write(buffer, 0, read)
                            digest.update(buffer, 0, read)
                            bytesWritten += read
                            bytesSinceLastSpeedCalc += read

                            val now = System.currentTimeMillis()
                            if (now - lastSpeedCalcTime >= 400) {
                                val speed = (bytesSinceLastSpeedCalc * 1000L) / (now - lastSpeedCalcTime).coerceAtLeast(1)
                                bytesSinceLastSpeedCalc = 0L
                                lastSpeedCalcTime = now
                                val fraction = (bytesWritten.toFloat() / item.size.coerceAtLeast(1L)).coerceIn(0f, 1f)
                                emit(
                                    ExtractionEvent.BytesWritten(
                                        partition = item.partitionName,
                                        currentBytes = bytesWritten,
                                        totalBytes = item.size,
                                        speedBytesPerSec = speed,
                                        overallProgressFraction = ((index + fraction) / total).coerceIn(0f, 1f)
                                    )
                                )
                            }
                        }
                    }

                    val sha256Hex = digest.digest().joinToString("") { "%02x".format(it) }
                    emit(ExtractionEvent.PartitionVerified(item.partitionName, sha256Hex, true))
                    emit(ExtractionEvent.PartitionFinished(item.partitionName, outFile.absolutePath, outFile.length(), sha256Hex))
                    successful.add(ExtractedFile(item.partitionName, outFile, outFile.length(), sha256Hex))

                } catch (e: Exception) {
                    outFile.delete()
                    emit(ExtractionEvent.PartitionFailed(item.partitionName, e.message ?: "Extraction failed"))
                    failed[item.partitionName] = e.message ?: "Extraction failed"
                }
            }
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

    private fun createInputStream(src: FirmwareSource, start: Long, length: Long): InputStream {
        return object : InputStream() {
            private var pos = start
            private val end = start + length

            override fun read(): Int {
                if (pos >= end) return -1
                val b = src.readBytes(pos, 1)
                if (b.isEmpty()) return -1
                pos++
                return b[0].toInt() and 0xFF
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (pos >= end) return -1
                val toRead = ((end - pos).coerceAtMost(len.toLong())).toInt()
                if (toRead <= 0) return -1
                src.readFully(pos, b, off, toRead)
                pos += toRead
                return toRead
            }
        }
    }
}
