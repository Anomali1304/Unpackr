package com.unpackr.ui.viewmodel

import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.unpackr.engine.detector.FormatDetector
import com.unpackr.engine.handler.FirmwareHandler
import com.unpackr.engine.model.EncryptedFormatException
import com.unpackr.engine.model.ExtractedFile
import com.unpackr.engine.model.ExtractionEvent
import com.unpackr.engine.model.ExtractionOptions
import com.unpackr.engine.model.FirmwareInfo
import com.unpackr.engine.model.FormatMatch
import com.unpackr.engine.model.PartitionEntry
import com.unpackr.engine.model.UnknownFormatException
import com.unpackr.engine.model.UnpackrException
import com.unpackr.engine.source.ByteArraySource
import com.unpackr.engine.source.FileChannelSource
import com.unpackr.engine.source.FirmwareSource
import com.unpackr.service.ExtractionService
import com.unpackr.service.ExtractionStateHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

sealed class ScreenState {
    data object Home : ScreenState()
    data class Detected(
        val info: FirmwareInfo,
        val match: FormatMatch,
        val isRemoteUrl: Boolean = false,
        val remoteUrl: String? = null,
        val remoteFileName: String? = null,
        val remoteFileSize: Long = 0L
    ) : ScreenState()
    data class Extracting(val currentPartition: String, val progress: Float, val speed: Long, val etaSeconds: Long) : ScreenState()
    data class Result(val successful: List<ExtractedFile>, val failed: Map<String, String>, val durationMs: Long) : ScreenState()
    data class Unsupported(val title: String, val reason: String, val solution: String, val technical: String?) : ScreenState()
    data object Settings : ScreenState()
}

data class RecentFirmwareItem(
    val name: String,
    val uriString: String,
    val sizeBytes: Long,
    val detectedFormat: String
)

class UnpackrViewModel(application: Application) : AndroidViewModel(application) {

    private val detector = FormatDetector()
    private var currentSource: FirmwareSource? = null
    private var currentHandler: FirmwareHandler? = null
    private var currentPfd: ParcelFileDescriptor? = null

    private val _screenState = MutableStateFlow<ScreenState>(ScreenState.Home)
    val screenState = _screenState.asStateFlow()

    private val _selectedPartitions = MutableStateFlow<Set<String>>(emptySet())
    val selectedPartitions = _selectedPartitions.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery = _searchQuery.asStateFlow()

    private val _options = MutableStateFlow(ExtractionOptions())
    val options = _options.asStateFlow()

    private val _recentItems = MutableStateFlow<List<RecentFirmwareItem>>(emptyList())
    val recentItems = _recentItems.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading = _isLoading.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage = _errorMessage.asStateFlow()

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun togglePartition(name: String) {
        val current = _selectedPartitions.value.toMutableSet()
        if (current.contains(name)) {
            current.remove(name)
        } else {
            current.add(name)
        }
        _selectedPartitions.value = current
    }

    fun selectAll(partitions: List<PartitionEntry>) {
        _selectedPartitions.value = partitions.map { it.name }.toSet()
    }

    fun deselectAll() {
        _selectedPartitions.value = emptySet()
    }

    fun selectCommon(partitions: List<PartitionEntry>) {
        _selectedPartitions.value = partitions.filter { it.isCommon }.map { it.name }.toSet()
    }

    fun selectSystem(partitions: List<PartitionEntry>) {
        val systemPrefixes = listOf("system", "vendor", "product", "system_ext", "odm")
        _selectedPartitions.value = partitions
            .filter { p -> systemPrefixes.any { p.name.startsWith(it, ignoreCase = true) } }
            .map { it.name }
            .toSet()
    }

    fun updateOptions(newOptions: ExtractionOptions) {
        _options.value = newOptions
    }

    fun navigateTo(screen: ScreenState) {
        _screenState.value = screen
    }

