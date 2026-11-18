package com.vcameraspace.feature.camera

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.SurfaceTexture
import android.net.Uri
import android.view.Surface
import android.view.TextureView
import android.view.ViewGroup
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.vcameraspace.core.designsystem.components.PrimaryButton
import com.vcameraspace.core.media.EglVirtualSurfacePipeline
import com.vcameraspace.core.media.GlColorFilterMode
import com.vcameraspace.core.media.GlRenderTelemetry
import com.vcameraspace.virtualengine.api.CameraSourceMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun VirtualCameraPreviewCanvas(
    mode: CameraSourceMode,
    sourceUri: String,
    enableAudio: Boolean,
    autoReconnect: Boolean,
    orientationDegrees: Int,
    onTelemetryUpdate: (fps: Double, resolution: String) -> Unit,
    onPlaybackError: (String) -> Unit,
    modifier: Modifier = Modifier,
    useOpenGlPipeline: Boolean = true,
    mirrorHorizontal: Boolean = false,
    filterMode: GlColorFilterMode = GlColorFilterMode.STANDARD,
    onGlTelemetry: (GlRenderTelemetry) -> Unit = {},
    fillContainer: Boolean = false,
    onBitmapProviderReady: ((() -> Bitmap?) -> Unit) = {}
) {
    val containerModifier = if (fillContainer) {
        modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(if (fillContainer) 0.dp else 18.dp))
    } else {
        modifier
            .fillMaxWidth()
            .height(220.dp)
            .clip(RoundedCornerShape(18.dp))
    }
    Surface(
        modifier = containerModifier,
        shape = RoundedCornerShape(if (fillContainer) 0.dp else 18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        when (mode) {
            CameraSourceMode.REAL_DEVICE -> {
                RealCameraXPreview(
                    orientationDegrees = orientationDegrees,
                    mirrorHorizontal = mirrorHorizontal,
                    onTelemetryUpdate = onTelemetryUpdate,
                    onError = onPlaybackError,
                    onBitmapProviderReady = onBitmapProviderReady
                )
            }
            CameraSourceMode.LOCAL_VIDEO,
            CameraSourceMode.NETWORK_STREAM -> {
                if (sourceUri.isBlank()) {
                    EmptyPreviewPlaceholder(
                        label = if (mode == CameraSourceMode.LOCAL_VIDEO) {
                            "Choose a local video in Media Section to preview"
                        } else {
                            "Enter and validate a stream URL to preview"
                        }
                    )
                } else if (useOpenGlPipeline) {
                    GlMedia3VideoPreview(
                        uriString = sourceUri,
                        enableAudio = enableAudio,
                        autoReconnect = autoReconnect,
                        orientationDegrees = orientationDegrees,
                        mirrorHorizontal = mirrorHorizontal,
                        filterMode = filterMode,
                        onTelemetryUpdate = onTelemetryUpdate,
                        onGlTelemetry = onGlTelemetry,
                        onPlaybackError = onPlaybackError,
                        onBitmapProviderReady = onBitmapProviderReady
                    )
                } else {
                    Media3VideoPreview(
                        uriString = sourceUri,
                        enableAudio = enableAudio,
                        autoReconnect = autoReconnect,
                        orientationDegrees = orientationDegrees,
                        mirrorHorizontal = mirrorHorizontal,
                        onTelemetryUpdate = onTelemetryUpdate,
                        onPlaybackError = onPlaybackError
                    )
                }
            }
            CameraSourceMode.LOCAL_PICTURE -> {
                if (sourceUri.isBlank()) {
                    EmptyPreviewPlaceholder(label = "Choose a local picture in Media Section to preview")
                } else if (useOpenGlPipeline) {
                    GlPicturePreview(
                        uriString = sourceUri,
                        orientationDegrees = orientationDegrees,
                        mirrorHorizontal = mirrorHorizontal,
                        filterMode = filterMode,
                        onTelemetryUpdate = onTelemetryUpdate,
                        onGlTelemetry = onGlTelemetry,
                        onError = onPlaybackError,
                        onBitmapProviderReady = onBitmapProviderReady
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        AsyncImage(
                            model = Uri.parse(sourceUri),
                            contentDescription = "Virtual camera picture preview",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    rotationZ = orientationDegrees.toFloat()
                                    scaleX = if (mirrorHorizontal) -1f else 1f
                                }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GlMedia3VideoPreview(
    uriString: String,
    enableAudio: Boolean,
    autoReconnect: Boolean,
    orientationDegrees: Int,
    mirrorHorizontal: Boolean,
    filterMode: GlColorFilterMode,
    onTelemetryUpdate: (Double, String) -> Unit,
    onGlTelemetry: (GlRenderTelemetry) -> Unit,
    onPlaybackError: (String) -> Unit,
    onBitmapProviderReady: ((() -> Bitmap?) -> Unit) = {}
) {
    val context = LocalContext.current
    var glUnavailable by remember(uriString) { mutableStateOf(false) }

    if (glUnavailable) {
        Media3VideoPreview(
            uriString = uriString,
            enableAudio = enableAudio,
            autoReconnect = autoReconnect,
            orientationDegrees = orientationDegrees,
            mirrorHorizontal = mirrorHorizontal,
            onTelemetryUpdate = onTelemetryUpdate,
            onPlaybackError = onPlaybackError
        )
        return
    }

    val exoPlayer = remember(uriString) {
        ExoPlayer.Builder(context).build().apply {
            repeatMode = Player.REPEAT_MODE_ALL
            playWhenReady = true
        }
    }

    var glPipeline by remember { mutableStateOf<EglVirtualSurfacePipeline?>(null) }

    LaunchedEffect(uriString) {
        runCatching {
            exoPlayer.setMediaItem(MediaItem.fromUri(Uri.parse(uriString)))
            exoPlayer.prepare()
        }.onFailure {
            onPlaybackError(it.message ?: "Failed to initialize Media3 player.")
        }
    }

    LaunchedEffect(enableAudio) {
        exoPlayer.volume = if (enableAudio) 1f else 0f
    }

    LaunchedEffect(orientationDegrees, mirrorHorizontal, filterMode) {
        glPipeline?.updateTransform(orientationDegrees, mirrorHorizontal, filterMode)
    }

    DisposableEffect(exoPlayer, autoReconnect) {
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    glPipeline?.updateSourceDimensions(videoSize.width, videoSize.height)
                    onTelemetryUpdate(30.0, "${videoSize.width} x ${videoSize.height}")
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                onPlaybackError(error.message ?: "Media3 playback error (${error.errorCodeName})")
                if (autoReconnect) {
                    exoPlayer.prepare()
                    exoPlayer.playWhenReady = true
                }
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.setVideoSurface(null)
            exoPlayer.release()
            glPipeline?.release()
            glPipeline = null
        }
    }

    AndroidView(
        factory = { ctx ->
            TextureView(ctx).apply {
                onBitmapProviderReady {
                    if (isAvailable) bitmap else null
                }
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                        val pipeline = EglVirtualSurfacePipeline(
                            outputSurfaceTexture = st,
                            viewportWidth = width,
                            viewportHeight = height,
                            onInputSurfaceReady = { glInputSurface: Surface ->
                                post {
                                    runCatching {
                                        exoPlayer.setVideoSurface(glInputSurface)
                                    }
                                }
                            },
                            onTelemetry = { telemetry ->
                                post {
                                    onGlTelemetry(telemetry)
                                    if (!telemetry.isGlActive && telemetry.errorDetail != null) {
                                        glUnavailable = true
                                    } else if (telemetry.measuredFps > 0.0) {
                                        onTelemetryUpdate(telemetry.measuredFps, "")
                                    }
                                }
                            }
                        )
                        pipeline.updateTransform(orientationDegrees, mirrorHorizontal, filterMode)
                        glPipeline = pipeline
                        pipeline.start()
                    }

                    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {
                        glPipeline?.updateViewport(width, height)
                    }

                    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                        exoPlayer.setVideoSurface(null)
                        glPipeline?.release()
                        glPipeline = null
                        return true
                    }

                    override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
                }
            }
        },
        modifier = Modifier.fillMaxSize()
    )
}

@Composable
private fun GlPicturePreview(
    uriString: String,
    orientationDegrees: Int,
    mirrorHorizontal: Boolean,
    filterMode: GlColorFilterMode,
    onTelemetryUpdate: (Double, String) -> Unit,
    onGlTelemetry: (GlRenderTelemetry) -> Unit,
    onError: (String) -> Unit,
    onBitmapProviderReady: ((() -> Bitmap?) -> Unit) = {}
) {
    val context = LocalContext.current
    var glUnavailable by remember(uriString) { mutableStateOf(false) }
    var decodedBitmap by remember(uriString) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(uriString) {
        val loaded = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openInputStream(Uri.parse(uriString))?.use { stream ->
                    BitmapFactory.decodeStream(stream)
                }
            }.getOrNull()
        }
        if (loaded != null) {
            decodedBitmap = loaded
            onTelemetryUpdate(60.0, "${loaded.width} x ${loaded.height}")
        } else {
            glUnavailable = true
        }
    }

    if (glUnavailable) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            AsyncImage(
                model = Uri.parse(uriString),
                contentDescription = "Virtual camera picture preview",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        rotationZ = orientationDegrees.toFloat()
                        scaleX = if (mirrorHorizontal) -1f else 1f
                    }
            )
        }
        return
    }

    var glPipeline by remember { mutableStateOf<EglVirtualSurfacePipeline?>(null) }

    LaunchedEffect(decodedBitmap, glPipeline) {
        val bmp = decodedBitmap
        val pipeline = glPipeline
        if (bmp != null && pipeline != null) {
            pipeline.submitBitmapFrame(bmp)
        }
    }

    LaunchedEffect(orientationDegrees, mirrorHorizontal, filterMode) {
        glPipeline?.updateTransform(orientationDegrees, mirrorHorizontal, filterMode)
    }

    DisposableEffect(uriString) {
        onDispose {
            glPipeline?.release()
            glPipeline = null
        }
    }

    AndroidView(
        factory = { ctx ->
            TextureView(ctx).apply {
                onBitmapProviderReady {
                    if (isAvailable) bitmap else null
                }
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                        val pipeline = EglVirtualSurfacePipeline(
                            outputSurfaceTexture = st,
                            viewportWidth = width,
                            viewportHeight = height,
                            onInputSurfaceReady = {
                                decodedBitmap?.let { bmp -> glPipeline?.submitBitmapFrame(bmp) }
                            },
                            onTelemetry = { telemetry ->
                                post {
                                    onGlTelemetry(telemetry)
                                    if (!telemetry.isGlActive && telemetry.errorDetail != null) {
                                        glUnavailable = true
                                        onError(telemetry.errorDetail ?: "OpenGL ES fallback")
                                    }
                                }
                            }
                        )
                        pipeline.updateTransform(orientationDegrees, mirrorHorizontal, filterMode)
                        glPipeline = pipeline
                        pipeline.start()
                    }

                    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {
                        glPipeline?.updateViewport(width, height)
                    }

                    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                        glPipeline?.release()
                        glPipeline = null
                        return true
                    }

                    override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
                }
            }
        },
        modifier = Modifier.fillMaxSize()
    )
}

