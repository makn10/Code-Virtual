package com.example

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import com.example.ui.theme.VCameraAppTheme
import com.vcameraspace.core.common.coroutines.DispatcherProvider
import com.vcameraspace.core.common.diagnostics.DiagnosticLogger
import com.vcameraspace.core.common.model.AppResult
import com.vcameraspace.core.common.model.BuiltInVirtualApps
import com.vcameraspace.core.common.model.ExecutionArchitecture
import com.vcameraspace.core.common.model.SpaceApp
import com.vcameraspace.core.common.model.VirtualAppStatus
import com.vcameraspace.core.database.VCameraDatabase
import com.vcameraspace.core.database.repository.RoomSpaceRepository
import com.vcameraspace.core.designsystem.components.PreLaunchVirtualCameraDialog
import com.vcameraspace.core.media.GlColorFilterMode
import com.vcameraspace.core.media.PhotoFrameProcessor
import com.vcameraspace.core.media.RoomCameraConfigurationRepository
import com.vcameraspace.core.media.SharedCameraSourceConfig
import com.vcameraspace.core.media.VideoCodecFrameProcessor
import com.vcameraspace.core.media.VirtualCameraAdapterManager
import com.vcameraspace.core.media.VirtualCaptureExporter
import com.vcameraspace.feature.camera.CaptureIntentMode
import com.vcameraspace.feature.camera.VirtualCameraViewfinderContent
import com.vcameraspace.feature.camera.VirtualViewfinderUiState
import com.vcameraspace.feature.home.HomeViewModel
import com.vcameraspace.feature.clone.VirtualAppsViewModel
import com.vcameraspace.virtualengine.api.CameraSourceMode
import com.vcameraspace.virtualengine.impl.AndroidCompatibilityChecker
import com.vcameraspace.virtualengine.impl.AndroidVirtualAppInstaller
import com.vcameraspace.virtualengine.impl.ManagedVirtualProcessManager
import com.vcameraspace.virtualengine.impl.RoomVirtualPackageManager
import com.vcameraspace.virtualengine.impl.ScopedVirtualStorageManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

private class TestDirectDispatchers : DispatcherProvider {
    override val main: CoroutineDispatcher = Dispatchers.Unconfined
    override val io: CoroutineDispatcher = Dispatchers.Unconfined
    override val default: CoroutineDispatcher = Dispatchers.Unconfined
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class VirtualCameraLaunchAndCaptureTest {

    private lateinit var context: Context
    private lateinit var database: VCameraDatabase
    private val testDispatcher = StandardTestDispatcher()
    private val dispatchers = TestDirectDispatchers()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        context = ApplicationProvider.getApplicationContext()
        database = VCameraDatabase.createInMemory(context)
    }

    @After
    fun tearDown() {
        database.close()
        File(context.filesDir, "captures").deleteRecursively()
        File(context.filesDir, "spaces").deleteRecursively()
        File(context.filesDir, "shared_virtual_camera.json").delete()
        Dispatchers.resetMain()
    }

    @Test
    fun `built-in virtual camera app is discoverable, installable, and launchable inside Space`() = runTest {
        val logger = DiagnosticLogger()
        val checker = AndroidCompatibilityChecker(context, dispatchers)
        val storage = ScopedVirtualStorageManager(context, dispatchers)
        val installer = AndroidVirtualAppInstaller(
            context = context,
            virtualAppDao = database.virtualAppDao(),
            installationDao = database.installationDao(),
            compatibilityChecker = checker,
            storageManager = storage,
            dispatchers = dispatchers
        )
        val processManager = ManagedVirtualProcessManager(context, database.virtualAppDao(), dispatchers)

        val compatRes = checker.evaluatePackage(BuiltInVirtualApps.VIRTUAL_CAMERA_PACKAGE)
        assertTrue(compatRes is AppResult.Success)
        val compatReport = (compatRes as AppResult.Success).data
        assertTrue(compatReport.isLaunchSupported)
        assertEquals(ExecutionArchitecture.SUPPORTED_SANDBOX, compatReport.recommendedArchitecture)

        val launchableList = checker.queryInstalledLaunchableApps()
        assertTrue(launchableList.any { it.packageName == BuiltInVirtualApps.VIRTUAL_CAMERA_PACKAGE })

        val installRes = installer.installIntoSpace("space_1", BuiltInVirtualApps.VIRTUAL_CAMERA_PACKAGE)
        assertTrue(installRes is AppResult.Success)
        val installedApp = (installRes as AppResult.Success).data
        assertEquals(BuiltInVirtualApps.VIRTUAL_CAMERA_LABEL, installedApp.label)
        assertEquals(VirtualAppStatus.READY, installedApp.status)

        val startRes = processManager.startProcess("space_1", BuiltInVirtualApps.VIRTUAL_CAMERA_PACKAGE)
        assertTrue(startRes is AppResult.Success)
    }

