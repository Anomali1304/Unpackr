package com.unpackr.engine.detector

import com.unpackr.engine.handler.AbPayloadHandler
import com.unpackr.engine.handler.FastbootZipHandler
import com.unpackr.engine.handler.FirmwareHandler
import com.unpackr.engine.handler.NewDatHandler
import com.unpackr.engine.handler.SamsungTarHandler
import com.unpackr.engine.handler.SparseImageHandler
import com.unpackr.engine.handler.SuperImageHandler
import com.unpackr.engine.handler.UnsupportedEncryptedHandler
import com.unpackr.engine.model.FormatMatch
import com.unpackr.engine.model.UnknownFormatException
import com.unpackr.engine.source.FirmwareSource

class FormatDetector(
    private val handlers: List<FirmwareHandler> = defaultHandlers()
) {
    companion object {
        fun defaultHandlers(): List<FirmwareHandler> = listOf(
            UnsupportedEncryptedHandler("", "", "", ""),
            AbPayloadHandler(),
            SparseImageHandler(),
            SuperImageHandler(),
            SamsungTarHandler(),
            NewDatHandler(),
            FastbootZipHandler()
        )
    }

    /**
     * Inspects magic bytes and container structures of [src] and returns the best matching format.
     */
    fun detect(src: FirmwareSource): FormatMatch {
        if (src.size == 0L) {
            throw UnknownFormatException("Archive is empty (0 bytes).")
        }

        // 1. Check encrypted formats first to prevent false matches
        val encryptedMatch = UnsupportedEncryptedHandler.probeEncrypted(src)
        if (encryptedMatch != null) {
            return encryptedMatch
        }

        val matches = mutableListOf<FormatMatch>()

        for (handler in handlers) {
            try {
                val match = handler.probe(src)
                if (match != null) {
                    matches.add(match)
                    if (match.confidence >= 0.95f) {
                        return match
                    }
                }
            } catch (_: Exception) {
                // Continue probing other handlers
            }
        }

        if (matches.isNotEmpty()) {
            return matches.maxByOrNull { it.confidence }!!
        }

        throw UnknownFormatException(
            message = "Unrecognized container format. Not a recognized Android firmware image.",
            technicalDetails = "Archive size: ${src.size} bytes\nLeading magic bytes: ${src.readBytes(0, 16.coerceAtMost(src.size.toInt())).joinToString(" ") { "%02X".format(it) }}"
        )
    }
}
