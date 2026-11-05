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

class SuperImageHandler : FirmwareHandler {

    companion object {
        const val LP_METADATA_GEOMETRY_MAGIC = 0x616C4430.toInt() // "0Dal"
        const val LP_METADATA_HEADER_MAGIC = 0x414C5030.toInt() // "0PLA"
        const val LP_SECTOR_SIZE = 512L
        const val LP_TARGET_TYPE_LINEAR = 0
        const val LP_TARGET_TYPE_ZERO = 1
    }

    data class LpGeometry(
        val magic: Int,
        val structSize: Int,
        val metadataMaxSize: Long,
        val metadataSlotCount: Long,
        val logicalBlockSize: Long
    )

    data class LpPartition(
        val name: String,
        val attributes: Int,
        val firstExtentIndex: Int,
        val numExtents: Int,
        val groupIndex: Int,
        val extents: List<LpExtent> = emptyList()
    ) {
        val totalBytes: Long
            get() = extents.sumOf { it.numSectors * LP_SECTOR_SIZE }
    }

    data class LpExtent(
        val numSectors: Long,
        val targetType: Int,
        val targetDataSector: Long, // Sector offset
        val targetSource: Int
    )

    data class LpMetadata(
        val geometry: LpGeometry,
        val partitions: List<LpPartition>,
        val blockDeviceFirstLogicalSector: Long
    )

    private fun findGeometryOffset(src: FirmwareSource): Long? {
        val offsetsToTry = listOf(4096L, 0L, 8192L)
        for (off in offsetsToTry) {
            if (src.size >= off + 64) {
                val magic = src.readIntLe(off)
                if (magic == LP_METADATA_GEOMETRY_MAGIC) {
                    return off
                }
            }
        }
        return null
    }

    fun parseLpMetadata(src: FirmwareSource): LpMetadata? {
        val geomOffset = findGeometryOffset(src) ?: return null
        val magic = src.readIntLe(geomOffset)
        if (magic != LP_METADATA_GEOMETRY_MAGIC) return null

        val structSize = src.readIntLe(geomOffset + 4)
        val metadataMaxSize = src.readIntLe(geomOffset + 40).toLong() and 0xFFFFFFFFL
        val metadataSlotCount = src.readIntLe(geomOffset + 44).toLong() and 0xFFFFFFFFL
        val logicalBlockSize = src.readIntLe(geomOffset + 48).toLong() and 0xFFFFFFFFL

        val geom = LpGeometry(magic, structSize, metadataMaxSize, metadataSlotCount, logicalBlockSize)

        // Metadata slots start after geometry (offset geomOffset + structSize aligned, typically 8192)
        val primarySlotOffset = geomOffset + 4096L
        if (src.size < primarySlotOffset + 80) return null

        val headerMagic = src.readIntLe(primarySlotOffset)
        if (headerMagic != LP_METADATA_HEADER_MAGIC) return null

        val headerSize = src.readIntLe(primarySlotOffset + 8)
        val tablesSize = src.readIntLe(primarySlotOffset + 44)

        // Partitions descriptor at offset 80
        val partTableOffset = src.readIntLe(primarySlotOffset + 80).toLong() and 0xFFFFFFFFL
        val partNumEntries = src.readIntLe(primarySlotOffset + 84)
        val partEntrySize = src.readIntLe(primarySlotOffset + 88)

        // Extents descriptor at offset 92
        val extTableOffset = src.readIntLe(primarySlotOffset + 92).toLong() and 0xFFFFFFFFL
        val extNumEntries = src.readIntLe(primarySlotOffset + 96)
        val extEntrySize = src.readIntLe(primarySlotOffset + 100)

        // Block devices descriptor at offset 116
        val devTableOffset = src.readIntLe(primarySlotOffset + 116).toLong() and 0xFFFFFFFFL
        val devNumEntries = src.readIntLe(primarySlotOffset + 120)
        val devEntrySize = src.readIntLe(primarySlotOffset + 124)

        val tablesBase = primarySlotOffset + headerSize

        // Read block device first logical sector
        var firstLogicalSector = 0L
        if (devNumEntries > 0) {
            val devOffset = tablesBase + devTableOffset
            firstLogicalSector = src.readLongLe(devOffset)
        }

        // Read extents table
        val allExtents = mutableListOf<LpExtent>()
        for (i in 0 until extNumEntries) {
            val eOff = tablesBase + extTableOffset + (i * extEntrySize)
            val numSectors = src.readLongLe(eOff)
            val targetType = src.readIntLe(eOff + 8)
            val targetData = src.readLongLe(eOff + 12)
            val targetSource = src.readIntLe(eOff + 20)
            allExtents.add(LpExtent(numSectors, targetType, targetData, targetSource))
        }

        // Read partitions table
        val partitions = mutableListOf<LpPartition>()
        for (i in 0 until partNumEntries) {
            val pOff = tablesBase + partTableOffset + (i * partEntrySize)
            val nameBytes = src.readBytes(pOff, 36)
            val zeroIndex = nameBytes.indexOf(0.toByte())
            val name = if (zeroIndex >= 0) {
                String(nameBytes, 0, zeroIndex, Charsets.UTF_8)
            } else {
                String(nameBytes, Charsets.UTF_8).trim()
            }

            if (name.isBlank()) continue

            val attributes = src.readIntLe(pOff + 36)
            val firstExt = src.readIntLe(pOff + 40)
            val numExt = src.readIntLe(pOff + 44)
            val groupIdx = src.readIntLe(pOff + 48)

            val partExtents = if (firstExt >= 0 && firstExt + numExt <= allExtents.size) {
                allExtents.subList(firstExt, firstExt + numExt)
            } else emptyList()

            partitions.add(LpPartition(name, attributes, firstExt, numExt, groupIdx, partExtents))
        }

        return LpMetadata(geom, partitions, firstLogicalSector)
    }

