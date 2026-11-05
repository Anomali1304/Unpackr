package com.unpackr.engine.source

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
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
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36"

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    override val size: Long
    val supportsRange: Boolean
    val fileName: String

    init {
        // Probe file size and range support using a small GET request instead of HEAD.
        // Many CDNs (Xiaomi/MIUI included) often block HEAD or specific User-Agents.
        val probeReq = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Range", "bytes=0-0")
            .get()
            .build()

        client.newCall(probeReq).execute().use { response ->
            if (!response.isSuccessful && response.code != 206) {
                throw IOException("Failed to connect to remote server: HTTP ${response.code}")
            }

            val contentRange = response.header("Content-Range")
            val contentLength = response.header("Content-Length")
            val contentDisposition = response.header("Content-Disposition")

            // Parse size from Content-Range (e.g., bytes 0-0/1234567)
            val fullSize = if (contentRange != null && contentRange.contains("/")) {
                contentRange.substringAfterLast('/').toLongOrNull() ?: -1L
            } else {
                contentLength?.toLongOrNull() ?: -1L
            }

            val guessedName = if (contentDisposition != null && contentDisposition.contains("filename=")) {
                contentDisposition.substringAfter("filename=").trim('"', ' ', ';')
            } else {
                url.substringBefore('?').substringAfterLast('/').ifBlank { "remote_firmware.bin" }
            }
            
            fileName = guessedName
            size = fullSize
            supportsRange = response.code == 206

            if (size <= 0) {
                throw IOException("Unable to determine remote file size from $url")
            }
            
            if (!supportsRange) {
                // Technically we can still read, but it will be inefficient. 
                // However, our read() implementation depends on Range.
                throw IOException("Server at $url does not support HTTP Range requests (Selective extraction requires Range support)")
            }
        }
    }

    override fun read(position: Long, dst: ByteBuffer): Int {
        if (position >= size) return -1
        val lengthToRead = (size - position).coerceAtMost(dst.remaining().toLong()).toInt()
        if (lengthToRead <= 0) return 0

        val endPosition = position + lengthToRead - 1
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Range", "bytes=$position-$endPosition")
            .build()

        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful && resp.code != 206) {
                throw IOException("Remote Range request failed: HTTP ${resp.code} for range $position-$endPosition")
            }
            val body = resp.body ?: throw IOException("Empty response body from $url")
            val stream = body.byteStream()
            val tempBuffer = ByteArray(16384)
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
        // Shared dispatcher pool
    }
}
