package com.unpackr.engine.handler

import com.unpackr.engine.model.ExtractionEvent
import com.unpackr.engine.model.ExtractionOptions
import com.unpackr.engine.model.FirmwareInfo
import com.unpackr.engine.model.FormatMatch
import com.unpackr.engine.source.FirmwareSource
import kotlinx.coroutines.flow.Flow
import java.io.File

/**
 * Standard interface for all firmware format unpacker handlers.
 */
interface FirmwareHandler {
    /**
     * Probes the given source. Returns a FormatMatch if recognized, null otherwise.
     */
    fun probe(src: FirmwareSource): FormatMatch?

    /**
     * Inspects the source to retrieve metadata and partition entries.
     */
    suspend fun inspect(src: FirmwareSource): FirmwareInfo

    /**
     * Extracts selected partition images into the given output directory.
     * Emits progress and status events via Flow.
     */
    fun extract(
        src: FirmwareSource,
        selection: List<String>,
        outputDir: File,
        options: ExtractionOptions
    ): Flow<ExtractionEvent>
}
