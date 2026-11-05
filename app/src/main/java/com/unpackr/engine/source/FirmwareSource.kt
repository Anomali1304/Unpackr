package com.unpackr.engine.source

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * Abstraction over a seekable, positional data source.
 * All reads are positional; whole files are NEVER loaded into RAM.
 */
interface FirmwareSource : Closeable {
    val size: Long

    /**
     * Reads up to dst.remaining() bytes starting at [position] into [dst].
     * Returns the number of bytes read, or -1 if position >= size.
     */
    fun read(position: Long, dst: ByteBuffer): Int

    /**
     * Reads exactly [dst.size] bytes from [position], or throws EOFException if not enough bytes.
     */
    fun readFully(position: Long, dst: ByteArray, offset: Int = 0, length: Int = dst.size) {
        var bytesRead = 0
        val buffer = ByteBuffer.wrap(dst, offset, length)
        while (bytesRead < length) {
            val n = read(position + bytesRead, buffer)
            if (n <= 0) {
                throw java.io.EOFException("Unexpected EOF reading $length bytes at position ${position + bytesRead} (read $bytesRead)")
            }
            bytesRead += n
        }
    }

    fun readIntLe(position: Long): Int {
        val buf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        readFully(position, buf.array())
        return buf.getInt(0)
    }

    fun readIntBe(position: Long): Int {
        val buf = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN)
        readFully(position, buf.array())
        return buf.getInt(0)
    }

    fun readShortLe(position: Long): Short {
        val buf = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN)
        readFully(position, buf.array())
        return buf.getShort(0)
    }

    fun readLongLe(position: Long): Long {
        val buf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        readFully(position, buf.array())
        return buf.getLong(0)
    }

    fun readLongBe(position: Long): Long {
        val buf = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
        readFully(position, buf.array())
        return buf.getLong(0)
    }

    fun readBytes(position: Long, count: Int): ByteArray {
        val actualCount = count.coerceAtMost((size - position).coerceAtLeast(0).toInt())
        val bytes = ByteArray(actualCount)
        if (actualCount > 0) {
            readFully(position, bytes, 0, actualCount)
        }
        return bytes
    }

    fun slice(offset: Long, length: Long): FirmwareSource {
        require(offset >= 0 && length >= 0 && offset + length <= size) {
            "Invalid slice bounds: offset=$offset, length=$length, size=$size"
        }
        return SlicedFirmwareSource(this, offset, length)
    }
}

class FileChannelSource(
    private val channel: FileChannel,
    private val closeChannelOnClose: Boolean = true
) : FirmwareSource {

    constructor(file: File) : this(RandomAccessFile(file, "r").channel, closeChannelOnClose = true)

    override val size: Long
        get() = channel.size()

    override fun read(position: Long, dst: ByteBuffer): Int {
        if (position >= size) return -1
        return channel.read(dst, position)
    }

    override fun close() {
        if (closeChannelOnClose && channel.isOpen) {
            channel.close()
        }
    }
}

class ByteArraySource(private val data: ByteArray) : FirmwareSource {
    override val size: Long = data.size.toLong()

    override fun read(position: Long, dst: ByteBuffer): Int {
        if (position >= size) return -1
        val available = (size - position).toInt().coerceAtMost(dst.remaining())
        dst.put(data, position.toInt(), available)
        return available
    }

    override fun close() {}
}

class SlicedFirmwareSource(
    private val parent: FirmwareSource,
    private val offset: Long,
    override val size: Long
) : FirmwareSource {

    override fun read(position: Long, dst: ByteBuffer): Int {
        if (position >= size) return -1
        val maxToRead = (size - position).toInt().coerceAtMost(dst.remaining())
        if (maxToRead <= 0) return -1

        val origLimit = dst.limit()
        dst.limit(dst.position() + maxToRead)
        val readCount = parent.read(offset + position, dst)
        dst.limit(origLimit)
        return readCount
    }

    override fun close() {
        // Parent ownership retained by creator
    }
}
