package com.unpackr.engine.source

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit

/**
 * Robust FirmwareSource implementation for remote HTTP/HTTPS links.
 * Optimized for high-latency CDNs (like Xiaomi/MIUI) by:
 * 1. Using a browser-like User-Agent.
 * 2. Caching the first and last 128KB of the file (critical for ZIP/TAR metadata).
 * 3. Minimizing small Range requests.
 */
class HttpFirmwareSource(
    val url: String,
    private val client: OkHttpClient = defaultClient()
) : FirmwareSource {

    companion object {
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36"
        private const val CACHE_SIZE = 128 * 1024 // 128KB cache for head/tail

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    override val size: Long
    val supportsRange: Boolean
    val fileName: String

    private var headCache: ByteArray? = null
    private var tailCache: ByteArray? = null
    private var tailOffset: Long = -1L

    private var rollingCache: ByteArray? = null
    private var rollingOffset: Long = -1L
    private val PREFETCH_SIZE = 1024 * 1024 // 1 MB prefetch buffer for smooth streaming

    init {
        // Probe file size and fetch the first chunk (HEAD cache) in one go
        val headProbeReq = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Range", "bytes=0-${CACHE_SIZE - 1}")
            .get()
            .build()

        client.newCall(headProbeReq).execute().use { response ->
            if (!response.isSuccessful && response.code != 206) {
                throw IOException("Remote server returned HTTP ${response.code} (URL might be expired or restricted)")
            }

            val contentRange = response.header("Content-Range")
            val contentLength = response.header("Content-Length")
            val contentDisposition = response.header("Content-Disposition")

            val fullSize = if (contentRange != null && contentRange.contains("/")) {
                contentRange.substringAfterLast('/').toLongOrNull() ?: -1L
            } else {
                contentLength?.toLongOrNull() ?: -1L
            }

            if (fullSize <= 0) {
                throw IOException("Unable to determine remote file size (Content-Length missing)")
            }

            size = fullSize
            supportsRange = response.code == 206
            fileName = if (contentDisposition?.contains("filename=") == true) {
                contentDisposition.substringAfter("filename=").trim('"', ' ', ';')
            } else {
                url.substringBefore('?').substringAfterLast('/').ifBlank { "remote_firmware.bin" }
            }

            if (supportsRange) {
                headCache = response.body?.bytes()
            }
        }

        // Prefetch tail cache ONLY for ZIP parsing (where EOCD is at the end)
        val isZipHead = headCache?.let { b ->
            b.size >= 4 && b[0] == 0x50.toByte() && b[1] == 0x4B.toByte()
        } ?: false

        if (supportsRange && size > CACHE_SIZE && isZipHead) {
            tailOffset = (size - CACHE_SIZE).coerceAtLeast(0L)
            val tailReq = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Range", "bytes=$tailOffset-${size - 1}")
                .get()
                .build()
            
            try {
                client.newCall(tailReq).execute().use { resp ->
                    if (resp.isSuccessful || resp.code == 206) {
                        tailCache = resp.body?.bytes()
                    }
                }
            } catch (_: Exception) {}
        }
    }

    override fun read(position: Long, dst: ByteBuffer): Int {
        if (position >= size) return -1
        val lengthToRead = (size - position).coerceAtMost(dst.remaining().toLong()).toInt()
        if (lengthToRead <= 0) return 0

        // 1. Check head cache
        if (headCache != null && position < headCache!!.size) {
            val offsetInCache = position.toInt()
            val availableInCache = (headCache!!.size - offsetInCache).coerceAtMost(lengthToRead)
            if (availableInCache > 0) {
                dst.put(headCache!!, offsetInCache, availableInCache)
                return availableInCache
            }
        }

        // 2. Check tail cache
        if (tailCache != null && position >= tailOffset && position < tailOffset + tailCache!!.size) {
            val offsetInCache = (position - tailOffset).toInt()
            val availableInCache = (tailCache!!.size - offsetInCache).coerceAtMost(lengthToRead)
            if (availableInCache > 0) {
                dst.put(tailCache!!, offsetInCache, availableInCache)
                return availableInCache
            }
        }

        // 3. Check rolling cache
        if (rollingCache != null && position >= rollingOffset && position < rollingOffset + rollingCache!!.size) {
            val offsetInCache = (position - rollingOffset).toInt()
            val availableInCache = (rollingCache!!.size - offsetInCache).coerceAtMost(lengthToRead)
            if (availableInCache > 0) {
                dst.put(rollingCache!!, offsetInCache, availableInCache)
                return availableInCache
            }
        }

        // 4. Network request with smart prefetch
        val fetchSize = maxOf(lengthToRead, PREFETCH_SIZE).toLong().coerceAtMost(size - position).toInt()
        val endPosition = position + fetchSize - 1

        val req = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Range", "bytes=$position-$endPosition")
            .build()

        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful && resp.code != 206) {
                throw IOException("Range request failed: HTTP ${resp.code}")
            }
            val body = resp.body ?: throw IOException("Empty response body")
            val bytes = body.bytes()
            
            rollingCache = bytes
            rollingOffset = position

            val toDeliver = lengthToRead.coerceAtMost(bytes.size)
            dst.put(bytes, 0, toDeliver)
            return toDeliver
        }
    }

    override fun close() {}
}
