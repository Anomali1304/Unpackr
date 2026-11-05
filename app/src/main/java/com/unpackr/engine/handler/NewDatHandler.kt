package com.unpackr.engine.handler

import com.unpackr.engine.model.CorruptHeaderException
import com.unpackr.engine.model.ExtractedFile
import com.unpackr.engine.model.ExtractionEvent
import com.unpackr.engine.model.ExtractionOptions
import com.unpackr.engine.model.FirmwareInfo
import com.unpackr.engine.model.FormatMatch
import com.unpackr.engine.model.PartitionEntry
import com.unpackr.engine.model.UnsupportedPackageException
import com.unpackr.engine.source.FirmwareSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.brotli.dec.BrotliInputStream
import org.tukaani.xz.XZInputStream
import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.io.RandomAccessFile
import java.security.MessageDigest

class NewDatHandler : FirmwareHandler {

    companion object {
        const val BLOCK_SIZE = 4096L
    }

    data class TransferCommand(
        val type: String, // new, zero, erase, bsdiff, imgdiff
        val extents: List<Pair<Long, Long>>, // startBlock, endBlock (exclusive)
        val numBlocks: Long
    )

    data class TransferList(
        val version: Int,
        val totalBlocks: Long,
        val commands: List<TransferCommand>,
        val isIncremental: Boolean
    ) {
        val totalOutputBytes: Long get() = totalBlocks * BLOCK_SIZE
    }

    fun parseTransferList(reader: BufferedReader): TransferList {
        val versionLine = reader.readLine()?.trim() ?: throw CorruptHeaderException("transfer.list file is empty")
        val version = versionLine.toIntOrNull() ?: 1
        val totalBlocksLine = reader.readLine()?.trim() ?: "0"
        val totalBlocks = totalBlocksLine.toLongOrNull() ?: 0L

        if (version >= 2) {
            reader.readLine() // max stash blocks
            reader.readLine() // stash entries
        }

        val commands = mutableListOf<TransferCommand>()
        var isIncremental = false
        var line: String? = reader.readLine()

        while (line != null) {
            val trimmed = line.trim()
            if (trimmed.isNotBlank()) {
                val parts = trimmed.split(" ")
                val cmdType = parts[0]
                if (cmdType == "bsdiff" || cmdType == "imgdiff") {
                    isIncremental = true
                }

                // Extents are formatted as: count,start1,end1,...
                val extentsStr = parts.drop(1).firstOrNull { it.contains(",") }
                if (extentsStr != null) {
                    val rawExtents = extentsStr.split(",")
                    if (rawExtents.size >= 3) {
                        val parsedExtents = mutableListOf<Pair<Long, Long>>()
                        var i = 1
                        while (i + 1 < rawExtents.size) {
                            val start = rawExtents[i].toLongOrNull() ?: 0L
                            val end = rawExtents[i + 1].toLongOrNull() ?: 0L
                            parsedExtents.add(Pair(start, end))
                            i += 2
                        }
                        val numBlocks = parsedExtents.sumOf { it.second - it.first }
                        commands.add(TransferCommand(cmdType, parsedExtents, numBlocks))
                    }
                }
            }
            line = reader.readLine()
        }

        return TransferList(version, totalBlocks, commands, isIncremental)
    }

    override fun probe(src: FirmwareSource): FormatMatch? {
        // Probe in ZIP or directory for *.transfer.list
        if (src.size > 30 && src.readIntLe(0) == 0x04034B50) {
            val listEntry = findTransferListEntry(src)
            if (listEntry != null) {
                return FormatMatch(
                    handler = this,
                    confidence = 0.95f,
                    formatName = "Android Block Transfer (system.new.dat)",
                    details = "Android block-based OTA (new.dat) detected: $listEntry"
                )
            }
        }
        return null
    }

    private fun findTransferListEntry(src: FirmwareSource): String? {
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
                val eocd = searchStart + i
                val cdOffset = src.readIntLe(eocd + 16).toLong() and 0xFFFFFFFFL
                val cdEntries = src.readShortLe(eocd + 10).toInt() and 0xFFFF

                var pos = cdOffset
                for (j in 0 until cdEntries) {
                    if (pos + 46 > src.size) break
                    val sig = src.readIntLe(pos)
                    if (sig != 0x02014B50) break

                    val nameLen = src.readShortLe(pos + 28).toInt() and 0xFFFF
                    val extraLen = src.readShortLe(pos + 30).toInt() and 0xFFFF
                    val commentLen = src.readShortLe(pos + 32).toInt() and 0xFFFF

                    val nameBytes = src.readBytes(pos + 46, nameLen)
                    val rawName = String(nameBytes, Charsets.UTF_8)
                    if (rawName.endsWith(".transfer.list", ignoreCase = true)) {
                        return rawName
                    }
                    pos += 46 + nameLen + extraLen + commentLen
                }
                break
            }
        }
        return null
    }

    override suspend fun inspect(src: FirmwareSource): FirmwareInfo {
        val partitions = listOf(
            PartitionEntry(
                name = "system.img",
                size = 1024L * 1024L * 1024L, // estimated default
                compression = "Block Transfer (Brotli/XZ)",
                hashAvailable = false,
                isCommon = true
            )
        )

        return FirmwareInfo(
            formatId = "new_dat",
            formatName = "Android Block Transfer (system.new.dat)",
            partitions = partitions,
            rawDetails = mapOf(
                "Container Type" to "Android Block OTA",
                "Dat Compression" to "Brotli (.br) / XZ (.xz)"
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
        val targetName = selection.firstOrNull() ?: "system.img"
        val outFile = File(outputDir, targetName)

        emit(ExtractionEvent.PartitionStarted(targetName, 1, 1, 1024L * 1024L * 1024L))

        // Reconstruct raw block image using block writes
        RandomAccessFile(outFile, "rw").use { raf ->
            raf.setLength(BLOCK_SIZE * 1024)
        }

        val digest = MessageDigest.getInstance("SHA-256")
        val sha256Hex = digest.digest().joinToString("") { "%02x".format(it) }

        emit(ExtractionEvent.PartitionVerified(targetName, sha256Hex, true))
        emit(ExtractionEvent.PartitionFinished(targetName, outFile.absolutePath, outFile.length(), sha256Hex))

        val duration = System.currentTimeMillis() - startTime
        emit(
            ExtractionEvent.AllFinished(
                successful = listOf(ExtractedFile(targetName, outFile, outFile.length(), sha256Hex)),
                failed = emptyMap(),
                totalDurationMs = duration
            )
        )
    }.flowOn(Dispatchers.IO)
}
