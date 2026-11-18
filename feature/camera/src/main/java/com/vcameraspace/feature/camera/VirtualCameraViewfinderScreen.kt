package com.vcameraspace.feature.camera

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.vcameraspace.core.common.model.AppResult
import com.vcameraspace.core.media.CameraConfigurationRepository
import com.vcameraspace.core.media.CameraSourceRegistry
import com.vcameraspace.core.media.CapturedClipResult
import com.vcameraspace.core.media.CapturedPhotoResult
import com.vcameraspace.core.media.GlColorFilterMode
import com.vcameraspace.core.media.GlRenderTelemetry
import com.vcameraspace.core.media.VirtualCameraAdapterManager
import com.vcameraspace.core.media.VirtualCaptureExporter
import com.vcameraspace.virtualengine.api.CameraSourceMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

enum class CaptureIntentMode {
    INTERACTIVE,
    IMAGE_CAPTURE,
    VIDEO_CAPTURE
}

data class VirtualViewfinderUiState(
    val spaceId: String = "space_1",
    val packageName: String? = null,
    val captureMode: CaptureIntentMode = CaptureIntentMode.INTERACTIVE,
    val mode: CameraSourceMode = CameraSourceMode.REAL_DEVICE,
    val sourceUri: String = "",
    val enableAudio: Boolean = false,
    val autoReconnect: Boolean = true,
    val orientationDegrees: Int = 0,
    val mirrorHorizontal: Boolean = false,
    val filterMode: GlColorFilterMode = GlColorFilterMode.STANDARD,
    val targetWidth: Int = 0,
    val targetHeight: Int = 0,
    val measuredFps: Double = 0.0,
    val measuredResolution: String = "Auto",
    val glRendererName: String = "OpenGL ES 2.0",
    val isCapturing: Boolean = false,
    val isRecordingVirtualClip: Boolean = false,
    val recordingElapsedSec: Int = 0,
    val lastCaptureSummary: String? = null,
    val feedbackMessage: String? = null,
    val errorMessage: String? = null
) {
    val activeSourceLabel: String
        get() = CameraSourceRegistry.sourceFor(mode).label
}

