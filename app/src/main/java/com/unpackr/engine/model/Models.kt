package com.unpackr.engine.model

import com.unpackr.engine.handler.FirmwareHandler
import java.io.File

/**
 * Result of format probing.
 */
data class FormatMatch(
    val handler: FirmwareHandler,
    val confidence: Float, // 0.0 to 1.0
    val formatName: String,
    val details: String,
    val brandHint: String? = null,
    val isEncrypted: Boolean = false,
    val isIncremental: Boolean = false
)

/**
 * Detailed metadata inspected from a firmware file.
 */
data class FirmwareInfo(
    val formatId: String,
    val formatName: String,
    val brandOrDevice: String? = null,
    val buildVersion: String? = null,
    val securityPatch: String? = null,
    val partitions: List<PartitionEntry>,
    val rawDetails: Map<String, String> = emptyMap(),
    val totalLogicalBytes: Long = partitions.sumOf { it.size }
)

/**
 * Metadata for a single extractable partition image.
 */
data class PartitionEntry(
    val name: String,
    val size: Long,
    val compression: String = "None",
    val hashAvailable: Boolean = false,
    val expectedSha256: String? = null,
    val isCommon: Boolean = false,
    val offset: Long = 0L,
    val isSparse: Boolean = false,
    val extraData: Any? = null
)

/**
 * User options for firmware extraction.
 */
data class ExtractionOptions(
    val verifyHashes: Boolean = true,
    val strictMode: Boolean = false,
    val sanityCheck: Boolean = true,
    val convertSparseToRaw: Boolean = true,
    val parallelism: Int = 4
)

/**
 * Represents a successfully extracted file on disk.
 */
data class ExtractedFile(
    val partitionName: String,
    val file: File,
    val sizeBytes: Long,
    val sha256: String
)

/**
 * Flow events emitted during extraction.
 */
sealed class ExtractionEvent {
    data class PartitionStarted(
        val partition: String,
        val index: Int,
        val total: Int,
        val totalBytes: Long
    ) : ExtractionEvent()

    data class BytesWritten(
        val partition: String,
        val currentBytes: Long,
        val totalBytes: Long,
        val speedBytesPerSec: Long,
        val overallProgressFraction: Float
    ) : ExtractionEvent()

    data class PartitionVerified(
        val partition: String,
        val sha256: String,
        val matches: Boolean
    ) : ExtractionEvent()

    data class PartitionFailed(
        val partition: String,
        val error: String
    ) : ExtractionEvent()

    data class PartitionFinished(
        val partition: String,
        val outputPath: String,
        val sizeBytes: Long,
        val sha256: String
    ) : ExtractionEvent()

    data class AllFinished(
        val successful: List<ExtractedFile>,
        val failed: Map<String, String>,
        val totalDurationMs: Long
    ) : ExtractionEvent()
}

/**
 * Base exception for Unpackr errors with friendly message and technical details.
 */
open class UnpackrException(
    override val message: String,
    val technicalDetails: String? = null,
    cause: Throwable? = null
) : Exception(message, cause)

class UnknownFormatException(
    message: String = "Unrecognized container format. Not a supported Android firmware archive.",
    technicalDetails: String? = null
) : UnpackrException(message, technicalDetails)

class EncryptedFormatException(
    val vendorFormat: String,
    message: String,
    val solution: String,
    technicalDetails: String? = null
) : UnpackrException(message, technicalDetails)

class UnsupportedPackageException(
    val feature: String,
    message: String,
    val suggestion: String,
    technicalDetails: String? = null
) : UnpackrException(message, technicalDetails)

class CorruptHeaderException(
    message: String,
    technicalDetails: String? = null
) : UnpackrException(message, technicalDetails)

class HashMismatchException(
    val partition: String,
    val expected: String,
    val actual: String
) : UnpackrException(
    "SHA-256 verification digest mismatch on partition $partition: expected $expected, computed $actual.",
    "Partition: $partition\nExpected SHA-256: $expected\nActual SHA-256: $actual"
)

class OutOfSpaceException(
    message: String = "Insufficient storage space on device to extract the selected partition images."
) : UnpackrException(message)
