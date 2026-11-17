package com.vcameraspace.feature.camera

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vcameraspace.core.designsystem.components.CustomDropdown
import com.vcameraspace.core.designsystem.components.CustomToolbar
import com.vcameraspace.core.designsystem.components.LoadingIndicator
import com.vcameraspace.core.designsystem.components.PrimaryButton
import com.vcameraspace.core.designsystem.components.SaveButton
import com.vcameraspace.core.designsystem.components.SectionHeader
import com.vcameraspace.core.designsystem.components.SettingSwitch
import com.vcameraspace.core.designsystem.components.StatusCard
import com.vcameraspace.core.designsystem.components.StatusSeverity
import com.vcameraspace.core.media.CameraSourceRegistry
import com.vcameraspace.core.media.MediaInspector
import com.vcameraspace.virtualengine.api.CameraSourceMode

@Composable
fun VirtualCameraSettingScreen(
    viewModel: CameraViewModel,
    onBackClick: () -> Unit,
    onOpenPreview: () -> Unit,
    onOpenCameraLab: () -> Unit,
    onOpenPhotoSourceSelect: (() -> Unit)? = null,
    onOpenVideoSourceSelect: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    val videoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            viewModel.onLocalVideoSelected(uri)
        }
    }

    val picturePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            viewModel.onLocalPictureSelected(uri)
        }
    }

    LaunchedEffect(uiState.feedbackMessage, uiState.errorMessage) {
        val msg = uiState.errorMessage ?: uiState.feedbackMessage
        if (!msg.isNullOrBlank()) {
            snackbarHostState.showSnackbar(msg)
            viewModel.clearFeedback()
        }
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("virtual_camera_setting_screen"),
        contentWindowInsets = WindowInsets.safeDrawing,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            CustomToolbar(
                title = "Virtual Camera Setting",
                subtitle = "Protect Your Camera Privacy.",
                onBackClick = onBackClick
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .widthIn(max = 680.dp),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                CustomDropdown(
                    selectedItem = uiState.mode,
                    items = CameraSourceMode.entries,
                    itemLabel = { CameraSourceRegistry.sourceFor(it).label },
                    onItemSelected = viewModel::selectMode,
                    label = "Camera Source Mode",
                    testTag = "camera_mode_dropdown"
                )
            }

            item {
                StatusCard(
                    title = "Mode Tip",
                    message = uiState.tipText,
                    severity = StatusSeverity.INFO,
                    icon = Icons.Default.Info
                )
            }

            when (uiState.mode) {
                CameraSourceMode.REAL_DEVICE -> {
                    item {
                        StatusCard(
                            title = "Physical Camera Active",
                            message = "VCamera Space will use the standard Android camera hardware when previewing or running supported integrations. Virtual video and picture overlays are disabled.",
                            severity = StatusSeverity.SUCCESS,
                            icon = Icons.Default.CameraAlt
                        )
                    }
                }

                CameraSourceMode.LOCAL_VIDEO -> {
                    item {
                        PrimaryButton(
                            text = "Choose Local Video",
                            icon = Icons.Default.VideoFile,
                            onClick = { videoPicker.launch(arrayOf("video/*")) },
                            modifier = Modifier.fillMaxWidth(),
                            testTag = "choose_local_video_button"
                        )
                    }
                    uiState.localVideoMetadata?.let { meta ->
                        item {
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                                ),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        text = "Selected: ${meta.displayName}",
                                        style = MaterialTheme.typography.titleSmall
                                    )
                                    Text(
                                        text = "Resolution: ${meta.width} x ${meta.height} • Duration: ${MediaInspector.formatDuration(meta.durationMs)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                    item {
                        VirtualCameraPreviewCanvas(
                            mode = uiState.mode,
                            sourceUri = uiState.sourceUri,
                            enableAudio = uiState.enableAudio,
                            autoReconnect = uiState.autoReconnect,
                            orientationDegrees = uiState.orientationDegrees,
                            onTelemetryUpdate = viewModel::updateMeasuredTelemetry,
                            onPlaybackError = viewModel::onPlaybackError
                        )
                    }
                    item {
                        HorizontalDivider()
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Note: Large high-bitrate 4K videos may require higher memory during preview. Video audio plays locally and cannot arbitrarily inject into another app's microphone.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    item {
                        SettingSwitch(
                            title = "Enable Audio",
                            subtitle = "Play local video audio track during preview",
                            checked = uiState.enableAudio,
                            onCheckedChange = viewModel::onEnableAudioChange,
                            testTag = "local_video_audio_switch"
                        )
                    }
                }

                CameraSourceMode.NETWORK_STREAM -> {
                    item {
                        OutlinedTextField(
                            value = uiState.sourceUri,
                            onValueChange = viewModel::onStreamUrlChange,
                            label = { Text("Enter video stream URL (MP4, HLS, DASH, RTSP)") },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("network_stream_url_input")
                        )
                    }
                    item {
                        PrimaryButton(
                            text = if (uiState.isValidatingStream) "Validating..." else "Validate Stream",
                            enabled = !uiState.isValidatingStream,
                            onClick = viewModel::validateNetworkStream,
                            modifier = Modifier.fillMaxWidth(),
                            testTag = "validate_stream_button"
                        )
                    }
                    if (uiState.isValidatingStream) {
                        item {
                            LoadingIndicator(label = "Checking syntax, protocol, and endpoint...")
                        }
                    }
                    uiState.streamValidation?.let { summary ->
                        item {
                            StatusCard(
                                title = summary.statusText,
                                message = summary.detail,
                                severity = if (summary.isValid) StatusSeverity.SUCCESS else StatusSeverity.ERROR
                            )
                        }
                    }
                    item {
                        VirtualCameraPreviewCanvas(
                            mode = uiState.mode,
                            sourceUri = uiState.sourceUri,
                            enableAudio = uiState.enableAudio,
                            autoReconnect = uiState.autoReconnect,
                            orientationDegrees = uiState.orientationDegrees,
                            onTelemetryUpdate = viewModel::updateMeasuredTelemetry,
                            onPlaybackError = viewModel::onPlaybackError
                        )
                    }
                    item {
                        SettingSwitch(
                            title = "Enable Audio",
                            subtitle = "Play stream audio during preview (distinct from microphone input)",
                            checked = uiState.enableAudio,
                            onCheckedChange = viewModel::onEnableAudioChange
                        )
                    }
                    item {
                        SettingSwitch(
                            title = "Automatic Reconnect",
                            subtitle = "Retry stream preparation automatically on transient network drop",
                            checked = uiState.autoReconnect,
                            onCheckedChange = viewModel::onAutoReconnectChange
                        )
                    }
                }

                CameraSourceMode.LOCAL_PICTURE -> {
                    item {
                        PrimaryButton(
                            text = "Choose Local Picture",
                            onClick = {
                                picturePicker.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                            testTag = "choose_local_picture_button"
                        )
                    }
                    item {
                        VirtualCameraPreviewCanvas(
                            mode = uiState.mode,
                            sourceUri = uiState.sourceUri,
                            enableAudio = false,
                            autoReconnect = false,
                            orientationDegrees = uiState.orientationDegrees,
                            onTelemetryUpdate = viewModel::updateMeasuredTelemetry,
                            onPlaybackError = viewModel::onPlaybackError
                        )
                    }
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedTextField(
                                value = uiState.widthInput,
                                onValueChange = viewModel::onWidthChange,
                                label = { Text("Width (0 = Auto)") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = uiState.heightInput,
                                onValueChange = viewModel::onHeightChange,
                                label = { Text("Height (0 = Auto)") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    item {
                        Text(
                            text = "Effective Output: ${uiState.effectiveResolutionLabel}. When both width and height are 0, a suitable output size is chosen automatically.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            item {
                SaveButton(
                    text = "Save",
                    onClick = viewModel::saveConfiguration,
                    modifier = Modifier.fillMaxWidth(),
                    testTag = "camera_save_button"
                )
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = onOpenPreview,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("open_camera_preview_button")
                    ) {
                        Icon(Icons.Default.Videocam, contentDescription = null)
                        Spacer(modifier = Modifier.padding(4.dp))
                        Text("Camera Preview")
                    }
                    OutlinedButton(
                        onClick = onOpenCameraLab,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("open_camera_lab_button")
                    ) {
                        Icon(Icons.Default.Science, contentDescription = null)
                        Spacer(modifier = Modifier.padding(4.dp))
                        Text("Camera Lab")
                    }
                }
            }

            if (onOpenPhotoSourceSelect != null && onOpenVideoSourceSelect != null) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(
                            onClick = onOpenPhotoSourceSelect,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("open_photo_source_button")
                        ) {
                            Text("Photo Pipeline")
                        }
                        OutlinedButton(
                            onClick = onOpenVideoSourceSelect,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("open_video_source_button")
                        ) {
                            Text("Video Codec")
                        }
                    }
                }
            }

            item {
                StatusCard(
                    title = "Android Camera Scope Disclosure",
                    message = "Ordinary Android apps cannot universally intercept Camera1/Camera2/CameraX streams inside unrelated third-party apps. This configuration controls VCamera Space previews and explicitly supported integrations.",
                    severity = StatusSeverity.WARNING
                )
            }
        }
    }
}