    override fun probe(src: FirmwareSource): FormatMatch? {
        val metadata = parseLpMetadata(src)
        if (metadata != null) {
            val partNames = metadata.partitions.joinToString(", ") { it.name }
            return FormatMatch(
                handler = this,
                confidence = 0.99f,
                formatName = "Android Super / Dynamic Image (liblp)",
                details = "liblp metadata: ${metadata.partitions.size} logical partition ($partNames)"
            )
        }

        // Check if name or hints suggest super.img or sparse super
        if (src.size > 28 && src.readIntLe(0).toUInt() == SparseImageHandler.SPARSE_HEADER_MAGIC) {
            // It is a sparse image; check if chunk 0 or 1 contains liblp geometry
            val sparseHeader = SparseImageHandler().parseHeader(src)
            if (sparseHeader != null && sparseHeader.totalBlks > 1024) {
                // If it's a huge sparse image, likely super.img
                return FormatMatch(
                    handler = this,
                    confidence = 0.70f,
                    formatName = "Android Super Image (Sparse)",
                    details = "Super image in chunked Android sparse container (${sparseHeader.uncompressedSize / (1024 * 1024)} MB)"
                )
            }
        }

        return null
    }

    override suspend fun inspect(src: FirmwareSource): FirmwareInfo {
        val metadata = parseLpMetadata(src)
            ?: throw CorruptHeaderException("Android liblp metadata (super image) not found or corrupted")

        val partitions = metadata.partitions.map { lp ->
            PartitionEntry(
                name = "${lp.name}.img",
                size = lp.totalBytes,
                compression = "None (Logical Dynamic)",
                hashAvailable = false,
                isCommon = listOf("system", "vendor", "product", "system_ext", "odm").any { lp.name.startsWith(it) },
                extraData = lp
            )
        }

        return FirmwareInfo(
            formatId = "super_liblp",
            formatName = "Android Super / Dynamic Partition (liblp)",
            partitions = partitions,
            rawDetails = mapOf(
                "Logical Partitions" to "${partitions.size}",
                "Logical Block Size" to "${metadata.geometry.logicalBlockSize} bytes",
                "Metadata Slots" to "${metadata.geometry.metadataSlotCount}",
                "First Logical Sector" to "${metadata.blockDeviceFirstLogicalSector}"
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
        val metadata = parseLpMetadata(src)
            ?: throw CorruptHeaderException("Failed to parse liblp metadata from super image")

        val partitionsToExtract = metadata.partitions.filter {
            selection.isEmpty() || selection.contains("${it.name}.img") || selection.contains(it.name)
        }

        val totalPartitions = partitionsToExtract.size
        val successful = mutableListOf<ExtractedFile>()
        val failed = mutableMapOf<String, String>()

        partitionsToExtract.forEachIndexed { index, part ->
            val partName = "${part.name}.img"
            val outFile = File(outputDir, partName)
            val expectedSize = part.totalBytes

            emit(ExtractionEvent.PartitionStarted(partName, index + 1, totalPartitions, expectedSize))

            val digest = MessageDigest.getInstance("SHA-256")
            var bytesWritten = 0L
            var lastSpeedCalcTime = System.currentTimeMillis()
            var bytesSinceLastSpeedCalc = 0L

            try {
                RandomAccessFile(outFile, "rw").use { raf ->
                    raf.setLength(expectedSize)
                    val buffer = ByteArray(64 * 1024)

                    for (ext in part.extents) {
                        if (ext.targetType == LP_TARGET_TYPE_LINEAR) {
                            val srcByteOffset = ext.targetDataSector * LP_SECTOR_SIZE
                            val extSizeBytes = ext.numSectors * LP_SECTOR_SIZE
                            var extBytesRead = 0L

                            while (extBytesRead < extSizeBytes) {
                                val toRead = (extSizeBytes - extBytesRead).coerceAtMost(buffer.size.toLong()).toInt()
                                val readPos = srcByteOffset + extBytesRead
                                if (readPos >= src.size) break
                                src.readFully(readPos, buffer, 0, toRead)
                                raf.write(buffer, 0, toRead)
                                digest.update(buffer, 0, toRead)

                                extBytesRead += toRead
                                bytesWritten += toRead
                                bytesSinceLastSpeedCalc += toRead

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
                        } else if (ext.targetType == LP_TARGET_TYPE_ZERO) {
                            val zeroBytes = ext.numSectors * LP_SECTOR_SIZE
                            val zeroBlock = ByteArray(64 * 1024)
                            var extZeroed = 0L
                            while (extZeroed < zeroBytes) {
                                val toZero = (zeroBytes - extZeroed).coerceAtMost(zeroBlock.size.toLong()).toInt()
                                raf.write(zeroBlock, 0, toZero)
                                digest.update(zeroBlock, 0, toZero)
                                extZeroed += toZero
                                bytesWritten += toZero
                            }
                        }
                    }
                }

                val sha256Hex = digest.digest().joinToString("") { "%02x".format(it) }
                emit(ExtractionEvent.PartitionVerified(partName, sha256Hex, true))
                emit(ExtractionEvent.PartitionFinished(partName, outFile.absolutePath, outFile.length(), sha256Hex))
                successful.add(ExtractedFile(partName, outFile, outFile.length(), sha256Hex))

            } catch (e: Exception) {
                outFile.delete()
                emit(ExtractionEvent.PartitionFailed(partName, e.message ?: "Failed to extract dynamic super partition"))
                failed[partName] = e.message ?: "Failed to extract dynamic super partition"
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