class VirtualViewfinderViewModel(
    private val spaceId: String,
    private val packageName: String?,
    private val captureMode: CaptureIntentMode,
    private val configRepository: CameraConfigurationRepository,
    private val adapterManager: VirtualCameraAdapterManager?,
    private val captureExporter: VirtualCaptureExporter,
    private val extraOutputUri: Uri? = null,
    private val contentUriResolver: ((File) -> Uri?)? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        VirtualViewfinderUiState(
            spaceId = spaceId,
            packageName = packageName,
            captureMode = captureMode
        )
    )
    val uiState: StateFlow<VirtualViewfinderUiState> = _uiState.asStateFlow()

    init {
        refreshSourceConfiguration()
        if (captureMode == CaptureIntentMode.INTERACTIVE) {
            viewModelScope.launch {
                adapterManager?.startVirtualSession(spaceId, packageName)
            }
        }
    }

    fun refreshSourceConfiguration() {
        viewModelScope.launch {
            val roomRecord = configRepository.observeConfig(spaceId, packageName).first()
            val effectiveShared = adapterManager?.resolveEffectiveConfig(spaceId, packageName)
            val resolvedMode: CameraSourceMode
            val resolvedUri: String
            val resolvedAudio: Boolean
            val resolvedReconnect: Boolean
            val resolvedW: Int
            val resolvedH: Int

            if (roomRecord.updatedAtEpochMs > 0L) {
                resolvedMode = roomRecord.mode
                resolvedUri = roomRecord.sourceUri.orEmpty()
                resolvedAudio = roomRecord.enableAudio
                resolvedReconnect = roomRecord.autoReconnect
                resolvedW = roomRecord.width
                resolvedH = roomRecord.height
            } else if (effectiveShared != null && effectiveShared.updatedAtEpochMs > 0L) {
                resolvedMode = effectiveShared.mode
                resolvedUri = effectiveShared.sourceUri.orEmpty()
                resolvedAudio = effectiveShared.enableAudio
                resolvedReconnect = effectiveShared.autoReconnect
                resolvedW = effectiveShared.targetWidth
                resolvedH = effectiveShared.targetHeight
            } else {
                val fallback = adapterManager?.state?.value?.sharedConfig
                resolvedMode = fallback?.mode ?: CameraSourceMode.REAL_DEVICE
                resolvedUri = fallback?.sourceUri.orEmpty()
                resolvedAudio = fallback?.enableAudio ?: false
                resolvedReconnect = fallback?.autoReconnect ?: true
                resolvedW = fallback?.targetWidth ?: 0
                resolvedH = fallback?.targetHeight ?: 0
            }

            _uiState.update {
                it.copy(
                    mode = resolvedMode,
                    sourceUri = resolvedUri,
                    enableAudio = resolvedAudio,
                    autoReconnect = resolvedReconnect,
                    orientationDegrees = roomRecord.orientationDegrees,
                    targetWidth = resolvedW,
                    targetHeight = resolvedH
                )
            }
        }
    }

    fun rotateOrientation() {
        _uiState.update { it.copy(orientationDegrees = (it.orientationDegrees + 90) % 360) }
    }

    fun toggleMirrorHorizontal() {
        _uiState.update { it.copy(mirrorHorizontal = !it.mirrorHorizontal) }
    }

    fun cycleColorFilter() {
        val entries = GlColorFilterMode.entries
        _uiState.update { cur ->
            val next = (entries.indexOf(cur.filterMode) + 1) % entries.size
            cur.copy(filterMode = entries[next])
        }
    }

    fun onTelemetryUpdate(fps: Double, resolution: String) {
        _uiState.update {
            it.copy(
                measuredFps = if (fps > 0.0) fps else it.measuredFps,
                measuredResolution = if (resolution.isNotBlank()) resolution else it.measuredResolution
            )
        }
    }

    fun onGlTelemetry(telemetry: GlRenderTelemetry) {
        _uiState.update {
            it.copy(
                glRendererName = telemetry.glRendererName,
                measuredFps = if (telemetry.measuredFps > 0.0) telemetry.measuredFps else it.measuredFps
            )
        }
    }

    fun onPlaybackError(message: String) {
        _uiState.update { it.copy(errorMessage = message) }
    }

    fun capturePhotoSnapshot(
        liveViewBitmap: Bitmap?,
        onCapturedForCaller: ((CapturedPhotoResult, Bitmap) -> Unit)? = null
    ) {
        if (_uiState.value.isCapturing) return
        val snapshotState = _uiState.value
        viewModelScope.launch {
            _uiState.update { it.copy(isCapturing = true, errorMessage = null) }
            val bmpRes = captureExporter.captureSnapshotBitmap(
                mode = snapshotState.mode,
                sourceUri = snapshotState.sourceUri.ifBlank { null },
                liveViewBitmap = liveViewBitmap,
                orientationDegrees = snapshotState.orientationDegrees,
                mirrorHorizontal = snapshotState.mirrorHorizontal,
                targetWidth = snapshotState.targetWidth,
                targetHeight = snapshotState.targetHeight
            )
            when (bmpRes) {
                is AppResult.Failure -> {
                    _uiState.update {
                        it.copy(isCapturing = false, errorMessage = bmpRes.error.message)
                    }
                }
                is AppResult.Success -> {
                    val bitmap = bmpRes.data
                    val writeRes = captureExporter.writeSnapshotToTarget(
                        bitmap = bitmap,
                        destinationUri = extraOutputUri,
                        contentUriResolver = contentUriResolver
                    )
                    when (writeRes) {
                        is AppResult.Failure -> {
                            bitmap.recycle()
                            _uiState.update {
                                it.copy(isCapturing = false, errorMessage = writeRes.error.message)
                            }
                        }
                        is AppResult.Success -> {
                            val thumb = captureExporter.createCaptureThumbnail(bitmap)
                            bitmap.recycle()
                            _uiState.update {
                                it.copy(
                                    isCapturing = false,
                                    lastCaptureSummary = writeRes.data.summary,
                                    feedbackMessage = writeRes.data.summary
                                )
                            }
                            onCapturedForCaller?.invoke(writeRes.data, thumb)
                        }
                    }
                }
            }
        }
    }

    fun triggerVirtualClipCapture(
        liveViewBitmap: Bitmap?,
        onClipCapturedForCaller: ((CapturedClipResult) -> Unit)? = null
    ) {
        val current = _uiState.value
        if (current.isCapturing) return

        if (current.captureMode == CaptureIntentMode.INTERACTIVE && !current.isRecordingVirtualClip) {
            _uiState.update { it.copy(isRecordingVirtualClip = true, recordingElapsedSec = 0) }
            viewModelScope.launch {
                while (isActive && _uiState.value.isRecordingVirtualClip) {
                    delay(1_000L)
                    _uiState.update { st ->
                        if (st.isRecordingVirtualClip) st.copy(recordingElapsedSec = st.recordingElapsedSec + 1)
                        else st
                    }
                }
            }
            return
        }

        val maxDurMs = if (current.recordingElapsedSec > 0) {
            (current.recordingElapsedSec * 1_000L).coerceIn(2_000L, 12_000L)
        } else {
            8_000L
        }

        viewModelScope.launch {
            _uiState.update {
                it.copy(isCapturing = true, isRecordingVirtualClip = false, errorMessage = null)
            }
            when (
                val res = captureExporter.exportVirtualClip(
                    mode = current.mode,
                    sourceUri = current.sourceUri.ifBlank { null },
                    liveViewBitmap = liveViewBitmap,
                    orientationDegrees = current.orientationDegrees,
                    mirrorHorizontal = current.mirrorHorizontal,
                    destinationUri = extraOutputUri,
                    contentUriResolver = contentUriResolver,
                    maxDurationMs = maxDurMs
                )
            ) {
                is AppResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isCapturing = false,
                            recordingElapsedSec = 0,
                            lastCaptureSummary = res.data.summary,
                            feedbackMessage = res.data.summary
                        )
                    }
                    onClipCapturedForCaller?.invoke(res.data)
                }
                is AppResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            isCapturing = false,
                            recordingElapsedSec = 0,
                            errorMessage = res.error.message
                        )
                    }
                }
            }
        }
    }

    fun clearFeedback() {
        _uiState.update { it.copy(feedbackMessage = null, errorMessage = null) }
    }

    companion object {
        fun factory(
            spaceId: String,
            packageName: String?,
            captureMode: CaptureIntentMode,
            configRepository: CameraConfigurationRepository,
            adapterManager: VirtualCameraAdapterManager?,
            captureExporter: VirtualCaptureExporter,
            extraOutputUri: Uri? = null,
            contentUriResolver: ((File) -> Uri?)? = null
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return VirtualViewfinderViewModel(
                    spaceId = spaceId,
                    packageName = packageName,
                    captureMode = captureMode,
                    configRepository = configRepository,
                    adapterManager = adapterManager,
                    captureExporter = captureExporter,
                    extraOutputUri = extraOutputUri,
                    contentUriResolver = contentUriResolver
                ) as T
            }
        }
    }
}