    @Test
    fun `home and virtual apps viewmodels route built-in camera directly and prompt before external camera apps`() = kotlinx.coroutines.runBlocking {
        val prefs = FakeUserPreferencesRepository()
        val spaceRepo = RoomSpaceRepository(context, database.spaceDao(), database.virtualAppDao(), prefs, dispatchers)
        spaceRepo.ensureDefaultSpacesSeeded()

        val logger = DiagnosticLogger()
        val configRepo = RoomCameraConfigurationRepository(database.cameraConfigDao(), dispatchers)
        val adapter = VirtualCameraAdapterManager(
            context = context,
            configRepository = configRepo,
            photoProcessor = PhotoFrameProcessor(context, dispatchers, logger),
            videoCodecProcessor = VideoCodecFrameProcessor(context, dispatchers, logger),
            dispatchers = dispatchers,
            logger = logger
        )
        adapter.saveSharedConfig(
            SharedCameraSourceConfig(
                mode = CameraSourceMode.LOCAL_VIDEO,
                sourceUri = "content://media/external/video/media/99"
            )
        )

        val pkgManager = RoomVirtualPackageManager(database.virtualAppDao(), dispatchers)
        val procManager = ManagedVirtualProcessManager(context, database.virtualAppDao(), dispatchers)
        val storage = ScopedVirtualStorageManager(context, dispatchers)

        val homeVm = HomeViewModel(
            spaceRepository = spaceRepo,
            userPreferencesRepository = prefs,
            virtualProcessManager = procManager,
            virtualStorageManager = storage,
            cameraAdapterManager = adapter
        )
        val collectorJob = launch { homeVm.uiState.collect() }

        var openedVirtualCamSpace: String? = null
        val builtInApp = BuiltInVirtualApps.createVirtualCameraApp("space_1")
        homeVm.requestOpenApp(builtInApp) { spaceId, _ ->
            openedVirtualCamSpace = spaceId
        }
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals("space_1", openedVirtualCamSpace)
        assertNull(homeVm.uiState.value.pendingPreLaunchPrompt)

        val cameraApp = SpaceApp(
            id = "space_1::com.android.camera2",
            spaceId = "space_1",
            packageName = "com.android.camera2",
            label = "Camera",
            versionName = "1.0",
            architecture = ExecutionArchitecture.SUPPORTED_SANDBOX,
            status = VirtualAppStatus.READY
        )
        homeVm.requestOpenApp(cameraApp) { _, _ -> }
        kotlinx.coroutines.withTimeout(3_000L) {
            while (homeVm.uiState.value.pendingPreLaunchPrompt == null) {
                testDispatcher.scheduler.advanceUntilIdle()
                kotlinx.coroutines.delay(25L)
            }
        }
        assertNotNull(homeVm.uiState.value.pendingPreLaunchPrompt)
        assertEquals(CameraSourceMode.LOCAL_VIDEO, homeVm.uiState.value.pendingPreLaunchPrompt?.activeMode)

        homeVm.dismissPreLaunchPrompt()
        testDispatcher.scheduler.advanceUntilIdle()
        assertNull(homeVm.uiState.value.pendingPreLaunchPrompt)

        val virtualAppsVm = VirtualAppsViewModel(
            spaceRepository = spaceRepo,
            virtualPackageManager = pkgManager,
            processManager = procManager,
            storageManager = storage,
            cameraAdapterManager = adapter,
            logger = logger,
            cameraConfigRepository = configRepo
        )
        val vaJob = launch { virtualAppsVm.uiState.collect() }

        virtualAppsVm.requestLaunchApp(cameraApp) { _, _ -> }
        kotlinx.coroutines.withTimeout(3_000L) {
            while (virtualAppsVm.uiState.value.pendingPreLaunchPrompt == null) {
                testDispatcher.scheduler.advanceUntilIdle()
                kotlinx.coroutines.delay(25L)
            }
        }
        assertNotNull(virtualAppsVm.uiState.value.pendingPreLaunchPrompt)

        virtualAppsVm.dismissPreLaunchPrompt()
        testDispatcher.scheduler.advanceUntilIdle()
        assertNull(virtualAppsVm.uiState.value.pendingPreLaunchPrompt)

        collectorJob.cancel()
        vaJob.cancel()
    }

