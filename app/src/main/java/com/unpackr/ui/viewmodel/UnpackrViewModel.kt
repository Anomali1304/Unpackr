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

data class DownloadedFileItem(
    val name: String,
    val file: File,
    val sizeBytes: Long,
    val lastModified: Long
)

class UnpackrViewModel(application: Application) : AndroidViewModel(application) {

    private val detector = FormatDetector()
    private var currentSource: FirmwareSource? = null
    private var currentHandler: FirmwareHandler? = null
    private var currentPfd: ParcelFileDescriptor? = null

    private val _screenState = MutableStateFlow<ScreenState>(ScreenState.Home)
    val screenState = _screenState.asStateFlow()

    private val _mainTab = MutableStateFlow(0) // 0: Home, 1: Recents, 2: Downloader
    val mainTab = _mainTab.asStateFlow()

    private val _downloadedFiles = MutableStateFlow<List<DownloadedFileItem>>(emptyList())
    val downloadedFiles = _downloadedFiles.asStateFlow()

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

    init {
        refreshDownloadedFiles()
    }

    fun setMainTab(tab: Int) {
        _mainTab.value = tab
        if (tab == 2) {
            refreshDownloadedFiles()
        }
    }

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

        val cleanPath = trimmed.substringBefore('?').substringBefore('#').lowercase()
        if (cleanPath.endsWith(".tgz") || cleanPath.endsWith(".tar.gz") || cleanPath.endsWith(".tar") || cleanPath.endsWith(".tar.md5")) {
            _errorMessage.value = "Remote URL inspection only supports OTA and Recovery .zip archives (due to random-seek central directory indexing). For Fastboot .tgz archives, please use the Downloader tab to download directly to /sdcard/Download/Unpackr before unpacking."
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

                    if (!com.unpackr.engine.zip.ZipReader.isZip(httpSource)) {
                        throw IllegalArgumentException("Remote URL inspection only supports .zip archives (OTA payload.bin / .new.dat.br). Non-zip formats cannot be indexed selectively over HTTP range requests.")
                    }

                    processSource(httpSource, httpSource.fileName, trimmed)
                } catch (e: EncryptedFormatException) {
                    _screenState.value = ScreenState.Unsupported(
                        title = e.vendorFormat,
                        reason = e.message,
                        solution = e.solution,
                        technical = e.technicalDetails
                    )
                } catch (e: Exception) {
                    val rawMsg = e.message ?: "General Error"
                    val userFriendlyMsg = if (rawMsg.isBlank() || rawMsg.toIntOrNull() != null || rawMsg.startsWith("-")) {
                        "Failed to read firmware package ($rawMsg). Please verify that the direct link is still accessible."
                    } else {
                        rawMsg
                    }
                    _errorMessage.value = userFriendlyMsg
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

        // No partition pre-filter or bias: user chooses partitions cleanly
        _selectedPartitions.value = emptySet()

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

    fun getUnpackrDownloadDir(): File {
        val dir = File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "Unpackr")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun refreshDownloadedFiles() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val dir = getUnpackrDownloadDir()
                val files = dir.listFiles()
                    ?.filter { it.isFile && !it.name.startsWith(".") }
                    ?.sortedByDescending { it.lastModified() }
                    ?.map {
                        DownloadedFileItem(
                            name = it.name,
                            file = it,
                            sizeBytes = it.length(),
                            lastModified = it.lastModified()
                        )
                    } ?: emptyList()
                _downloadedFiles.value = files
            } catch (_: Exception) {
                _downloadedFiles.value = emptyList()
            }
        }
    }

    fun deleteDownloadedFile(item: DownloadedFileItem, onDone: ((Boolean) -> Unit)? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            val success = try {
                item.file.delete()
            } catch (_: Exception) {
                false
            }
            refreshDownloadedFiles()
            withContext(Dispatchers.Main) {
                onDone?.invoke(success)
            }
        }
    }

    fun clearRecentItems() {
        _recentItems.value = emptyList()
    }

    fun loadLocalFileDirect(file: File) {
        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null

            withContext(Dispatchers.IO) {
                try {
                    currentPfd?.close()
                    currentSource?.close()

                    val fis = FileInputStream(file)
                    val channel = fis.channel
                    val source = FileChannelSource(channel, closeChannelOnClose = true)
                    currentSource = source

                    processSource(source, file.name, file.absolutePath)
                } catch (e: EncryptedFormatException) {
                    _screenState.value = ScreenState.Unsupported(
                        title = e.vendorFormat,
                        reason = e.message,
                        solution = e.solution,
                        technical = e.technicalDetails
                    )
                } catch (e: Exception) {
                    val rawMsg = e.message ?: "General Error"
                    val userFriendlyMsg = if (rawMsg.isBlank() || rawMsg.toIntOrNull() != null || rawMsg.startsWith("-")) {
                        "Failed to read firmware package ($rawMsg)."
                    } else {
                        rawMsg
                    }
                    _errorMessage.value = userFriendlyMsg
                } finally {
                    _isLoading.value = false
                }
            }
        }
    }

    fun startDownloadToUnpackr(url: String, context: Context) {
        val trimmed = url.trim()
        if (trimmed.isBlank() || (!trimmed.startsWith("http://", ignoreCase = true) && !trimmed.startsWith("https://", ignoreCase = true))) {
            android.widget.Toast.makeText(context, "Invalid URL. Please enter an http:// or https:// link", android.widget.Toast.LENGTH_SHORT).show()
            return
        }

        try {
            getUnpackrDownloadDir()
            val fileName = trimmed.substringBefore('?').substringAfterLast('/').ifBlank { "firmware_${System.currentTimeMillis()}.bin" }
            val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as android.app.DownloadManager
            val request = android.app.DownloadManager.Request(Uri.parse(trimmed))
                .setTitle(fileName)
                .setDescription("Downloading to /sdcard/Download/Unpackr...")
                .setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(android.os.Environment.DIRECTORY_DOWNLOADS, "Unpackr/$fileName")
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(true)

            downloadManager.enqueue(request)
            android.widget.Toast.makeText(
                context,
                "Downloading $fileName to /sdcard/Download/Unpackr...",
                android.widget.Toast.LENGTH_LONG
            ).show()
            refreshDownloadedFiles()
        } catch (e: Exception) {
            android.widget.Toast.makeText(
                context,
                "Failed to start download: ${e.message}",
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }

    /**
     * Downloads the original full ROM package directly from the remote link
     * into the device's public Downloads/Unpackr directory using Android DownloadManager.
     */
    fun downloadFullRom(context: Context) {
        val httpSource = currentSource as? com.unpackr.engine.source.HttpFirmwareSource
        if (httpSource == null) {
            android.widget.Toast.makeText(context, "This file is not from a remote URL.", android.widget.Toast.LENGTH_SHORT).show()
            return
        }

        try {
            getUnpackrDownloadDir()
            val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as android.app.DownloadManager
            val request = android.app.DownloadManager.Request(Uri.parse(httpSource.url))
                .setTitle(httpSource.fileName)
                .setDescription("Downloading full original ROM archive...")
                .setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(android.os.Environment.DIRECTORY_DOWNLOADS, "Unpackr/${httpSource.fileName}")
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(true)

            downloadManager.enqueue(request)
            android.widget.Toast.makeText(
                context,
                "Downloading ${httpSource.fileName} to /sdcard/Download/Unpackr...",
                android.widget.Toast.LENGTH_LONG
            ).show()
            refreshDownloadedFiles()
        } catch (e: Exception) {
            android.widget.Toast.makeText(
                context,
                "Failed to start download: ${e.message}",
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