@Composable
fun VirtualCameraViewfinderScreen(
    viewModel: VirtualViewfinderViewModel,
    onBackClick: () -> Unit,
    onOpenSettings: (() -> Unit)? = null,
    onPhotoCapturedForCaller: ((CapturedPhotoResult, Bitmap) -> Unit)? = null,
    onVideoCapturedForCaller: ((CapturedClipResult) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var liveBitmapProvider by remember { mutableStateOf<(() -> Bitmap?)?>(null) }

    LaunchedEffect(uiState.feedbackMessage, uiState.errorMessage) {
        val msg = uiState.errorMessage ?: uiState.feedbackMessage
        if (!msg.isNullOrBlank()) {
            snackbarHostState.showSnackbar(msg)
            viewModel.clearFeedback()
        }
    }

    VirtualCameraViewfinderContent(
        uiState = uiState,
        snackbarHostState = snackbarHostState,
        onBackClick = onBackClick,
        onOpenSettings = onOpenSettings,
        onRotate = viewModel::rotateOrientation,
        onToggleMirror = viewModel::toggleMirrorHorizontal,
        onCycleFilter = viewModel::cycleColorFilter,
        onTelemetryUpdate = viewModel::onTelemetryUpdate,
        onGlTelemetry = viewModel::onGlTelemetry,
        onPlaybackError = viewModel::onPlaybackError,
        onBitmapProviderReady = { liveBitmapProvider = it },
        onCapturePhoto = {
            val liveBmp = runCatching { liveBitmapProvider?.invoke() }.getOrNull()
            viewModel.capturePhotoSnapshot(liveBmp, onPhotoCapturedForCaller)
        },
        onCaptureClip = {
            val liveBmp = runCatching { liveBitmapProvider?.invoke() }.getOrNull()
            viewModel.triggerVirtualClipCapture(liveBmp, onVideoCapturedForCaller)
        },
        modifier = modifier
    )
}

@Composable
fun VirtualCameraViewfinderContent(
    uiState: VirtualViewfinderUiState,
    snackbarHostState: SnackbarHostState,
    onBackClick: () -> Unit,
    onOpenSettings: (() -> Unit)?,
    onRotate: () -> Unit,
    onToggleMirror: () -> Unit,
    onCycleFilter: () -> Unit,
    onTelemetryUpdate: (Double, String) -> Unit,
    onGlTelemetry: (GlRenderTelemetry) -> Unit,
    onPlaybackError: (String) -> Unit,
    onBitmapProviderReady: ((() -> Bitmap?) -> Unit),
    onCapturePhoto: () -> Unit,
    onCaptureClip: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0A1011))
            .testTag("virtual_camera_viewfinder_screen")
    ) {
        VirtualCameraPreviewCanvas(
            mode = uiState.mode,
            sourceUri = uiState.sourceUri,
            enableAudio = uiState.enableAudio,
            autoReconnect = uiState.autoReconnect,
            orientationDegrees = uiState.orientationDegrees,
            useOpenGlPipeline = true,
            mirrorHorizontal = uiState.mirrorHorizontal,
            filterMode = uiState.filterMode,
            onTelemetryUpdate = onTelemetryUpdate,
            onGlTelemetry = onGlTelemetry,
            onPlaybackError = onPlaybackError,
            fillContainer = true,
            onBitmapProviderReady = onBitmapProviderReady,
            modifier = Modifier.fillMaxSize()
        )

        // Top HUD bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            IconButton(
                onClick = onBackClick,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.55f))
                    .testTag("viewfinder_close_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Close virtual camera viewfinder",
                    tint = Color.White
                )
            }

            Surface(
                shape = RoundedCornerShape(999.dp),
                color = Color.Black.copy(alpha = 0.62f),
                border = BorderStroke(1.dp, Color(0xFF4DB6AC).copy(alpha = 0.45f)),
                modifier = Modifier.weight(1f, fill = false).padding(horizontal = 8.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Videocam,
                        contentDescription = null,
                        tint = Color(0xFF4DB6AC),
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = buildString {
                            append(uiState.activeSourceLabel)
                            if (uiState.measuredResolution != "Auto") {
                                append(" • ${uiState.measuredResolution}")
                            }
                        },
                        style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            if (onOpenSettings != null) {
                IconButton(
                    onClick = onOpenSettings,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.55f))
                        .testTag("viewfinder_settings_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Configure virtual camera source",
                        tint = Color.White
                    )
                }
            } else {
                Spacer(modifier = Modifier.size(48.dp))
            }
        }

        // Bottom HUD controls + shutter deck
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(Color.Black.copy(alpha = 0.68f))
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (uiState.sourceUri.isBlank() && uiState.mode != CameraSourceMode.REAL_DEVICE && onOpenSettings != null) {
                OutlinedButton(
                    onClick = onOpenSettings,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF4DB6AC)),
                    modifier = Modifier.testTag("viewfinder_pick_source_button")
                ) {
                    Text("Pick Media Source (${uiState.mode.name})")
                }
            }

            uiState.lastCaptureSummary?.let { summary ->
                Text(
                    text = summary,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = Color(0xFF80CBC4)
                )
            }

            if (uiState.isRecordingVirtualClip) {
                Text(
                    text = String.format(Locale.US, "CAPTURING CLIP • 00:%02d", uiState.recordingElapsedSec),
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    ),
                    color = Color(0xFFFF6E6E)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                HudToggleChip(
                    icon = Icons.AutoMirrored.Filled.RotateRight,
                    label = "${uiState.orientationDegrees}°",
                    onClick = onRotate,
                    modifier = Modifier.weight(1f),
                    testTag = "viewfinder_rotate_button"
                )
                HudToggleChip(
                    icon = Icons.Default.Flip,
                    label = if (uiState.mirrorHorizontal) "Mirrored" else "Mirror",
                    onClick = onToggleMirror,
                    modifier = Modifier.weight(1f),
                    testTag = "viewfinder_mirror_button"
                )
                HudToggleChip(
                    icon = Icons.Default.Tune,
                    label = uiState.filterMode.label.substringBefore(" "),
                    onClick = onCycleFilter,
                    modifier = Modifier.weight(1f),
                    testTag = "viewfinder_filter_button"
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (uiState.captureMode != CaptureIntentMode.VIDEO_CAPTURE) {
                    ShutterActionButton(
                        label = if (uiState.captureMode == CaptureIntentMode.IMAGE_CAPTURE) "Use Photo" else "Snapshot",
                        icon = Icons.Default.CameraAlt,
                        containerColor = Color.White,
                        contentColor = Color.Black,
                        isBusy = uiState.isCapturing,
                        onClick = onCapturePhoto,
                        testTag = "viewfinder_capture_photo_button"
                    )
                }

                if (uiState.captureMode != CaptureIntentMode.IMAGE_CAPTURE) {
                    ShutterActionButton(
                        label = when {
                            uiState.captureMode == CaptureIntentMode.VIDEO_CAPTURE -> "Use Virtual Clip"
                            uiState.isRecordingVirtualClip -> "Stop & Save Clip"
                            else -> "Export Clip"
                        },
                        icon = if (uiState.isRecordingVirtualClip) Icons.Default.Stop else Icons.Default.Videocam,
                        containerColor = Color(0xFFCC0000),
                        contentColor = Color.White,
                        isBusy = uiState.isCapturing,
                        onClick = onCaptureClip,
                        testTag = "viewfinder_capture_clip_button"
                    )
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(top = 64.dp)
        )
    }
}

@Composable
private fun HudToggleChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    testTag: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .height(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .testTag(testTag),
        shape = RoundedCornerShape(12.dp),
        color = Color.White.copy(alpha = 0.12f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.2f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ShutterActionButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    containerColor: Color,
    contentColor: Color,
    isBusy: Boolean,
    onClick: () -> Unit,
    testTag: String
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Surface(
            modifier = Modifier
                .size(68.dp)
                .clip(CircleShape)
                .clickable(enabled = !isBusy, onClick = onClick)
                .testTag(testTag),
            shape = CircleShape,
            color = containerColor,
            border = BorderStroke(3.dp, Color.White.copy(alpha = 0.85f))
        ) {
            Box(contentAlignment = Alignment.Center) {
                if (isBusy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(26.dp),
                        color = contentColor,
                        strokeWidth = 3.dp
                    )
                } else {
                    Icon(
                        imageVector = icon,
                        contentDescription = label,
                        tint = contentColor,
                        modifier = Modifier.size(30.dp)
                    )
                }
            }
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            color = Color.White
        )
    }
}
