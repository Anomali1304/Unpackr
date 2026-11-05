package com.unpackr.engine.source

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit

/**
 * FirmwareSource implementation backed by HTTP/HTTPS with byte-range requests (RFC 7233).
 * Enables remote inspection of multi-gigabyte ZIP/OTA/TAR archives by reading headers
 * and central directories on-demand without downloading the entire file into local storage.
 */
class HttpFirmwareSource(
    val url: String,
    private val client: OkHttpClient = defaultClient()
) : FirmwareSource {

    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    override val size: Long
    val supportsRange: Boolean
    val fileName: String

    init {
        val headReq = Request.Builder().url(url).head().build()
        val response = client.newCall(headReq).execute()

        val contentLengthHeader = response.header("Content-Length")
        val acceptRangesHeader = response.header("Accept-Ranges")
        val contentDisposition = response.header("Content-Disposition")

        val guessedName = if (contentDisposition != null && contentDisposition.contains("filename=")) {
            contentDisposition.substringAfter("filename=").trim('"', ' ', ';')
        } else {
            url.substringBefore('?').substringAfterLast('/').ifBlank { "remote_firmware.bin" }
        }
        fileName = guessedName

        size = contentLengthHeader?.toLongOrNull() ?: -1L
        supportsRange = (acceptRangesHeader?.equals("bytes", ignoreCase = true) == true) || response.isSuccessful

        response.close()

        if (size <= 0) {
            throw IOException("Unable to determine remote file size from $url (HTTP ${response.code})")
        }
    }

    override fun read(position: Long, dst: ByteBuffer): Int {
        if (position >= size) return -1
        val lengthToRead = (size - position).coerceAtMost(dst.remaining().toLong()).toInt()
        if (lengthToRead <= 0) return 0

        val endPosition = position + lengthToRead - 1
        val req = Request.Builder()
            .url(url)
            .header("Range", "bytes=$position-$endPosition")
            .build()

        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful && resp.code != 206) {
                throw IOException("Remote Range request failed: HTTP ${resp.code} for range $position-$endPosition")
            }
            val body = resp.body ?: throw IOException("Empty response body from $url")
            val stream = body.byteStream()
            val tempBuffer = ByteArray(8192)
            var totalRead = 0

            while (totalRead < lengthToRead) {
                val needed = (lengthToRead - totalRead).coerceAtMost(tempBuffer.size)
                val n = stream.read(tempBuffer, 0, needed)
                if (n == -1) break
                dst.put(tempBuffer, 0, n)
                totalRead += n
            }
            return totalRead
        }
    }

    override fun close() {
        // OkHttpClient shared dispatcher connection pool handles lifecycle
    }
}
