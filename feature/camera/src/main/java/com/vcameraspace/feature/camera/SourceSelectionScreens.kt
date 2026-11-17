package com.vcameraspace.feature.camera

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vcameraspace.core.common.model.AppResult
import com.vcameraspace.core.designsystem.components.CustomToolbar
import com.vcameraspace.core.designsystem.components.LoadingIndicator
import com.vcameraspace.core.designsystem.components.PrimaryButton
import com.vcameraspace.core.designsystem.components.SaveButton
import com.vcameraspace.core.designsystem.components.SectionHeader
import com.vcameraspace.core.designsystem.components.SettingSwitch
import com.vcameraspace.core.designsystem.components.StatusCard
import com.vcameraspace.core.designsystem.components.StatusSeverity
import com.vcameraspace.core.media.PhotoFramePipelineResult
import com.vcameraspace.core.media.SharedCameraSourceConfig
import com.vcameraspace.core.media.VideoCodecPipelineResult
import com.vcameraspace.core.media.VirtualCameraAdapterManager
import com.vcameraspace.core.media.YuvFrameFormat
import com.vcameraspace.virtualengine.api.CameraSourceMode
import kotlinx.coroutines.launch

@Composable
fun PhotoSourceScreen(
    viewModel: CameraViewModel,
    adapterManager: VirtualCameraAdapterManager,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val adapterState by adapterManager.state.collectAsStateWithLifecycle()
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var selectedFormat by remember(adapterState.sharedConfig.outputFormat) {
        mutableStateOf(adapterState.sharedConfig.outputFormat)
    }
    var isProcessing by remember { mutableStateOf(false) }
    var pipelineResult by remember { mutableStateOf<PhotoFramePipelineResult?>(null) }

    val photoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            viewModel.selectMode(CameraSourceMode.LOCAL_PICTURE)
            viewModel.onLocalPictureSelected(uri)
        }
    }

    val documentPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.selectMode(CameraSourceMode.LOCAL_PICTURE)
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
            .testTag("photo_source_screen"),
        contentWindowInsets = WindowInsets.safeDrawing,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            CustomToolbar(
                title = "Photo Source Selection",
                subtitle = "Decode, Resize & YUV I420/YUV_420_888 Pipeline",
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
                StatusCard(
                    title = "Photo-to-YUV Frame Pipeline",
                    message = "Pick an image via Android Photo Picker or SAF. VCamera Space decodes the bitmap, resizes cleanly to even YUV dimensions, and converts pixels into planar I420 / YUV_420_888 frames.",
                    severity = StatusSeverity.INFO,
                    icon = Icons.Default.Photo
                )
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PrimaryButton(
                        text = "Photo Picker",
                        icon = Icons.Default.Photo,
                        onClick = {
                            photoPicker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        modifier = Modifier.weight(1f),
                        testTag = "photo_source_picker_button"
                    )
                    OutlinedButton(
                        onClick = { documentPicker.launch(arrayOf("image/*")) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Pick via Files (SAF)")
                    }
                }
            }

            item {
                SectionHeader(
                    title = "Output Frame Format & Scaling",
                    subtitle = "Even dimensions ensure lossless 4:2:0 chroma alignment"
                )
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    YuvFrameFormat.entries.forEach { fmt ->
                        FilterChip(
                            selected = selectedFormat == fmt,
                            onClick = { selectedFormat = fmt },
                            label = { Text(fmt.name) }
                        )
                    }
                }
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

            if (uiState.sourceUri.isNotBlank()) {
                item {
                    OutlinedButton(
                        enabled = !isProcessing,
                        onClick = {
                            val reqW = uiState.widthInput.toIntOrNull() ?: 0
                            val reqH = uiState.heightInput.toIntOrNull() ?: 0
                            coroutineScope.launch {
                                isProcessing = true
                                when (val res = adapterManager.photoProcessor.processPhoto(
                                    uriString = uiState.sourceUri,
                                    requestedWidth = reqW,
                                    requestedHeight = reqH,
                                    outputFormat = selectedFormat,
                                    retainPreviewBitmap = true
                                )) {
                                    is AppResult.Success -> pipelineResult = res.data
                                    is AppResult.Failure -> snackbarHostState.showSnackbar(res.error.message)
                                }
                                isProcessing = false
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("photo_run_yuv_pipeline_button")
                    ) {
                        Icon(Icons.Default.Science, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (isProcessing) "Converting to ${selectedFormat.name}..." else "Run Resize & ${selectedFormat.name} Conversion")
                    }
                }
            }

            if (isProcessing) {
                item {
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        LoadingIndicator(label = "Decoding bitmap and building Y/U/V planes...")
                    }
                }
            }

            pipelineResult?.let { result ->
                item {
                    result.decodedPreviewBitmap?.let { bmp ->
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = "Resized YUV source preview",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(200.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                        )
                    }
                }
                item {
                    StatusCard(
                        title = "Frame Conversion Verified (${result.outputFormat.name})",
                        message = buildString {
                            appendLine("Input Bitmap: ${result.intrinsicWidth} x ${result.intrinsicHeight}")
                            appendLine("Resized YUV Output: ${result.outputWidth} x ${result.outputHeight}")
                            appendLine("Packed Frame Bytes: ${result.packedI420Bytes} bytes")
                            appendLine("Conversion Time: ${result.conversionDurationMs} ms")
                            append("Frame Checksum (CRC32): ${result.frameChecksumHex}")
                        },
                        severity = StatusSeverity.SUCCESS,
                        icon = Icons.Default.CheckCircle
                    )
                }
            }

            item {
                SaveButton(
                    text = "Save as Shared Virtual Camera Photo Source",
                    enabled = uiState.sourceUri.isNotBlank(),
                    onClick = {
                        viewModel.selectMode(CameraSourceMode.LOCAL_PICTURE)
                        viewModel.saveConfiguration()
                        val reqW = uiState.widthInput.toIntOrNull() ?: 0
                        val reqH = uiState.heightInput.toIntOrNull() ?: 0
                        coroutineScope.launch {
                            adapterManager.saveSharedConfig(
                                SharedCameraSourceConfig(
                                    mode = CameraSourceMode.LOCAL_PICTURE,
                                    sourceUri = uiState.sourceUri,
                                    outputFormat = selectedFormat,
                                    targetWidth = reqW,
                                    targetHeight = reqH,
                                    enableAudio = false,
                                    autoReconnect = false
                                )
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    testTag = "photo_source_save_button"
                )
            }
        }
    }
}

@Composable
fun VideoSourceScreen(
    viewModel: CameraViewModel,
    adapterManager: VirtualCameraAdapterManager,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val adapterState by adapterManager.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var selectedFormat by remember(adapterState.sharedConfig.outputFormat) {
        mutableStateOf(adapterState.sharedConfig.outputFormat)
    }
    var isDecoding by remember { mutableStateOf(false) }
    var codecResult by remember { mutableStateOf<VideoCodecPipelineResult?>(null) }

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
            viewModel.selectMode(CameraSourceMode.LOCAL_VIDEO)
            viewModel.onLocalVideoSelected(uri)
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
            .testTag("video_source_screen"),
        contentWindowInsets = WindowInsets.safeDrawing,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            CustomToolbar(
                title = "Video Source Selection",
                subtitle = "MediaExtractor + MediaCodec Frame Pipeline",
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
                StatusCard(
                    title = "MediaCodec + MediaExtractor Video Decoding",
                    message = "Select a local MP4/MKV video via SAF. VCamera Space previews with Media3 and decodes hardware/software frames using MediaCodec into YUV_420_888 / I420 buffers with presentation timestamp tracking.",
                    severity = StatusSeverity.INFO,
                    icon = Icons.Default.VideoFile
                )
            }

            item {
                PrimaryButton(
                    text = "Select Video File (SAF)",
                    icon = Icons.Default.VideoFile,
                    onClick = { videoPicker.launch(arrayOf("video/*")) },
                    modifier = Modifier.fillMaxWidth(),
                    testTag = "video_source_pick_button"
                )
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    YuvFrameFormat.entries.forEach { fmt ->
                        FilterChip(
                            selected = selectedFormat == fmt,
                            onClick = { selectedFormat = fmt },
                            label = { Text(fmt.name) }
                        )
                    }
                }
            }

            if (uiState.sourceUri.isNotBlank()) {
                item {
                    VirtualCameraPreviewCanvas(
                        mode = CameraSourceMode.LOCAL_VIDEO,
                        sourceUri = uiState.sourceUri,
                        enableAudio = uiState.enableAudio,
                        autoReconnect = uiState.autoReconnect,
                        orientationDegrees = uiState.orientationDegrees,
                        onTelemetryUpdate = viewModel::updateMeasuredTelemetry,
                        onPlaybackError = viewModel::onPlaybackError
                    )
                }

                item {
                    OutlinedButton(
                        enabled = !isDecoding,
                        onClick = {
                            coroutineScope.launch {
                                isDecoding = true
                                when (val res = adapterManager.videoCodecProcessor.decodeAndValidateFrames(
                                    uriString = uiState.sourceUri,
                                    maxSampleFrames = 10,
                                    targetFormat = selectedFormat
                                )) {
                                    is AppResult.Success -> codecResult = res.data
                                    is AppResult.Failure -> snackbarHostState.showSnackbar(res.error.message)
                                }
                                isDecoding = false
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("video_run_mediacodec_button")
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (isDecoding) "Decoding via MediaCodec..." else "Run MediaCodec Frame Decode Probe")
                    }
                }
            }

            if (isDecoding) {
                item {
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        LoadingIndicator(label = "Extracting track & decoding YUV frames via MediaCodec...")
                    }
                }
            }

            codecResult?.let { result ->
                item {
                    StatusCard(
                        title = "MediaCodec Decode Verified (${result.codecName})",
                        message = buildString {
                            appendLine("Track MIME: ${result.mimeType}")
                            appendLine("Decoded Dimensions: ${result.width} x ${result.height}")
                            appendLine("Sample Frames Decoded: ${result.decodedFrameCount} (${String.format("%.1f", result.estimatedFps)} fps)")
                            appendLine("Avg Presentation Delta: ${String.format("%.2f", result.averageFrameDeltaMs)} ms")
                            appendLine("YUV Frame Size (${result.outputFormat.name}): ${result.yuvFrameBytes} bytes")
                            append("Processing Time: ${result.processingDurationMs} ms")
                        },
                        severity = StatusSeverity.SUCCESS,
                        icon = Icons.Default.CheckCircle
                    )
                }
            }

            item {
                SettingSwitch(
                    title = "Enable Video Audio",
                    subtitle = "Plays audio locally inside preview (separate from third-party mic input)",
                    checked = uiState.enableAudio,
                    onCheckedChange = viewModel::onEnableAudioChange
                )
            }
            item {
                SettingSwitch(
                    title = "Seamless Loop & Reconnect",
                    subtitle = "Automatically loops video playback on end-of-stream",
                    checked = uiState.autoReconnect,
                    onCheckedChange = viewModel::onAutoReconnectChange
                )
            }

            item {
                SaveButton(
                    text = "Save as Shared Virtual Camera Video Source",
                    enabled = uiState.sourceUri.isNotBlank(),
                    onClick = {
                        viewModel.selectMode(CameraSourceMode.LOCAL_VIDEO)
                        viewModel.saveConfiguration()
                        coroutineScope.launch {
                            adapterManager.saveSharedConfig(
                                SharedCameraSourceConfig(
                                    mode = CameraSourceMode.LOCAL_VIDEO,
                                    sourceUri = uiState.sourceUri,
                                    outputFormat = selectedFormat,
                                    targetWidth = 0,
                                    targetHeight = 0,
                                    enableAudio = uiState.enableAudio,
                                    autoReconnect = uiState.autoReconnect
                                )
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    testTag = "video_source_save_button"
                )
            }
        }
    }
}
