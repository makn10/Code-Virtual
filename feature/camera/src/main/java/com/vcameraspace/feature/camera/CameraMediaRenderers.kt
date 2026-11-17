package com.vcameraspace.feature.camera

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.vcameraspace.core.designsystem.components.PrimaryButton
import com.vcameraspace.virtualengine.api.CameraSourceMode

@Composable
fun VirtualCameraPreviewCanvas(
    mode: CameraSourceMode,
    sourceUri: String,
    enableAudio: Boolean,
    autoReconnect: Boolean,
    orientationDegrees: Int,
    onTelemetryUpdate: (fps: Double, resolution: String) -> Unit,
    onPlaybackError: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(220.dp)
            .clip(RoundedCornerShape(18.dp)),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        when (mode) {
            CameraSourceMode.REAL_DEVICE -> {
                RealCameraXPreview(
                    orientationDegrees = orientationDegrees,
                    onTelemetryUpdate = onTelemetryUpdate,
                    onError = onPlaybackError
                )
            }
            CameraSourceMode.LOCAL_VIDEO,
            CameraSourceMode.NETWORK_STREAM -> {
                if (sourceUri.isBlank()) {
                    EmptyPreviewPlaceholder(
                        label = if (mode == CameraSourceMode.LOCAL_VIDEO) {
                            "Choose a local video to preview"
                        } else {
                            "Enter and validate a stream URL to preview"
                        }
                    )
                } else {
                    Media3VideoPreview(
                        uriString = sourceUri,
                        enableAudio = enableAudio,
                        autoReconnect = autoReconnect,
                        orientationDegrees = orientationDegrees,
                        onTelemetryUpdate = onTelemetryUpdate,
                        onPlaybackError = onPlaybackError
                    )
                }
            }
            CameraSourceMode.LOCAL_PICTURE -> {
                if (sourceUri.isBlank()) {
                    EmptyPreviewPlaceholder(label = "Choose a local picture to preview")
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
                                .rotate(orientationDegrees.toFloat())
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RealCameraXPreview(
    orientationDegrees: Int,
    onTelemetryUpdate: (Double, String) -> Unit,
    onError: (String) -> Unit
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
            .rotate(orientationDegrees.toFloat())
    )
}

@OptIn(UnstableApi::class)
@Composable
private fun Media3VideoPreview(
    uriString: String,
    enableAudio: Boolean,
    autoReconnect: Boolean,
    orientationDegrees: Int,
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
            .rotate(orientationDegrees.toFloat())
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
