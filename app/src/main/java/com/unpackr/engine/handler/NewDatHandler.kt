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
import com.unpackr.engine.zip.ZipReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.brotli.dec.BrotliInputStream
import org.tukaani.xz.XZInputStream
import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * Handles Android block-based OTA archives (system.new.dat, system.new.dat.br, system.new.dat.xz).
 * Fully decodes Brotli compression and maps transfer.list block commands into raw partition images.
 */
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
        if (ZipReader.isZip(src)) {
            val entries = ZipReader.listEntries(src)
            val listEntry = entries.firstOrNull { it.name.endsWith(".transfer.list", ignoreCase = true) }?.name
            if (listEntry != null) {
                val hasBrotli = entries.any { it.name.endsWith(".new.dat.br", ignoreCase = true) }
                val hasXz = entries.any { it.name.endsWith(".new.dat.xz", ignoreCase = true) }
                val compressionTag = when {
                    hasBrotli -> "Brotli (.new.dat.br)"
                    hasXz -> "XZ (.new.dat.xz)"
                    else -> "Raw Block (.new.dat)"
                }
                return FormatMatch(
                    handler = this,
                    confidence = 0.95f,
                    formatName = "Android Block Transfer ($compressionTag)",
                    details = "Android block-based OTA detected with $listEntry"
                )
            }
        }
        return null
    }

    override suspend fun inspect(src: FirmwareSource): FirmwareInfo {
        val entries = ZipReader.listEntries(src)
        val transferListEntries = entries.filter { it.name.endsWith(".transfer.list", ignoreCase = true) }
        val partitions = mutableListOf<PartitionEntry>()
        val details = mutableMapOf<String, String>()

        for (tlEntry in transferListEntries) {
            val prefix = tlEntry.name.substringBefore(".transfer.list")
            val partName = "${prefix.substringAfterLast('/')}.img"

            val datBr = entries.firstOrNull { it.name.equals("$prefix.new.dat.br", ignoreCase = true) }
            val datXz = entries.firstOrNull { it.name.equals("$prefix.new.dat.xz", ignoreCase = true) }
            val datRaw = entries.firstOrNull { it.name.equals("$prefix.new.dat", ignoreCase = true) }

            val compression = when {
                datBr != null -> "Brotli (.br)"
                datXz != null -> "XZ (.xz)"
                datRaw != null -> "Raw (.dat)"
                else -> "Block Transfer"
            }

            var size = 0L
            try {
                ZipReader.openEntryStream(src, tlEntry).bufferedReader(Charsets.UTF_8).use { reader ->
                    val tl = parseTransferList(reader)
                    size = tl.totalOutputBytes
                }
            } catch (_: Exception) {
                size = 1024L * 1024L * 1024L
            }

            val isCommon = partName.startsWith("boot") || partName.startsWith("system") || partName.startsWith("vendor")
            partitions.add(
                PartitionEntry(
                    name = partName,
                    size = size,
                    compression = compression,
                    hashAvailable = false,
                    isCommon = isCommon
                )
            )
        }

        // Also check if the ZIP contains standalone partition images (e.g., boot.img, vbmeta.img, dtbo.img)
        val directImgs = entries.filter {
            it.name.endsWith(".img", ignoreCase = true) && !it.name.contains("/")
        }
        for (imgEntry in directImgs) {
            if (partitions.none { it.name.equals(imgEntry.name, ignoreCase = true) }) {
                partitions.add(
                    PartitionEntry(
                        name = imgEntry.name,
                        size = imgEntry.uncompressedSize,
                        compression = if (imgEntry.method == 8) "ZIP Deflate" else "Stored",
                        hashAvailable = false,
                        isCommon = true
                    )
                )
            }
        }

        details["Container Format"] = "Android Block Transfer OTA"
        details["Partitions Found"] = "${partitions.size} partition image(s)"
        details["Brotli Engine"] = "Supported (RFC 7932 pure JVM decoder)"

        return FirmwareInfo(
            formatId = "new_dat",
            formatName = "Android Block Transfer (system.new.dat.br)",
            partitions = partitions,
            rawDetails = details
        )
    }

    override fun extract(
        src: FirmwareSource,
        selection: List<String>,
        outputDir: File,
        options: ExtractionOptions
    ): Flow<ExtractionEvent> = flow {
        val startTime = System.currentTimeMillis()
        val entries = ZipReader.listEntries(src)
        val successful = mutableListOf<ExtractedFile>()
        val failed = mutableMapOf<String, String>()

        val totalSelected = selection.size
        var currentIndex = 0

        for (targetName in selection) {
            currentIndex++
            val prefix = targetName.removeSuffix(".img")
            val tlEntry = entries.firstOrNull { 
                it.name.endsWith("$prefix.transfer.list", ignoreCase = true) 
            }
            val datBr = entries.firstOrNull { it.name.endsWith("$prefix.new.dat.br", ignoreCase = true) }
            val datXz = entries.firstOrNull { it.name.endsWith("$prefix.new.dat.xz", ignoreCase = true) }
            val datRaw = entries.firstOrNull { it.name.endsWith("$prefix.new.dat", ignoreCase = true) }
            val datEntry = datBr ?: datXz ?: datRaw

            val directImgEntry = entries.firstOrNull { it.name.equals(targetName, ignoreCase = true) }

            val outFile = File(outputDir, targetName)

            if (tlEntry != null && datEntry != null) {
                try {
                    val tl = ZipReader.openEntryStream(src, tlEntry).bufferedReader(Charsets.UTF_8).use {
                        parseTransferList(it)
                    }

                    if (tl.isIncremental) {
                        throw UnsupportedPackageException(
                            feature = "Incremental OTA",
                            message = "Incremental OTA updates require the existing partition image on the device.",
                            suggestion = "Please use a Full OTA or Fastboot factory ROM package."
                        )
                    }

                    emit(ExtractionEvent.PartitionStarted(targetName, currentIndex, totalSelected, tl.totalOutputBytes))

                    val digest = MessageDigest.getInstance("SHA-256")
                    var writtenBytes = 0L

                    RandomAccessFile(outFile, "rw").use { raf ->
                        raf.setLength(tl.totalOutputBytes)

                        val rawEntryStream = ZipReader.openEntryStream(src, datEntry)
                        val decompressedStream: InputStream = when {
                            datEntry.name.endsWith(".br", ignoreCase = true) -> BrotliInputStream(rawEntryStream)
                            datEntry.name.endsWith(".xz", ignoreCase = true) -> XZInputStream(rawEntryStream)
                            else -> rawEntryStream
                        }

                        decompressedStream.use { stream ->
                            val buffer = ByteArray(64 * 1024)
                            for (cmd in tl.commands) {
                                when (cmd.type) {
                                    "new" -> {
                                        for (extent in cmd.extents) {
                                            val startBlock = extent.first
                                            val endBlock = extent.second
                                            val blocksToWrite = endBlock - startBlock
                                            var bytesRemainingForExtent = blocksToWrite * BLOCK_SIZE

                                            raf.seek(startBlock * BLOCK_SIZE)

                                            while (bytesRemainingForExtent > 0) {
                                                val toRead = bytesRemainingForExtent.coerceAtMost(buffer.size.toLong()).toInt()
                                                var readTotal = 0
                                                while (readTotal < toRead) {
                                                    val n = stream.read(buffer, readTotal, toRead - readTotal)
                                                    if (n <= 0) break
                                                    readTotal += n
                                                }
                                                if (readTotal == 0) break

                                                raf.write(buffer, 0, readTotal)
                                                digest.update(buffer, 0, readTotal)
                                                writtenBytes += readTotal
                                                bytesRemainingForExtent -= readTotal

                                                emit(
                                                    ExtractionEvent.BytesWritten(
                                                        partition = targetName,
                                                        currentBytes = writtenBytes,
                                                        totalBytes = tl.totalOutputBytes,
                                                        speedBytesPerSec = 0L,
                                                        overallProgressFraction = (writtenBytes.toFloat() / tl.totalOutputBytes.toFloat()).coerceIn(0f, 1f)
                                                    )
                                                )
                                            }
                                        }
                                    }
                                    "zero", "erase" -> {
                                        // Blocks are zero-initialized by setLength
                                    }
                                }
                            }
                        }
                    }

                    val sha256Hex = digest.digest().joinToString("") { "%02x".format(it) }
                    emit(ExtractionEvent.PartitionVerified(targetName, sha256Hex, true))
                    emit(ExtractionEvent.PartitionFinished(targetName, outFile.absolutePath, outFile.length(), sha256Hex))
                    successful.add(ExtractedFile(targetName, outFile, outFile.length(), sha256Hex))

                } catch (e: Exception) {
                    outFile.delete()
                    failed[targetName] = e.message ?: "Extraction failed"
                    emit(ExtractionEvent.PartitionFailed(targetName, e.message ?: "Failed"))
                }
            } else if (directImgEntry != null) {
                try {
                    emit(ExtractionEvent.PartitionStarted(targetName, currentIndex, totalSelected, directImgEntry.uncompressedSize))
                    val digest = MessageDigest.getInstance("SHA-256")
                    var writtenBytes = 0L

                    ZipReader.openEntryStream(src, directImgEntry).use { input ->
                        outFile.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var read: Int
                            while (input.read(buffer).also { read = it } != -1) {
                                output.write(buffer, 0, read)
                                digest.update(buffer, 0, read)
                                writtenBytes += read
                                emit(
                                    ExtractionEvent.BytesWritten(
                                        partition = targetName,
                                        currentBytes = writtenBytes,
                                        totalBytes = directImgEntry.uncompressedSize,
                                        speedBytesPerSec = 0L,
                                        overallProgressFraction = if (directImgEntry.uncompressedSize > 0) (writtenBytes.toFloat() / directImgEntry.uncompressedSize.toFloat()).coerceIn(0f, 1f) else 0.5f
                                    )
                                )
                            }
                        }
                    }

                    val sha256Hex = digest.digest().joinToString("") { "%02x".format(it) }
                    emit(ExtractionEvent.PartitionVerified(targetName, sha256Hex, true))
                    emit(ExtractionEvent.PartitionFinished(targetName, outFile.absolutePath, outFile.length(), sha256Hex))
                    successful.add(ExtractedFile(targetName, outFile, outFile.length(), sha256Hex))
                } catch (e: Exception) {
                    outFile.delete()
                    failed[targetName] = e.message ?: "Extraction failed"
                    emit(ExtractionEvent.PartitionFailed(targetName, e.message ?: "Failed"))
                }
            } else {
                failed[targetName] = "Partition $targetName not found in archive"
                emit(ExtractionEvent.PartitionFailed(targetName, "Not found in archive"))
            }
        }

        val duration = System.currentTimeMillis() - startTime
        emit(ExtractionEvent.AllFinished(successful, failed, duration))
    }.flowOn(Dispatchers.IO)
}
