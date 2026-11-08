package com.unpackr.engine.zip

import com.unpackr.engine.source.ByteArraySource
import com.unpackr.engine.source.FirmwareSource
import java.io.IOException

/**
 * Zip entry metadata supporting standard ZIP and Zip64 archives (>4GB).
 */
data class ZipEntryInfo(
    val name: String,
    val method: Int, // 0 = STORED, 8 = DEFLATED
    val compressedSize: Long,
    val uncompressedSize: Long,
    val localHeaderOffset: Long
)

/**
 * Utility for reading ZIP and ZIP64 archives positionally from any [FirmwareSource]
 * without loading the whole archive into memory or downloading entire remote archives.
 */
object ZipReader {

    private const val SIG_EOCD = 0x06054B50
    private const val SIG_ZIP64_LOCATOR = 0x07064B50
    private const val SIG_ZIP64_EOCD = 0x06064B50
    private const val SIG_CENTRAL_HEADER = 0x02014B50
    private const val SIG_LOCAL_HEADER = 0x04034B50

    fun isZip(src: FirmwareSource): Boolean {
        if (src.size < 4) return false
        return src.readIntLe(0) == SIG_LOCAL_HEADER
    }

    /**
     * Finds and parses the Central Directory, returning all entries.
     * Fully compliant with standard ZIP (32-bit) and Zip64 (64-bit).
     */
    fun listEntries(src: FirmwareSource): List<ZipEntryInfo> {
        val maxSearch = 65536L + 300L
        val searchStart = (src.size - maxSearch).coerceAtLeast(0L)
        val searchLen = (src.size - searchStart).toInt()
        val tailBuffer = src.readBytes(searchStart, searchLen)

        // 1. Locate standard EOCD signature (0x06054B50)
        var eocdRelativeOffset = -1
        for (i in searchLen - 22 downTo 0) {
            if (tailBuffer[i] == 0x50.toByte() &&
                tailBuffer[i + 1] == 0x4B.toByte() &&
                tailBuffer[i + 2] == 0x05.toByte() &&
                tailBuffer[i + 3] == 0x06.toByte()
            ) {
                eocdRelativeOffset = i
                break
            }
        }
        if (eocdRelativeOffset == -1) return emptyList()

        val eocdAbsoluteOffset = searchStart + eocdRelativeOffset
        val eocdSource = ByteArraySource(tailBuffer)

        var cdEntries = (eocdSource.readShortLe(eocdRelativeOffset + 10L).toInt() and 0xFFFF).toLong()
        var cdSize = eocdSource.readIntLe(eocdRelativeOffset + 12L).toLong() and 0xFFFFFFFFL
        var cdOffset = eocdSource.readIntLe(eocdRelativeOffset + 16L).toLong() and 0xFFFFFFFFL

        // 2. Check for Zip64 End of Central Directory Locator (20 bytes before EOCD or searched backwards)
        val zip64LocatorSearchStart = (eocdRelativeOffset - 32).coerceAtLeast(0)
        for (i in eocdRelativeOffset - 20 downTo zip64LocatorSearchStart) {
            if (tailBuffer[i] == 0x50.toByte() &&
                tailBuffer[i + 1] == 0x4B.toByte() &&
                tailBuffer[i + 2] == 0x06.toByte() &&
                tailBuffer[i + 3] == 0x07.toByte()
            ) {
                // Zip64 locator found!
                val zip64EocdOffset = eocdSource.readLongLe(i + 8L)
                
                // Read Zip64 EOCD Record (56 bytes minimum)
                val zip64Eocd = if (zip64EocdOffset >= searchStart && (zip64EocdOffset - searchStart + 56) <= tailBuffer.size) {
                    val relativeOffset = (zip64EocdOffset - searchStart)
                    eocdSource.readBytes(relativeOffset, 56)
                } else if (zip64EocdOffset + 56 <= src.size) {
                    src.readBytes(zip64EocdOffset, 56)
                } else {
                    ByteArray(0)
                }

                if (zip64Eocd.size >= 56) {
                    val zip64Source = ByteArraySource(zip64Eocd)
                    if (zip64Source.readIntLe(0) == SIG_ZIP64_EOCD) {
                        cdEntries = zip64Source.readLongLe(32)
                        cdSize = zip64Source.readLongLe(40)
                        cdOffset = zip64Source.readLongLe(48)
                    }
                }
                break
            }
        }

        if (cdSize <= 0 || cdEntries <= 0) return emptyList()

        // 3. Read the Central Directory into memory to avoid repeated network/disk calls
        val cdBytes = src.readBytes(cdOffset, cdSize.coerceAtMost(25 * 1024 * 1024).toInt())
        val cdBuf = ByteArraySource(cdBytes)
        val entries = ArrayList<ZipEntryInfo>(cdEntries.coerceAtMost(50000).toInt())

        var pos = 0L
        for (idx in 0 until cdEntries) {
            if (pos + 46 > cdBytes.size) break
            val sig = cdBuf.readIntLe(pos)
            if (sig != SIG_CENTRAL_HEADER) break

            val method = cdBuf.readShortLe(pos + 10).toInt() and 0xFFFF
            var compSize = cdBuf.readIntLe(pos + 20).toLong() and 0xFFFFFFFFL
            var uncompSize = cdBuf.readIntLe(pos + 24).toLong() and 0xFFFFFFFFL
            val nameLen = cdBuf.readShortLe(pos + 28).toInt() and 0xFFFF
            val extraLen = cdBuf.readShortLe(pos + 30).toInt() and 0xFFFF
            val commentLen = cdBuf.readShortLe(pos + 32).toInt() and 0xFFFF
            var localOffset = cdBuf.readIntLe(pos + 42).toLong() and 0xFFFFFFFFL

            val name = String(cdBuf.readBytes(pos + 46, nameLen), Charsets.UTF_8)
            val extraOffset = pos + 46 + nameLen

            // Parse Zip64 Extra Field (Tag 0x0001) if sizes or offset are 0xFFFFFFFF
            if (extraLen >= 4 && (uncompSize == 0xFFFFFFFFL || compSize == 0xFFFFFFFFL || localOffset == 0xFFFFFFFFL)) {
                var extraPos = extraOffset
                val extraEnd = extraOffset + extraLen
                while (extraPos + 4 <= extraEnd) {
                    val tag = cdBuf.readShortLe(extraPos).toInt() and 0xFFFF
                    val blockSize = cdBuf.readShortLe(extraPos + 2).toInt() and 0xFFFF
                    extraPos += 4
                    if (tag == 0x0001) {
                        var fieldPos = extraPos
                        if (uncompSize == 0xFFFFFFFFL && fieldPos + 8 <= extraEnd) {
                            uncompSize = cdBuf.readLongLe(fieldPos)
                            fieldPos += 8
                        }
                        if (compSize == 0xFFFFFFFFL && fieldPos + 8 <= extraEnd) {
                            compSize = cdBuf.readLongLe(fieldPos)
                            fieldPos += 8
                        }
                        if (localOffset == 0xFFFFFFFFL && fieldPos + 8 <= extraEnd) {
                            localOffset = cdBuf.readLongLe(fieldPos)
                            fieldPos += 8
                        }
                        break
                    }
                    extraPos += blockSize
                }
            }

            entries.add(
                ZipEntryInfo(
                    name = name,
                    method = method,
                    compressedSize = compSize,
                    uncompressedSize = uncompSize,
                    localHeaderOffset = localOffset
                )
            )

            pos += 46 + nameLen + extraLen + commentLen
        }

        return entries
    }

    /**
     * Resolves the exact start offset of an entry's uncompressed/raw payload data
     * by skipping its local file header (signature, name, and local extra fields).
     */
    fun getDataOffset(src: FirmwareSource, entry: ZipEntryInfo): Long {
        val localOffset = entry.localHeaderOffset
        val localHeader = src.readBytes(localOffset, 30)
        val sig = ((localHeader[0].toInt() and 0xFF)) or
                ((localHeader[1].toInt() and 0xFF) shl 8) or
                ((localHeader[2].toInt() and 0xFF) shl 16) or
                ((localHeader[3].toInt() and 0xFF) shl 24)
        if (sig != SIG_LOCAL_HEADER) {
            throw IOException("Corrupt ZIP local header for entry ${entry.name} at offset $localOffset")
        }

        val nameLen = ((localHeader[26].toInt() and 0xFF)) or ((localHeader[27].toInt() and 0xFF) shl 8)
        val extraLen = ((localHeader[28].toInt() and 0xFF)) or ((localHeader[29].toInt() and 0xFF) shl 8)

        return localOffset + 30 + nameLen + extraLen
    }
}