@Composable
private fun RealCameraXPreview(
    orientationDegrees: Int,
    mirrorHorizontal: Boolean,
    onTelemetryUpdate: (Double, String) -> Unit,
    onError: (String) -> Unit,
    onBitmapProviderReady: ((() -> Bitmap?) -> Unit) = {}
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
        if (!granted) {
            onError("Camera permission denied. Grant Camera permission to preview the real camera device.")
        }
    }

    if (!hasCameraPermission) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.CameraAlt,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "Camera permission required for Real Camera preview",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp)
            )
            PrimaryButton(
                text = "Allow Camera",
                onClick = { launcher.launch(Manifest.permission.CAMERA) }
            )
        }
        return
    }

    val previewView = remember {
        PreviewView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            onBitmapProviderReady { bitmap }
        }
    }

    DisposableEffect(lifecycleOwner) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        val executor = ContextCompat.getMainExecutor(context)
        providerFuture.addListener(
            {
                runCatching {
                    val cameraProvider = providerFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.surfaceProvider = previewView.surfaceProvider
                    }
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview
                    )
                    onTelemetryUpdate(30.0, "CameraX Live")
                }.onFailure { err ->
                    onError("Camera resource unavailable: ${err.message ?: "No camera lens"}")
                }
            },
            executor
        )
        onDispose {
            runCatching {
                if (providerFuture.isDone) {
                    providerFuture.get().unbindAll()
                }
            }
        }
    }

    AndroidView(
        factory = { previewView },
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                rotationZ = orientationDegrees.toFloat()
                scaleX = if (mirrorHorizontal) -1f else 1f
            }
    )
}

