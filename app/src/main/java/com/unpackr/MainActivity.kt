package com.unpackr

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.unpackr.ui.screens.DetectedScreen
import com.unpackr.ui.screens.ExtractingScreen
import com.unpackr.ui.screens.HomeScreen
import com.unpackr.ui.screens.OptionsBottomSheet
import com.unpackr.ui.screens.ResultScreen
import com.unpackr.ui.screens.SettingsScreen
import com.unpackr.ui.screens.UnsupportedScreen
import com.unpackr.ui.theme.UnpackrTheme
import com.unpackr.ui.viewmodel.ScreenState
import com.unpackr.ui.viewmodel.UnpackrViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: UnpackrViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            UnpackrTheme {
                // Request POST_NOTIFICATIONS permission on Android 13+
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val notifPermissionLauncher = rememberLauncherForActivityResult(
                        contract = ActivityResultContracts.RequestPermission(),
                        onResult = {}
                    )
                    LaunchedEffect(Unit) {
                        if (ContextCompat.checkSelfPermission(
                                this@MainActivity,
                                Manifest.permission.POST_NOTIFICATIONS
                            ) != PackageManager.PERMISSION_GRANTED
                        ) {
                            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                }

                UnpackrApp(viewModel = viewModel)
            }
        }
    }
}

@Composable
fun UnpackrApp(viewModel: UnpackrViewModel) {
    val screenState by viewModel.screenState.collectAsStateWithLifecycle()
    val selectedPartitions by viewModel.selectedPartitions.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val options by viewModel.options.collectAsStateWithLifecycle()
    val recentFiles by viewModel.recentItems.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()

    var showOptionsSheet by remember { mutableStateOf(false) }

    Surface(modifier = Modifier.fillMaxSize()) {
        when (val state = screenState) {
            is ScreenState.Home -> {
                HomeScreen(
                    isLoading = isLoading,
                    errorMessage = errorMessage,
                    recentFiles = recentFiles,
                    onFileSelected = { uri -> viewModel.loadFirmwareUri(uri) },
                    onUrlSubmitted = { url -> viewModel.loadFirmwareUrl(url) },
                    onNavigateSettings = { viewModel.navigateTo(ScreenState.Settings) }
                )
            }

            is ScreenState.Detected -> {
                BackHandler {
                    viewModel.navigateTo(ScreenState.Home)
                }

                DetectedScreen(
                    info = state.info,
                    match = state.match,
                    selectedPartitions = selectedPartitions,
                    searchQuery = searchQuery,
                    onSearchQueryChange = { viewModel.updateSearchQuery(it) },
                    onTogglePartition = { viewModel.togglePartition(it) },
                    onSelectAll = { viewModel.selectAll(state.info.partitions) },
                    onDeselectAll = { viewModel.deselectAll() },
                    onSelectCommon = { viewModel.selectCommon(state.info.partitions) },
                    onSelectSystem = { viewModel.selectSystem(state.info.partitions) },
                    onOptionsClick = { showOptionsSheet = true },
                    onStartExtraction = { viewModel.startExtraction() },
                    onBack = { viewModel.navigateTo(ScreenState.Home) }
                )

                if (showOptionsSheet) {
                    OptionsBottomSheet(
                        currentOptions = options,
                        onDismiss = { showOptionsSheet = false },
                        onSave = { viewModel.updateOptions(it) }
                    )
                }
            }

            is ScreenState.Extracting -> {
                BackHandler {
                    // Handled inside ExtractingScreen via cancel confirmation
                }

                ExtractingScreen(
                    currentPartition = state.currentPartition,
                    progress = state.progress,
                    speedBytesPerSec = state.speed,
                    etaSeconds = state.etaSeconds,
                    onCancel = { viewModel.cancelExtraction() }
                )
            }

            is ScreenState.Result -> {
                BackHandler {
                    viewModel.navigateTo(ScreenState.Home)
                }

                ResultScreen(
                    successful = state.successful,
                    failed = state.failed,
                    durationMs = state.durationMs,
                    onDone = { viewModel.navigateTo(ScreenState.Home) }
                )
            }

            is ScreenState.Unsupported -> {
                BackHandler {
                    viewModel.navigateTo(ScreenState.Home)
                }

                UnsupportedScreen(
                    title = state.title,
                    reason = state.reason,
                    solution = state.solution,
                    technical = state.technical,
                    onBack = { viewModel.navigateTo(ScreenState.Home) }
                )
            }

            is ScreenState.Settings -> {
                BackHandler {
                    viewModel.navigateTo(ScreenState.Home)
                }

                SettingsScreen(
                    onBack = { viewModel.navigateTo(ScreenState.Home) }
                )
            }
        }
    }
}