    @Test
    fun `virtual capture exporter saves snapshot JPEG, thumbnail, and resolves FileProvider URI`() = runTest {
        val logger = DiagnosticLogger()
        val exporter = VirtualCaptureExporter(context, dispatchers, logger)

        val bmpRes = exporter.captureSnapshotBitmap(
            mode = CameraSourceMode.REAL_DEVICE,
            sourceUri = null,
            liveViewBitmap = null,
            orientationDegrees = 90,
            mirrorHorizontal = true,
            targetWidth = 640,
            targetHeight = 480
        )
        assertTrue(bmpRes is AppResult.Success)
        val capturedBitmap = (bmpRes as AppResult.Success).data
        // Rotated 90 deg swaps 640x480 -> 480x640
        assertEquals(480, capturedBitmap.width)
        assertEquals(640, capturedBitmap.height)

        val thumb = exporter.createCaptureThumbnail(capturedBitmap, maxEdge = 160)
        assertTrue(maxOf(thumb.width, thumb.height) <= 160)

        val extraTargetFile = File(context.filesDir, "captures/photos/caller_target.jpg").apply {
            parentFile?.mkdirs()
            createNewFile()
        }
        val callerUri = Uri.fromFile(extraTargetFile)
        val authority = "${context.packageName}.fileprovider"

        val writeRes = exporter.writeSnapshotToTarget(
            bitmap = capturedBitmap,
            destinationUri = callerUri,
            contentUriResolver = { file -> FileProvider.getUriForFile(context, authority, file) }
        )
        assertTrue(writeRes is AppResult.Success)
        val photoResult = (writeRes as AppResult.Success).data
        assertTrue(photoResult.file.exists())
        assertTrue(photoResult.sizeBytes > 0L)
        assertTrue(extraTargetFile.length() > 0L)
        val decoded = BitmapFactory.decodeFile(photoResult.file.absolutePath)
        assertNotNull(decoded)
        decoded.recycle()

        // Without destinationUri, contentUriResolver builds FileProvider URI
        val resolvedRes = exporter.writeSnapshotToTarget(
            bitmap = capturedBitmap,
            destinationUri = null,
            contentUriResolver = { file -> FileProvider.getUriForFile(context, authority, file) }
        )
        assertTrue(resolvedRes is AppResult.Success)
        val resolvedUri = (resolvedRes as AppResult.Success).data.contentUri
        assertNotNull(resolvedUri)
        assertEquals("content", resolvedUri?.scheme)

        capturedBitmap.recycle()
        thumb.recycle()

        // Exporting LOCAL_VIDEO clip without sourceUri fails cleanly with actionable error
        val missingVideoRes = exporter.exportVirtualClip(
            mode = CameraSourceMode.LOCAL_VIDEO,
            sourceUri = ""
        )
        assertTrue(missingVideoRes is AppResult.Failure)
    }