@Composable
fun CameraPreviewScreen(
    viewModel: CameraViewModel,
    onBackClick: () -> Unit,
    onOpenCameraLab: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("camera_preview_screen"),
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            CustomToolbar(
                title = "Camera Preview",
                subtitle = CameraSourceRegistry.sourceFor(uiState.mode).label,
                onBackClick = onBackClick,
                actions = {
                    OutlinedButton(
                        onClick = viewModel::rotateOrientation,
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        Icon(Icons.Default.RotateRight, contentDescription = "Rotate preview")
                        Text("${uiState.orientationDegrees}°")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
                .widthIn(max = 680.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            VirtualCameraPreviewCanvas(
                mode = uiState.mode,
                sourceUri = uiState.sourceUri,
                enableAudio = uiState.enableAudio,
                autoReconnect = uiState.autoReconnect,
                orientationDegrees = uiState.orientationDegrees,
                onTelemetryUpdate = viewModel::updateMeasuredTelemetry,
                onPlaybackError = viewModel::onPlaybackError,
                modifier = Modifier.weight(1f)
            )
            StatusCard(
                title = "Active Output Metrics",
                message = "Resolution: ${uiState.effectiveResolutionLabel} • Orientation: ${uiState.orientationDegrees}° • Audio: ${if (uiState.enableAudio) "Enabled" else "Muted"}",
                severity = StatusSeverity.INFO
            )
            PrimaryButton(
                text = "Open Camera Lab Diagnostics",
                icon = Icons.Default.Science,
                onClick = onOpenCameraLab,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
fun CameraLabScreen(
    viewModel: CameraViewModel,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("camera_lab_screen"),
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            CustomToolbar(
                title = "Camera Lab",
                subtitle = "Source Diagnostics & Test Report",
                onBackClick = onBackClick
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .widthIn(max = 680.dp),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PrimaryButton(
                        text = if (uiState.isPreviewActive) "Stop Preview" else "Start Preview",
                        icon = if (uiState.isPreviewActive) Icons.Default.Stop else Icons.Default.PlayArrow,
                        onClick = { viewModel.setPreviewActive(!uiState.isPreviewActive) },
                        modifier = Modifier.weight(1f),
                        testTag = "camera_lab_toggle_preview"
                    )
                    OutlinedButton(
                        onClick = viewModel::rotateOrientation,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.RotateRight, contentDescription = null)
                        Text("Rotate (${uiState.orientationDegrees}°)")
                    }
                }
            }

            if (uiState.isPreviewActive) {
                item {
                    VirtualCameraPreviewCanvas(
                        mode = uiState.mode,
                        sourceUri = uiState.sourceUri,
                        enableAudio = uiState.enableAudio,
                        autoReconnect = uiState.autoReconnect,
                        orientationDegrees = uiState.orientationDegrees,
                        onTelemetryUpdate = viewModel::updateMeasuredTelemetry,
                        onPlaybackError = viewModel::onPlaybackError
                    )
                }
            }

            item {
                SectionHeader(
                    title = "Camera Lab Test Report",
                    subtitle = "Live measured and configured parameters"
                )
            }

            item {
                StatusCard(
                    title = "Configuration & Stream Diagnostics",
                    message = buildString {
                        appendLine("Selected Mode: ${CameraSourceRegistry.sourceFor(uiState.mode).label}")
                        appendLine("Resolution: ${uiState.effectiveResolutionLabel}")
                        appendLine("Measured FPS: ${if (uiState.isPreviewActive && uiState.measuredFps > 0) "${uiState.measuredFps} fps" else "Idle"}")
                        appendLine("Orientation: ${uiState.orientationDegrees}°")
                        appendLine("Audio Preference: ${if (uiState.enableAudio) "Enabled (Preview Playback)" else "Muted"}")
                        append("Network Status: ${uiState.streamValidation?.statusText ?: "Not probed"}")
                    },
                    severity = StatusSeverity.INFO,
                    icon = Icons.Default.Science
                )
            }

            item {
                SectionHeader(
                    title = "Sanitized Diagnostic Logs",
                    subtitle = "Credentials are automatically redacted"
                )
            }

            if (uiState.diagnosticLogs.isEmpty()) {
                item {
                    Text(
                        text = "No diagnostic events logged yet. Start preview or validate a source.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                items(uiState.diagnosticLogs) { logLine ->
                    Text(
                        text = "• $logLine",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}
