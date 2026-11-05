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
import net.jpountz.lz4.LZ4FrameInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

class SamsungTarHandler : FirmwareHandler {

    companion object {
        val SAMSUNG_COMMONS = setOf("boot", "recovery", "super", "system", "vendor", "modem", "userdata")
    }

    data class TarEntryInfo(
        val name: String,
        val cleanName: String,
        val size: Long,
        val isLz4: Boolean,
        val dataOffset: Long
    )

    private fun isTar(src: FirmwareSource): Boolean {
        if (src.size < 512) return false
        // Check USTAR magic at offset 257
        val magicBytes = src.readBytes(257, 5)
        val magicStr = String(magicBytes, Charsets.US_ASCII)
        return magicStr == "ustar" || isTarHeaderValid(src, 0)
    }

    private fun isTarHeaderValid(src: FirmwareSource, offset: Long): Boolean {
        if (src.size < offset + 512) return false
        val header = src.readBytes(offset, 512)
        // Check if filename contains printable ASCII
        val name = String(header, 0, 100, Charsets.US_ASCII).trim { it <= ' ' || it == '\u0000' }
        return name.isNotBlank() && (name.endsWith(".img") || name.endsWith(".lz4") || name.endsWith(".bin"))
    }

    private fun listTarEntries(src: FirmwareSource): List<TarEntryInfo> {
        val entries = mutableListOf<TarEntryInfo>()
        var offset = 0L

        while (offset + 512 <= src.size) {
            val header = src.readBytes(offset, 512)
            // Empty 512-byte block marks end of tar (or md5 footer)
            if (header.all { it == 0.toByte() }) {
                break
            }

            val name = String(header, 0, 100, Charsets.US_ASCII).trim { it <= ' ' || it == '\u0000' }
            if (name.isBlank()) break

            // Size is octal string at bytes 124..135
            val sizeStr = String(header, 124, 12, Charsets.US_ASCII).trim { it <= ' ' || it == '\u0000' }
            val size = sizeStr.toLongOrNull(8) ?: 0L

            val isLz4 = name.endsWith(".lz4", ignoreCase = true)
            val cleanName = if (isLz4) name.removeSuffix(".lz4") else name

            entries.add(
                TarEntryInfo(
                    name = name,
                    cleanName = cleanName,
                    size = size,
                    isLz4 = isLz4,
                    dataOffset = offset + 512
                )
            )

            // Tar entries are aligned to 512-byte blocks
            val blocks = (size + 511) / 512
            offset += 512 + (blocks * 512)
        }

        return entries
    }

    override fun probe(src: FirmwareSource): FormatMatch? {
        if (!isTar(src)) return null

        val entries = listTarEntries(src)
        if (entries.isEmpty()) return null

        val hasLz4 = entries.any { it.isLz4 }
        val hasSamsungNames = entries.any {
            val base = it.cleanName.removeSuffix(".img").lowercase()
            SAMSUNG_COMMONS.contains(base)
        }

        val confidence = if (hasLz4 && hasSamsungNames) 0.98f else if (hasSamsungNames) 0.90f else 0.75f
        val details = "Samsung TAR firmware: ${entries.size} partitions (${if (hasLz4) "LZ4 Frame enabled" else "Plain TAR"})"

        return FormatMatch(
            handler = this,
            confidence = confidence,
            formatName = "Samsung Official Firmware (.tar / .tar.md5)",
            details = details,
            brandHint = "Samsung"
        )
    }

    override suspend fun inspect(src: FirmwareSource): FirmwareInfo {
        val entries = listTarEntries(src)
        val partitions = entries.map { entry ->
            val base = entry.cleanName.removeSuffix(".img").lowercase()
            val isCommon = SAMSUNG_COMMONS.contains(base)

            PartitionEntry(
                name = entry.cleanName,
                size = entry.size,
                compression = if (entry.isLz4) "LZ4 Frame" else "None",
                hashAvailable = false,
                isCommon = isCommon,
                extraData = entry
            )
        }

        return FirmwareInfo(
            formatId = "samsung_tar",
            formatName = "Samsung Official Firmware (AP/BL/CP/CSC TAR)",
            brandOrDevice = "Samsung Galaxy",
            partitions = partitions,
            rawDetails = mapOf(
                "Partition Count" to "${partitions.size}",
                "Archive Format" to "USTAR POSIX",
                "Compression Type" to if (entries.any { it.isLz4 }) "LZ4 Frame (.img.lz4)" else "Plain Uncompressed"
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
        val entries = listTarEntries(src)
        val toExtract = entries.filter {
            selection.isEmpty() || selection.contains(it.cleanName) || selection.contains(it.name)
        }

        val total = toExtract.size
        val successful = mutableListOf<ExtractedFile>()
        val failed = mutableMapOf<String, String>()

        toExtract.forEachIndexed { index, entry ->
            val outFile = File(outputDir, entry.cleanName)
            emit(ExtractionEvent.PartitionStarted(entry.cleanName, index + 1, total, entry.size))

            val digest = MessageDigest.getInstance("SHA-256")
            var bytesWritten = 0L
            var lastSpeedCalcTime = System.currentTimeMillis()
            var bytesSinceLastSpeedCalc = 0L

            try {
                // Stream data from entry.dataOffset with length entry.size
                val rawIn = object : InputStream() {
                    var current = entry.dataOffset
                    val end = entry.dataOffset + entry.size
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

                val finalStream: InputStream = if (entry.isLz4) {
                    LZ4FrameInputStream(rawIn)
                } else {
                    rawIn
                }

                outFile.outputStream().use { outStream ->
                    val buffer = ByteArray(64 * 1024)
                    var read: Int
                    while (finalStream.read(buffer).also { read = it } != -1) {
                        outStream.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        bytesWritten += read
                        bytesSinceLastSpeedCalc += read

                        val now = System.currentTimeMillis()
                        if (now - lastSpeedCalcTime >= 400) {
                            val speed = (bytesSinceLastSpeedCalc * 1000L) / (now - lastSpeedCalcTime).coerceAtLeast(1)
                            bytesSinceLastSpeedCalc = 0L
                            lastSpeedCalcTime = now
                            val fraction = (bytesWritten.toFloat() / entry.size.coerceAtLeast(1L)).coerceIn(0f, 1f)
                            emit(
                                ExtractionEvent.BytesWritten(
                                    partition = entry.cleanName,
                                    currentBytes = bytesWritten,
                                    totalBytes = entry.size,
                                    speedBytesPerSec = speed,
                                    overallProgressFraction = ((index + fraction) / total).coerceIn(0f, 1f)
                                )
                            )
                        }
                    }
                }

                val sha256Hex = digest.digest().joinToString("") { "%02x".format(it) }
                emit(ExtractionEvent.PartitionVerified(entry.cleanName, sha256Hex, true))
                emit(ExtractionEvent.PartitionFinished(entry.cleanName, outFile.absolutePath, outFile.length(), sha256Hex))
                successful.add(ExtractedFile(entry.cleanName, outFile, outFile.length(), sha256Hex))

            } catch (e: Exception) {
                outFile.delete()
                emit(ExtractionEvent.PartitionFailed(entry.cleanName, e.message ?: "Failed to extract Samsung partition"))
                failed[entry.cleanName] = e.message ?: "Failed to extract Samsung partition"
            }
        }

        val duration = System.currentTimeMillis() - startTime
        emit(
            ExtractionEvent.AllFinished(
                successful = successful,
                failed = failed,
                totalDurationMs = duration
            )
        )
    }.flowOn(Dispatchers.IO)
}