    fun loadFirmwareUri(uri: Uri) {
        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null

            withContext(Dispatchers.IO) {
                try {
                    val context = getApplication<Application>()
                    val pfd = context.contentResolver.openFileDescriptor(uri, "r")
                        ?: throw UnpackrException("Failed to open file descriptor for the selected file")

                    currentPfd?.close()
                    currentSource?.close()

                    currentPfd = pfd
                    val channel = FileInputStream(pfd.fileDescriptor).channel
                    val source = FileChannelSource(channel, closeChannelOnClose = false)
                    currentSource = source

                    processSource(source, getFileName(context, uri) ?: "firmware.bin", uri.toString())
                } catch (e: EncryptedFormatException) {
                    _screenState.value = ScreenState.Unsupported(
                        title = e.vendorFormat,
                        reason = e.message,
                        solution = e.solution,
                        technical = e.technicalDetails
                    )
                } catch (e: Exception) {
                    _errorMessage.value = e.message ?: "Failed to inspect local firmware file"
                } finally {
                    _isLoading.value = false
                }
            }
        }
    }

    /**
     * Inspects a remote firmware archive directly via HTTP Range requests without requiring
     * downloading the full multi-gigabyte package first.
     */
    fun loadFirmwareUrl(url: String) {
        val trimmed = url.trim()
        if (trimmed.isBlank() || (!trimmed.startsWith("http://", ignoreCase = true) && !trimmed.startsWith("https://", ignoreCase = true))) {
            _errorMessage.value = "Please provide a valid HTTP or HTTPS URL to an archive."
            return
        }

        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null

            withContext(Dispatchers.IO) {
                try {
                    currentPfd?.close()
                    currentSource?.close()

                    val httpSource = com.unpackr.engine.source.HttpFirmwareSource(trimmed)
                    currentSource = httpSource

                    processSource(httpSource, httpSource.fileName, trimmed)
                } catch (e: EncryptedFormatException) {
                    _screenState.value = ScreenState.Unsupported(
                        title = e.vendorFormat,
                        reason = e.message,
                        solution = e.solution,
                        technical = e.technicalDetails
                    )
                } catch (e: Exception) {
                    _errorMessage.value = e.message ?: "Failed to connect or inspect remote firmware URL"
                } finally {
                    _isLoading.value = false
                }
            }
        }
    }

    private suspend fun processSource(source: FirmwareSource, fileName: String, uriString: String) {
        val match = detector.detect(source)
        currentHandler = match.handler

        if (match.isEncrypted) {
            _screenState.value = ScreenState.Unsupported(
                title = match.formatName,
                reason = match.details,
                solution = "Vendor-proprietary encrypted archives cannot be unpacked on-device without cryptographic keys.",
                technical = "Brand: ${match.brandHint ?: "Vendor proprietary"}"
            )
            return
        }

        val info = match.handler.inspect(source)

        // Select common partitions by default
        val commons = info.partitions.filter { it.isCommon }.map { it.name }.toSet()
        _selectedPartitions.value = if (commons.isNotEmpty()) commons else info.partitions.take(5).map { it.name }.toSet()

        // Update recents
        val updatedRecents = _recentItems.value.toMutableList()
        updatedRecents.removeAll { it.name == fileName }
        updatedRecents.add(
            0,
            RecentFirmwareItem(
                name = fileName,
                uriString = uriString,
                sizeBytes = source.size,
                detectedFormat = match.formatName
            )
        )
        _recentItems.value = updatedRecents.take(5)

        val isRemote = source is com.unpackr.engine.source.HttpFirmwareSource
        val httpSource = source as? com.unpackr.engine.source.HttpFirmwareSource

        _screenState.value = ScreenState.Detected(
            info = info,
            match = match,
            isRemoteUrl = isRemote,
            remoteUrl = httpSource?.url,
            remoteFileName = httpSource?.fileName ?: fileName,
            remoteFileSize = source.size
        )
    }

    /**
     * Downloads the original full ROM package directly from the remote link
     * into the device's public Downloads directory using Android DownloadManager.
     */
    fun downloadFullRom(context: Context) {
        val httpSource = currentSource as? com.unpackr.engine.source.HttpFirmwareSource
        if (httpSource == null) {
            android.widget.Toast.makeText(context, "Berkas ini bukan dari tautan unduhan online.", android.widget.Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as android.app.DownloadManager
            val request = android.app.DownloadManager.Request(Uri.parse(httpSource.url))
                .setTitle(httpSource.fileName)
                .setDescription("Mengunduh arsip ROM asli...")
                .setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(android.os.Environment.DIRECTORY_DOWNLOADS, httpSource.fileName)
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(true)

            downloadManager.enqueue(request)
            android.widget.Toast.makeText(
                context,
                "Mengunduh ${httpSource.fileName} ke folder Download via sistem...",
                android.widget.Toast.LENGTH_LONG
            ).show()
        } catch (e: Exception) {
            android.widget.Toast.makeText(
                context,
                "Gagal memulai unduhan: ${e.message}",
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }

    fun startExtraction() {
        val source = currentSource ?: return
        val handler = currentHandler ?: return
        val selection = _selectedPartitions.value.toList()
        if (selection.isEmpty()) return

        viewModelScope.launch {
            val context = getApplication<Application>()
            val outDir = File(context.getExternalFilesDir(null) ?: context.filesDir, "extracted").apply { mkdirs() }

            _screenState.value = ScreenState.Extracting(
                currentPartition = selection.first(),
                progress = 0f,
                speed = 0L,
                etaSeconds = 0L
            )

            ExtractionService.startService(context)
            ExtractionStateHolder.isExtracting.value = true
            ExtractionStateHolder.cancelRequested.value = false

            var lastPartitionName = selection.first()

            try {
                handler.extract(source, selection, outDir, _options.value).collect { event ->
                    ExtractionStateHolder.currentEvent.value = event
                    when (event) {
                        is ExtractionEvent.PartitionStarted -> {
                            lastPartitionName = event.partition
                            _screenState.value = ScreenState.Extracting(
                                currentPartition = event.partition,
                                progress = 0f,
                                speed = 0L,
                                etaSeconds = 0L
                            )
                        }
                        is ExtractionEvent.BytesWritten -> {
                            val remainingBytes = (event.totalBytes - event.currentBytes).coerceAtLeast(0)
                            val eta = if (event.speedBytesPerSec > 0) remainingBytes / event.speedBytesPerSec else 0L
                            _screenState.value = ScreenState.Extracting(
                                currentPartition = event.partition,
                                progress = event.overallProgressFraction,
                                speed = event.speedBytesPerSec,
                                etaSeconds = eta
                            )
                        }
                        is ExtractionEvent.AllFinished -> {
                            ExtractionStateHolder.isExtracting.value = false
                            _screenState.value = ScreenState.Result(
                                successful = event.successful,
                                failed = event.failed,
                                durationMs = event.totalDurationMs
                            )
                        }
                        else -> {}
                    }
                }
            } catch (e: Exception) {
                ExtractionStateHolder.isExtracting.value = false
                _screenState.value = ScreenState.Result(
                    successful = emptyList(),
                    failed = mapOf(lastPartitionName to (e.message ?: "Extraction failed")),
                    durationMs = 0L
                )
            } finally {
                ExtractionService.stopService(context)
            }
        }
    }

    fun cancelExtraction() {
        val context = getApplication<Application>()
        ExtractionStateHolder.cancelRequested.value = true
        ExtractionService.stopService(context)
        _screenState.value = ScreenState.Home
    }

    private fun getFileName(context: Context, uri: Uri): String? {
        var result: String? = null
        if (uri.scheme == "content") {
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1) {
                        result = it.getString(nameIndex)
                    }
                }
            }
        }
        if (result == null) {
            result = uri.path?.substringAfterLast('/')
        }
        return result
    }

    override fun onCleared() {
        super.onCleared()
        currentSource?.close()
        currentPfd?.close()
    }
}