@OptIn(UnstableApi::class)
@Composable
private fun Media3VideoPreview(
    uriString: String,
    enableAudio: Boolean,
    autoReconnect: Boolean,
    orientationDegrees: Int,
    mirrorHorizontal: Boolean,
    onTelemetryUpdate: (Double, String) -> Unit,
    onPlaybackError: (String) -> Unit
) {
    val context = LocalContext.current
    val exoPlayer = remember(uriString) {
        ExoPlayer.Builder(context).build().apply {
            repeatMode = Player.REPEAT_MODE_ALL
            playWhenReady = true
        }
    }

    LaunchedEffect(uriString) {
        runCatching {
            exoPlayer.setMediaItem(MediaItem.fromUri(Uri.parse(uriString)))
            exoPlayer.prepare()
        }.onFailure {
            onPlaybackError(it.message ?: "Failed to initialize Media3 player.")
        }
    }

    LaunchedEffect(enableAudio) {
        exoPlayer.volume = if (enableAudio) 1f else 0f
    }

    DisposableEffect(exoPlayer, autoReconnect) {
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    onTelemetryUpdate(30.0, "${videoSize.width} x ${videoSize.height}")
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                onPlaybackError(error.message ?: "Media3 playback error (${error.errorCodeName})")
                if (autoReconnect) {
                    exoPlayer.prepare()
                    exoPlayer.playWhenReady = true
                }
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                useController = true
                player = exoPlayer
            }
        },
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                rotationZ = orientationDegrees.toFloat()
                scaleX = if (mirrorHorizontal) -1f else 1f
            }
    )
}

@Composable
private fun EmptyPreviewPlaceholder(label: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Default.Photo,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}