    @Test
    fun `manifest registers exported capture intents for photo and video capture`() {
        val pm = context.packageManager
        val imageIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).setPackage(context.packageName)
        val imageResolved = pm.queryIntentActivities(imageIntent, 0)
        assertTrue(
            "Expected VirtualCameraCaptureActivity for ACTION_IMAGE_CAPTURE",
            imageResolved.any { it.activityInfo.name == VirtualCameraCaptureActivity::class.java.name && it.activityInfo.exported }
        )

        val videoIntent = Intent(MediaStore.ACTION_VIDEO_CAPTURE).setPackage(context.packageName)
        val videoResolved = pm.queryIntentActivities(videoIntent, 0)
        assertTrue(
            "Expected VirtualCameraCaptureActivity for ACTION_VIDEO_CAPTURE",
            videoResolved.any { it.activityInfo.name == VirtualCameraCaptureActivity::class.java.name && it.activityInfo.exported }
        )
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class VirtualCameraViewfinderUiTests {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `viewfinder interactive HUD displays controls and fires photo, clip, and transform actions`() {
        var rotated = false
        var mirrored = false
        var filtered = false
        var photoCaptured = false
        var clipCaptured = false

        composeRule.setContent {
            VCameraAppTheme {
                VirtualCameraViewfinderContent(
                    uiState = VirtualViewfinderUiState(
                        mode = CameraSourceMode.LOCAL_VIDEO,
                        sourceUri = "content://demo/video.mp4",
                        filterMode = GlColorFilterMode.STANDARD
                    ),
                    snackbarHostState = SnackbarHostState(),
                    onBackClick = {},
                    onOpenSettings = {},
                    onRotate = { rotated = true },
                    onToggleMirror = { mirrored = true },
                    onCycleFilter = { filtered = true },
                    onTelemetryUpdate = { _, _ -> },
                    onGlTelemetry = {},
                    onPlaybackError = {},
                    onBitmapProviderReady = {},
                    onCapturePhoto = { photoCaptured = true },
                    onCaptureClip = { clipCaptured = true }
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("virtual_camera_viewfinder_screen").assertIsDisplayed()
        composeRule.onNodeWithTag("viewfinder_rotate_button").performClick()
        composeRule.onNodeWithTag("viewfinder_mirror_button").performClick()
        composeRule.onNodeWithTag("viewfinder_filter_button").performClick()
        composeRule.onNodeWithTag("viewfinder_capture_photo_button").performClick()
        composeRule.onNodeWithTag("viewfinder_capture_clip_button").performClick()

        assertTrue(rotated)
        assertTrue(mirrored)
        assertTrue(filtered)
        assertTrue(photoCaptured)
        assertTrue(clipCaptured)
    }

    @Test
    fun `pre-launch virtual camera dialog triggers all three route actions cleanly`() {
        var openVirtualCam = false
        var configureSource = false
        var openExternal = false

        composeRule.setContent {
            VCameraAppTheme {
                PreLaunchVirtualCameraDialog(
                    appName = "Camera",
                    activeSourceLabel = "Local Video",
                    onOpenBuiltInVirtualCamera = { openVirtualCam = true },
                    onConfigureSource = { configureSource = true },
                    onOpenExternalAppAnyway = { openExternal = true },
                    onDismiss = {}
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("prelaunch_virtual_camera_dialog").assertIsDisplayed()
        composeRule.onNodeWithText("Active Media Section Source: Local Video").assertIsDisplayed()

        composeRule.onNodeWithTag("prelaunch_open_virtual_camera").performClick()
        composeRule.onNodeWithTag("prelaunch_configure_source").performClick()
        composeRule.onNodeWithTag("prelaunch_open_external").performClick()

        assertTrue(openVirtualCam)
        assertTrue(configureSource)
        assertTrue(openExternal)
    }
}
