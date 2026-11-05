package com.unpackr.engine.payload

import java.io.InputStream
import java.nio.ByteBuffer

/**
 * Pure Kotlin data structures and parser for AOSP update_metadata.proto
 * without native or heavy generated dependencies.
 */

data class DeltaArchiveManifest(
    val blockSize: Long = 4096L,
    val securityPatchLevel: String? = null,
    val partitions: List<PartitionUpdate> = emptyList(),
    val dynamicPartitionGroups: List<DynamicPartitionGroup> = emptyList()
)

data class PartitionUpdate(
    val partitionName: String,
    val newPartitionInfo: PartitionInfo? = null,
    val oldPartitionInfo: PartitionInfo? = null,
    val operations: List<InstallOperation> = emptyList()
) {
    val isIncremental: Boolean
        get() = oldPartitionInfo != null || operations.any {
            it.type in listOf(
                InstallOpType.SOURCE_COPY,
                InstallOpType.BSDIFF,
                InstallOpType.SOURCE_BSDIFF,
                InstallOpType.PUFFDIFF,
                InstallOpType.ZUCCHINI,
                InstallOpType.LZ4DIFF
            )
        }
}

data class PartitionInfo(
    val size: Long = 0L,
    val hash: ByteArray? = null
) {
    val hashHex: String?
        get() = hash?.joinToString("") { "%02x".format(it) }
}

enum class InstallOpType(val value: Int) {
    REPLACE(0),
    REPLACE_BZ(1),
    MOVE(2),
    BSDIFF(3),
    SOURCE_COPY(4),
    SOURCE_BSDIFF(5),
    REPLACE_XZ(6),
    ZERO(7),
    DISCARD(8),
    REPLACE_ZSTD(9),
    PUFFDIFF(10),
    ZUCCHINI(11),
    LZ4DIFF(12),
    UNKNOWN(-1);

    companion object {
        fun fromValue(value: Int): InstallOpType =
            entries.firstOrNull { it.value == value } ?: UNKNOWN
    }
}

data class Extent(
    val startBlock: Long = 0L,
    val numBlocks: Long = 0L
)

data class InstallOperation(
    val type: InstallOpType = InstallOpType.REPLACE,
    val dataOffset: Long? = null,
    val dataLength: Long? = null,
    val srcExtents: List<Extent> = emptyList(),
    val dstExtents: List<Extent> = emptyList(),
    val dataSha256Hash: ByteArray? = null,
    val srcLength: Long? = null,
    val dstLength: Long? = null
)

data class DynamicPartitionGroup(
    val name: String,
    val size: Long = 0L,
    val partitionNames: List<String> = emptyList()
)

/**
 * Lightweight streaming Protobuf wire format parser.
 */
class CodedInputStream(private val buffer: ByteBuffer) {

    constructor(bytes: ByteArray) : this(ByteBuffer.wrap(bytes))

    fun hasMore(): Boolean = buffer.hasRemaining()

    fun readTag(): Int {
        if (!buffer.hasRemaining()) return 0
        return readVarint32()
    }

    fun readVarint32(): Int = readVarint64().toInt()

    fun readVarint64(): Long {
        var result = 0L
        var shift = 0
        while (shift < 64) {
            if (!buffer.hasRemaining()) throw java.io.EOFException("Premature EOF reading varint")
            val b = buffer.get().toInt()
            result = result or ((b and 0x7F).toLong() shl shift)
            if ((b and 0x80) == 0) return result
            shift += 7
        }
        throw IllegalArgumentException("Malformed varint")
    }

    fun readFixed32(): Int = buffer.getInt()
    fun readFixed64(): Long = buffer.getLong()

    fun readBytes(): ByteArray {
        val length = readVarint32()
        if (length < 0 || length > buffer.remaining()) {
            throw IllegalArgumentException("Invalid length: $length, remaining: ${buffer.remaining()}")
        }
        val bytes = ByteArray(length)
        buffer.get(bytes)
        return bytes
    }

    fun readString(): String {
        val bytes = readBytes()
        return String(bytes, Charsets.UTF_8)
    }

    fun skipField(tag: Int) {
        val wireType = tag and 0x07
        when (wireType) {
            0 -> readVarint64()
            1 -> {
                buffer.position(buffer.position() + 8)
            }
            2 -> {
                val len = readVarint32()
                buffer.position(buffer.position() + len)
            }
            5 -> {
                buffer.position(buffer.position() + 4)
            }
            else -> throw IllegalArgumentException("Unsupported wire type: $wireType")
        }
    }
}

object DeltaManifestParser {

