package com.unpackr.engine.handler

import com.unpackr.engine.model.EncryptedFormatException
import com.unpackr.engine.model.ExtractedFile
import com.unpackr.engine.model.ExtractionEvent
import com.unpackr.engine.model.ExtractionOptions
import com.unpackr.engine.model.FirmwareInfo
import com.unpackr.engine.model.FormatMatch
import com.unpackr.engine.model.PartitionEntry
import com.unpackr.engine.source.FirmwareSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.File

/**
 * Detects proprietary vendor encrypted formats (.ozip, .ofp, .ops, UPDATE.APP, .kdz).
 * Provides actionable guidance rather than crashing or pretending to decrypt.
 */
class UnsupportedEncryptedHandler(
    val formatName: String,
    val vendor: String,
    val reasonMessage: String,
    val solutionMessage: String
) : FirmwareHandler {

    companion object {
        fun probeEncrypted(src: FirmwareSource): FormatMatch? {
            if (src.size < 16) return null

            // 1. Oppo / Realme OZIP
            val magic12 = src.readBytes(0, 12)
            val magicStr = String(magic12, Charsets.US_ASCII)
            if (magicStr.startsWith("OPPOENCRYPT!") || magicStr.startsWith("OPPO")) {
                val handler = UnsupportedEncryptedHandler(
                    formatName = "Oppo / Realme OZIP (.ozip)",
                    vendor = "Oppo / Realme",
                    reasonMessage = "This archive is protected with proprietary Oppo/Realme AES-CBC encryption.",
                    solutionMessage = "Decrypt the .ozip file into a standard OTA .zip using the vendor\'s official utility or ozip decryptor prior to loading into Unpackr."
                )
                return FormatMatch(
                    handler = handler,
                    confidence = 1.0f,
                    formatName = handler.formatName,
                    details = handler.reasonMessage,
                    brandHint = "Oppo / Realme",
                    isEncrypted = true
                )
            }

            // 2. Huawei UPDATE.APP
            if (src.size >= 98) {
                val b0 = src.readBytes(0, 4)
                if (b0[0] == 0x55.toByte() && b0[1] == 0xAA.toByte() && b0[2] == 0x5A.toByte() && b0[3] == 0xA5.toByte()) {
                    val handler = UnsupportedEncryptedHandler(
                        formatName = "Huawei Firmware Package (UPDATE.APP)",
                        vendor = "Huawei / Honor",
                        reasonMessage = "Huawei UPDATE.APP employs proprietary HiSilicon container structures.",
                        solutionMessage = "Extract wrapped ZIP packages or use official HiSuite / eRecovery utilities before processing partition images in Unpackr."
                    )
                    return FormatMatch(
                        handler = handler,
                        confidence = 1.0f,
                        formatName = handler.formatName,
                        details = handler.reasonMessage,
                        brandHint = "Huawei",
                        isEncrypted = true
                    )
                }
            }

            // 3. LG KDZ
            val kdzMagic = src.readBytes(0, 4)
            if ((kdzMagic[0] == 0x28.toByte() && kdzMagic[1] == 0x05.toByte() && kdzMagic[2] == 0x00.toByte() && kdzMagic[3] == 0x00.toByte()) ||
                String(kdzMagic, Charsets.US_ASCII).startsWith("KDZ")
            ) {
                val handler = UnsupportedEncryptedHandler(
                    formatName = "LG Firmware Package (.kdz)",
                    vendor = "LG Electronics",
                    reasonMessage = "LG KDZ format is a proprietary compressed/encrypted KDZ/DZ container.",
                    solutionMessage = "Use the official LG Flash Tool or extract the nested KDZ/DZ partition cab."
                )
                return FormatMatch(
                    handler = handler,
                    confidence = 1.0f,
                    formatName = handler.formatName,
                    details = handler.reasonMessage,
                    brandHint = "LG",
                    isEncrypted = true
                )
            }

            return null
        }
    }

    override fun probe(src: FirmwareSource): FormatMatch? = probeEncrypted(src)

    override suspend fun inspect(src: FirmwareSource): FirmwareInfo {
        throw EncryptedFormatException(
            vendorFormat = formatName,
            message = reasonMessage,
            solution = solutionMessage,
            technicalDetails = "Vendor: $vendor\nFormat: $formatName"
        )
    }

    override fun extract(
        src: FirmwareSource,
        selection: List<String>,
        outputDir: File,
        options: ExtractionOptions
    ): Flow<ExtractionEvent> = flow {
        throw EncryptedFormatException(
            vendorFormat = formatName,
            message = reasonMessage,
            solution = solutionMessage
        )
    }
}

/**
 * Tier 3: Qualcomm EDL package parser stub (rawprogram*.xml + patch*.xml).
 */
class QualcommEdlHandler : FirmwareHandler {
    override fun probe(src: FirmwareSource): FormatMatch? = null
    override suspend fun inspect(src: FirmwareSource): FirmwareInfo {
        return FirmwareInfo("qualcomm_edl", "Qualcomm EDL Package", partitions = emptyList())
    }
    override fun extract(src: FirmwareSource, selection: List<String>, outputDir: File, options: ExtractionOptions): Flow<ExtractionEvent> = flow {}
}

/**
 * Tier 3: MediaTek Scatter file parser stub (MT67xx_Android_scatter.txt).
 */
class MediatekScatterHandler : FirmwareHandler {
    override fun probe(src: FirmwareSource): FormatMatch? = null
    override suspend fun inspect(src: FirmwareSource): FirmwareInfo {
        return FirmwareInfo("mtk_scatter", "MediaTek Scatter Package", partitions = emptyList())
    }
    override fun extract(src: FirmwareSource, selection: List<String>, outputDir: File, options: ExtractionOptions): Flow<ExtractionEvent> = flow {}
}
