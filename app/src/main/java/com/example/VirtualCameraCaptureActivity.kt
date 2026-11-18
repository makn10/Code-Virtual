package com.example

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.theme.VCameraAppTheme
import com.vcameraspace.core.common.model.ThemePreference
import com.vcameraspace.core.datastore.UserPreferencesSnapshot
import com.vcameraspace.feature.camera.CaptureIntentMode
import com.vcameraspace.feature.camera.VirtualCameraViewfinderScreen
import com.vcameraspace.feature.camera.VirtualViewfinderViewModel

/**
 * Exported capture Activity handling MediaStore.ACTION_IMAGE_CAPTURE and
 * MediaStore.ACTION_VIDEO_CAPTURE so any Android app delegating photo or clip capture
 * via system camera intents can pick VCamera Space – Live Assist and receive
 * a processed frame or clip from the configured Space media source.
 */
class VirtualCameraCaptureActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val appContainer = (application as VCameraApplication).appContainer
        val captureMode = when (intent?.action) {
            MediaStore.ACTION_VIDEO_CAPTURE -> CaptureIntentMode.VIDEO_CAPTURE
            else -> CaptureIntentMode.IMAGE_CAPTURE
        }
        val extraOutputUri = intent?.let {
            IntentCompat.getParcelableExtra(it, MediaStore.EXTRA_OUTPUT, Uri::class.java)
        }
        val authority = "${packageName}.fileprovider"

        setContent {
            val prefs by appContainer.userPreferencesRepository.userPreferences
                .collectAsStateWithLifecycle(initialValue = UserPreferencesSnapshot())
            var resolvedSpaceId by remember { mutableStateOf(prefs.activeSpaceId) }

            LaunchedEffect(prefs.activeSpaceId) {
                resolvedSpaceId = prefs.activeSpaceId
            }

            val useDarkTheme = when (prefs.themePreference) {
                ThemePreference.SYSTEM -> isSystemInDarkTheme()
                ThemePreference.LIGHT -> false
                ThemePreference.DARK -> true
            }

            val viewfinderViewModel: VirtualViewfinderViewModel = viewModel(
                key = "capture_${resolvedSpaceId}_${captureMode.name}",
                factory = VirtualViewfinderViewModel.factory(
                    spaceId = resolvedSpaceId,
                    packageName = null,
                    captureMode = captureMode,
                    configRepository = appContainer.cameraConfigRepository,
                    adapterManager = appContainer.virtualCameraAdapterManager,
                    captureExporter = appContainer.virtualCaptureExporter,
                    extraOutputUri = extraOutputUri,
                    contentUriResolver = { file ->
                        runCatching { FileProvider.getUriForFile(this, authority, file) }.getOrNull()
                    }
                )
            )

            VCameraAppTheme(darkTheme = useDarkTheme) {
                VirtualCameraViewfinderScreen(
                    viewModel = viewfinderViewModel,
                    onBackClick = {
                        setResult(Activity.RESULT_CANCELED)
                        finish()
                    },
                    onOpenSettings = null,
                    onPhotoCapturedForCaller = { photoResult, thumbnail ->
                        val resultIntent = Intent().apply {
                            if (extraOutputUri != null) {
                                putExtra(MediaStore.EXTRA_OUTPUT, extraOutputUri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                            } else {
                                putExtra("data", thumbnail)
                                photoResult.contentUri?.let { uri ->
                                    data = uri
                                    clipData = ClipData.newRawUri("vcamera_photo", uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                            }
                        }
                        setResult(Activity.RESULT_OK, resultIntent)
                        finish()
                    },
                    onVideoCapturedForCaller = { clipResult ->
                        val outUri = extraOutputUri ?: clipResult.contentUri
                        val resultIntent = Intent().apply {
                            if (outUri != null) {
                                data = outUri
                                clipData = ClipData.newRawUri("vcamera_clip", outUri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                            }
                        }
                        setResult(Activity.RESULT_OK, resultIntent)
                        finish()
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}