    fun parse(bytes: ByteArray): DeltaArchiveManifest {
        val stream = CodedInputStream(bytes)
        var blockSize = 4096L
        var securityPatch: String? = null
        val partitions = mutableListOf<PartitionUpdate>()
        val groups = mutableListOf<DynamicPartitionGroup>()

        while (stream.hasMore()) {
            val tag = stream.readTag()
            if (tag == 0) break
            val fieldNumber = tag ushr 3
            when (fieldNumber) {
                3 -> blockSize = stream.readVarint64()
                4 -> {
                    val subBytes = stream.readBytes()
                    partitions.add(parsePartitionUpdate(subBytes))
                }
                10 -> {
                    val subBytes = stream.readBytes()
                    groups.addAll(parseDynamicPartitionMetadata(subBytes))
                }
                13 -> securityPatch = stream.readString()
                else -> stream.skipField(tag)
            }
        }

        return DeltaArchiveManifest(
            blockSize = blockSize,
            securityPatchLevel = securityPatch,
            partitions = partitions,
            dynamicPartitionGroups = groups
        )
    }

    private fun parsePartitionUpdate(bytes: ByteArray): PartitionUpdate {
        val stream = CodedInputStream(bytes)
        var name = ""
        var newInfo: PartitionInfo? = null
        var oldInfo: PartitionInfo? = null
        val operations = mutableListOf<InstallOperation>()

        while (stream.hasMore()) {
            val tag = stream.readTag()
            if (tag == 0) break
            when (tag ushr 3) {
                1 -> name = stream.readString()
                4 -> oldInfo = parsePartitionInfo(stream.readBytes())
                5 -> newInfo = parsePartitionInfo(stream.readBytes())
                6 -> operations.add(parseInstallOperation(stream.readBytes()))
                else -> stream.skipField(tag)
            }
        }
        return PartitionUpdate(name, newInfo, oldInfo, operations)
    }

    private fun parsePartitionInfo(bytes: ByteArray): PartitionInfo {
        val stream = CodedInputStream(bytes)
        var size = 0L
        var hash: ByteArray? = null
        while (stream.hasMore()) {
            val tag = stream.readTag()
            if (tag == 0) break
            when (tag ushr 3) {
                1 -> size = stream.readVarint64()
                2 -> hash = stream.readBytes()
                else -> stream.skipField(tag)
            }
        }
        return PartitionInfo(size, hash)
    }

    private fun parseInstallOperation(bytes: ByteArray): InstallOperation {
        val stream = CodedInputStream(bytes)
        var type = InstallOpType.REPLACE
        var dataOffset: Long? = null
        var dataLength: Long? = null
        val srcExtents = mutableListOf<Extent>()
        val dstExtents = mutableListOf<Extent>()
        var dataHash: ByteArray? = null
        var srcLength: Long? = null
        var dstLength: Long? = null

        while (stream.hasMore()) {
            val tag = stream.readTag()
            if (tag == 0) break
            when (tag ushr 3) {
                1 -> type = InstallOpType.fromValue(stream.readVarint32())
                2 -> dataOffset = stream.readVarint64()
                3 -> dataLength = stream.readVarint64()
                4 -> srcExtents.add(parseExtent(stream.readBytes()))
                5 -> dstExtents.add(parseExtent(stream.readBytes()))
                6 -> dataHash = stream.readBytes()
                7 -> srcLength = stream.readVarint64()
                8 -> dstLength = stream.readVarint64()
                else -> stream.skipField(tag)
            }
        }
        return InstallOperation(type, dataOffset, dataLength, srcExtents, dstExtents, dataHash, srcLength, dstLength)
    }

    private fun parseExtent(bytes: ByteArray): Extent {
        val stream = CodedInputStream(bytes)
        var start = 0L
        var count = 0L
        while (stream.hasMore()) {
            val tag = stream.readTag()
            if (tag == 0) break
            when (tag ushr 3) {
                1 -> start = stream.readVarint64()
                2 -> count = stream.readVarint64()
                else -> stream.skipField(tag)
            }
        }
        return Extent(start, count)
    }

    private fun parseDynamicPartitionMetadata(bytes: ByteArray): List<DynamicPartitionGroup> {
        val stream = CodedInputStream(bytes)
        val groups = mutableListOf<DynamicPartitionGroup>()
        while (stream.hasMore()) {
            val tag = stream.readTag()
            if (tag == 0) break
            when (tag ushr 3) {
                1 -> groups.add(parseDynamicPartitionGroup(stream.readBytes()))
                else -> stream.skipField(tag)
            }
        }
        return groups
    }

    private fun parseDynamicPartitionGroup(bytes: ByteArray): DynamicPartitionGroup {
        val stream = CodedInputStream(bytes)
        var name = ""
        var size = 0L
        val partitions = mutableListOf<String>()
        while (stream.hasMore()) {
            val tag = stream.readTag()
            if (tag == 0) break
            when (tag ushr 3) {
                1 -> name = stream.readString()
                2 -> size = stream.readVarint64()
                3 -> partitions.add(stream.readString())
                else -> stream.skipField(tag)
            }
        }
        return DynamicPartitionGroup(name, size, partitions)
    }
}
