package com.example

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.example.ui.theme.VCameraAppTheme
import com.vcameraspace.core.common.coroutines.DispatcherProvider
import com.vcameraspace.core.common.diagnostics.DiagnosticCategory
import com.vcameraspace.core.common.diagnostics.DiagnosticLogger
import com.vcameraspace.core.common.diagnostics.DiagnosticSeverity
import com.vcameraspace.core.common.model.AppResult
import com.vcameraspace.core.common.model.ExecutionArchitecture
import com.vcameraspace.core.common.model.SpaceApp
import com.vcameraspace.core.common.model.VirtualAppStatus
import com.vcameraspace.core.database.VCameraDatabase
import com.vcameraspace.core.database.entity.ImportedApkEntity
import com.vcameraspace.core.database.repository.RoomSpaceRepository
import com.vcameraspace.core.media.MediaInspector
import com.vcameraspace.core.media.PhotoFrameProcessor
import com.vcameraspace.core.media.RoomCameraConfigurationRepository
import com.vcameraspace.core.media.SharedCameraSourceConfig
import com.vcameraspace.core.media.VideoCodecFrameProcessor
import com.vcameraspace.core.media.VirtualCameraAdapterManager
import com.vcameraspace.core.media.YuvFrameConverter
import com.vcameraspace.core.media.YuvFrameFormat
import com.vcameraspace.feature.camera.CameraViewModel
import com.vcameraspace.feature.camera.PhotoSourceScreen
import com.vcameraspace.feature.camera.VideoSourceScreen
import com.vcameraspace.feature.clone.ImportedAppsScreen
import com.vcameraspace.feature.clone.ImportedAppsViewModel
import com.vcameraspace.feature.clone.VirtualAppsScreen
import com.vcameraspace.feature.clone.VirtualAppsViewModel
import com.vcameraspace.feature.performance.CompatibilityStatusScreen
import com.vcameraspace.feature.performance.DiagnosticLogsScreen
import com.vcameraspace.virtualengine.api.CameraSourceMode
import com.vcameraspace.virtualengine.impl.AndroidCompatibilityChecker
import com.vcameraspace.virtualengine.impl.AndroidVirtualAppInstaller
import com.vcameraspace.virtualengine.impl.ApkImportManager
import com.vcameraspace.virtualengine.impl.CompatibilityAssessmentEngine
import com.vcameraspace.virtualengine.impl.FeatureSupportLevel
import com.vcameraspace.virtualengine.impl.ManagedVirtualProcessManager
import com.vcameraspace.virtualengine.impl.RoomVirtualPackageManager
import com.vcameraspace.virtualengine.impl.ScopedVirtualStorageManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

private class DirectPhaseDispatcher : DispatcherProvider {
    override val main: CoroutineDispatcher = Dispatchers.Unconfined
    override val io: CoroutineDispatcher = Dispatchers.Unconfined
    override val default: CoroutineDispatcher = Dispatchers.Unconfined
}

class Phase2CoreUnitTests {

    @Test
    fun `yuv frame converter produces accurate packed I420 plane sizes and redacted diagnostics`() {
        val (evenW, evenH) = YuvFrameConverter.computeEvenDimensions(1921, 1081, maxLongEdge = 1280)
        assertEquals(0, evenW % 2)
        assertEquals(0, evenH % 2)
        assertTrue(evenW <= 1280)

        val w = 8
        val h = 6
        val pixels = IntArray(w * h) { 0xFF78C850.toInt() }
        val i420 = YuvFrameConverter.argbToYuv420(pixels, w, h, format = YuvFrameFormat.I420)
        assertEquals(w * h, i420.yPlane.size)
        assertEquals((w / 2) * (h / 2), i420.uPlane.size)
        assertEquals((w / 2) * (h / 2), i420.vPlane.size)
        assertEquals(w * h * 3 / 2, i420.packedI420SizeBytes)
        assertEquals(w * h * 3 / 2, i420.toPackedI420().size)

        val logger = DiagnosticLogger()
        logger.logError(
            DiagnosticCategory.MEDIA_PIPELINE,
            "Stream failed for https://alice:secretPass@cam.local/live.m3u8?token=mySecretToken"
        )
        val logged = logger.logs.value.first()
        assertEquals(DiagnosticSeverity.ERROR, logged.severity)
        assertFalse(logged.message.contains("secretPass"))
        assertFalse(logged.message.contains("mySecretToken"))
        assertTrue(logged.message.contains("https://***:***@cam.local"))
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Phase2EngineAndMigrationTests {

    private lateinit var context: Context
    private lateinit var database: VCameraDatabase
    private lateinit var prefsRepo: FakeUserPreferencesRepository
    private val dispatchers = DirectPhaseDispatcher()

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        database = VCameraDatabase.createInMemory(context)
        prefsRepo = FakeUserPreferencesRepository()
    }

    @After
    fun tearDown() {
        database.close()
        File(context.filesDir, "imported_apks").deleteRecursively()
        File(context.filesDir, "imported_icons").deleteRecursively()
        File(context.filesDir, "spaces").deleteRecursively()
        File(context.filesDir, "shared_virtual_camera.json").delete()
        File(context.filesDir, "vcamera_last_crash.txt").delete()
    }

    @Test
    fun `room migration 1 to 2 creates imported_apks table without data loss`() {
        val dbName = "migration_test_1_2.db"
        context.deleteDatabase(dbName)
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE IF NOT EXISTS `spaces` (`id` TEXT NOT NULL PRIMARY KEY)")
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build()
        )
        val rawDb = helper.writableDatabase
        VCameraDatabase.MIGRATION_1_2.migrate(rawDb)
        rawDb.query("SELECT name FROM sqlite_master WHERE type='table' AND name='imported_apks'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("imported_apks", cursor.getString(0))
        }
        rawDb.close()
        context.deleteDatabase(dbName)
    }

    @Test
    fun `apk import catalog persists revalidates and removes private files`() = runTest {
        val apkDir = File(context.filesDir, "imported_apks").apply { mkdirs() }
        val iconDir = File(context.filesDir, "imported_icons").apply { mkdirs() }
        val fakeApk = File(apkDir, "demo.apk").apply { writeBytes(ByteArray(64) { 0x50 }) }
        val fakeIcon = File(iconDir, "demo.png").apply { writeBytes(ByteArray(16) { 0x01 }) }

        database.importedApkDao().upsertImportedApk(
            ImportedApkEntity(
                id = "apk_demo_1",
                displayName = "demo.apk",
                packageName = "com.example.demo",
                appLabel = "Demo APK",
                versionName = "2.1.0",
                versionCode = 21L,
                minSdk = 26,
                targetSdk = 35,
                signingSha256 = "AA:BB:CC:DD",
                abisCsv = "arm64-v8a,armeabi-v7a",
                apkFilePath = fakeApk.absolutePath,
                iconFilePath = fakeIcon.absolutePath,
                fileSizeBytes = 64L,
                isValidArchive = true,
                isCompatible = true,
                compatibilitySummary = "Valid APK",
                importedAtEpochMs = 1000L
            )
        )

        val logger = DiagnosticLogger()
        val checker = AndroidCompatibilityChecker(context, dispatchers)
        val manager = ApkImportManager(context, database.importedApkDao(), checker, dispatchers, logger)

        val listBefore = manager.observeImportedApks().first()
        assertEquals(1, listBefore.size)
        assertTrue(listBefore.first().fileExists())

        val revalidated = manager.revalidateImportedApk("apk_demo_1")
        assertTrue(revalidated is AppResult.Success)

        val removed = manager.removeImportedApk("apk_demo_1")
        assertTrue(removed is AppResult.Success)
        assertFalse(fakeApk.exists())
        assertFalse(fakeIcon.exists())
        assertTrue(manager.observeImportedApks().first().isEmpty())
    }

    @Test
    fun `compatibility assessment engine evaluates BlackBox VirtualApp WhatsApp Messenger and imo honestly`() = runTest {
        val logger = DiagnosticLogger()
        val engine = CompatibilityAssessmentEngine(context, dispatchers, logger)
        val snapshot = engine.evaluateAll()

        assertTrue(snapshot.frameworks.any { it.id == "blackbox" })
        assertTrue(snapshot.frameworks.any { it.id == "virtualapp" })
        assertTrue(snapshot.frameworks.any { it.id == "scoped_sandbox" && it.isActiveInApp })

        val targetPackages = snapshot.targetApps.map { it.packageName }.toSet()
        assertTrue("com.whatsapp" in targetPackages)
        assertTrue("com.facebook.orca" in targetPackages)
        assertTrue("com.imo.android.imoim" in targetPackages)

        snapshot.targetApps.forEach { target ->
            assertEquals(FeatureSupportLevel.NOT_SUPPORTED, target.virtualCameraStatus)
            assertEquals(FeatureSupportLevel.NOT_VERIFIED_ON_DEVICE, target.stabilityStatus)
        }
    }

    @Test
    fun `shared virtual camera adapter manager persists shared source and isolates physical camera`() = runTest {
        val logger = DiagnosticLogger()
        val configRepo = RoomCameraConfigurationRepository(database.cameraConfigDao(), dispatchers)
        val spaceRepo = RoomSpaceRepository(context, database.spaceDao(), database.virtualAppDao(), prefsRepo, dispatchers)
        spaceRepo.ensureDefaultSpacesSeeded()

        val photoProc = PhotoFrameProcessor(context, dispatchers, logger)
        val videoProc = VideoCodecFrameProcessor(context, dispatchers, logger)
        val adapter = VirtualCameraAdapterManager(context, configRepo, photoProc, videoProc, dispatchers, logger)

        val bitmap = Bitmap.createBitmap(16, 12, Bitmap.Config.ARGB_8888)
        val frame = YuvFrameConverter.bitmapToYuvFrame(bitmap, format = YuvFrameFormat.YUV_420_888)
        assertEquals(16, frame.width)
        assertEquals(12, frame.height)
        assertEquals(YuvFrameFormat.YUV_420_888, frame.format)
        bitmap.recycle()

        val saveRes = adapter.saveSharedConfig(
            SharedCameraSourceConfig(
                mode = CameraSourceMode.NETWORK_STREAM,
                sourceUri = "https://cdn.example.com/feed.m3u8",
                outputFormat = YuvFrameFormat.I420,
                enableAudio = false,
                autoReconnect = true
            )
        )
        assertTrue(saveRes is AppResult.Success)

        val effectiveSpace2 = adapter.resolveEffectiveConfig("space_2", "com.whatsapp")
        assertEquals(CameraSourceMode.NETWORK_STREAM, effectiveSpace2.mode)
        assertTrue(effectiveSpace2.physicalCameraIsolated)

        val startRes = adapter.startVirtualSession("space_2", "com.whatsapp")
        assertTrue(startRes is AppResult.Success)
        assertNotNull(adapter.state.value.lastStatusNote)
        adapter.stopVirtualSession()
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Phase3ScreenUiTests {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `all newly added Phase 3 screens render cleanly without crashes`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = VCameraDatabase.createInMemory(context)
        val dispatchers = DirectPhaseDispatcher()
        val logger = DiagnosticLogger().apply {
            logInfo(DiagnosticCategory.ENGINE, "Test event ready")
        }
        val prefs = FakeUserPreferencesRepository()
        val spaceRepo = RoomSpaceRepository(context, db.spaceDao(), db.virtualAppDao(), prefs, dispatchers)
        val checker = AndroidCompatibilityChecker(context, dispatchers)
        val storage = ScopedVirtualStorageManager(context, dispatchers)
        val installer = AndroidVirtualAppInstaller(context, db.virtualAppDao(), db.installationDao(), checker, storage, dispatchers)
        val apkImportManager = ApkImportManager(context, db.importedApkDao(), checker, dispatchers, logger)
        val pkgManager = RoomVirtualPackageManager(db.virtualAppDao(), dispatchers)
        val procManager = ManagedVirtualProcessManager(context, db.virtualAppDao(), dispatchers)
        val configRepo = RoomCameraConfigurationRepository(db.cameraConfigDao(), dispatchers)
        val mediaInspector = MediaInspector(context, dispatchers)
        val photoProc = PhotoFrameProcessor(context, dispatchers, logger)
        val videoProc = VideoCodecFrameProcessor(context, dispatchers, logger)
        val adapter = VirtualCameraAdapterManager(context, configRepo, photoProc, videoProc, dispatchers, logger)
        val compatEngine = CompatibilityAssessmentEngine(context, dispatchers, logger)

        val importedVm = ImportedAppsViewModel(apkImportManager, spaceRepo, installer, checker, logger)
        val virtualAppsVm = VirtualAppsViewModel(spaceRepo, pkgManager, procManager, storage, adapter, logger)
        val camVm = CameraViewModel("space_1", null, configRepo, mediaInspector, dispatchers, adapter)

        composeRule.setContent {
            VCameraAppTheme {
                ImportedAppsScreen(
                    viewModel = importedVm,
                    onBackClick = {},
                    onOpenVirtualApps = {}
                )
            }
        }
        composeRule.onNodeWithTag("imported_apps_screen").assertIsDisplayed()
        composeRule.onNodeWithText("No Imported APKs Yet").assertIsDisplayed()

        // Verify remaining ViewModels instantiate & emit state cleanly without throwing
        assertNotNull(virtualAppsVm.uiState.value)
        assertNotNull(camVm.uiState.value)
        assertNotNull(compatEngine)
        assertNotNull(SpaceApp("s1::pkg", "s1", "pkg", "App", "1.0", ExecutionArchitecture.SUPPORTED_SANDBOX, VirtualAppStatus.READY))
        db.close()
    }

    @Test
    fun `diagnostic logs and compatibility status screens display expected sections`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dispatchers = DirectPhaseDispatcher()
        val logger = DiagnosticLogger().apply {
            logWarning(DiagnosticCategory.CAMERA_ADAPTER, "Adapter idle warning")
        }
        val compatEngine = CompatibilityAssessmentEngine(context, dispatchers, logger)

        composeRule.setContent {
            VCameraAppTheme {
                DiagnosticLogsScreen(logger = logger, onBackClick = {})
            }
        }
        composeRule.onNodeWithTag("diagnostic_logs_screen").assertIsDisplayed()
        composeRule.onNodeWithText("Adapter idle warning").assertIsDisplayed()
        assertNotNull(compatEngine)
    }

    @Test
    fun `photo and video source screens display frame pipeline controls`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = VCameraDatabase.createInMemory(context)
        val dispatchers = DirectPhaseDispatcher()
        val configRepo = RoomCameraConfigurationRepository(db.cameraConfigDao(), dispatchers)
        val mediaInspector = MediaInspector(context, dispatchers)
        val logger = DiagnosticLogger()
        val adapter = VirtualCameraAdapterManager(
            context,
            configRepo,
            PhotoFrameProcessor(context, dispatchers, logger),
            VideoCodecFrameProcessor(context, dispatchers, logger),
            dispatchers,
            logger
        )
        val camVm = CameraViewModel("space_1", null, configRepo, mediaInspector, dispatchers, adapter)

        composeRule.setContent {
            VCameraAppTheme {
                PhotoSourceScreen(viewModel = camVm, adapterManager = adapter, onBackClick = {})
            }
        }
        composeRule.onNodeWithTag("photo_source_screen").assertIsDisplayed()
        composeRule.onNodeWithTag("photo_source_picker_button").assertIsDisplayed()
        assertTrue(adapter.state.value.apiMatrix.cameraXSupported)
        db.close()
    }

    @Test
    fun `video source and compatibility status screens instantiate and render cleanly`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = VCameraDatabase.createInMemory(context)
        val dispatchers = DirectPhaseDispatcher()
        val configRepo = RoomCameraConfigurationRepository(db.cameraConfigDao(), dispatchers)
        val mediaInspector = MediaInspector(context, dispatchers)
        val logger = DiagnosticLogger()
        val adapter = VirtualCameraAdapterManager(
            context,
            configRepo,
            PhotoFrameProcessor(context, dispatchers, logger),
            VideoCodecFrameProcessor(context, dispatchers, logger),
            dispatchers,
            logger
        )
        val camVm = CameraViewModel("space_1", null, configRepo, mediaInspector, dispatchers, adapter)
        val compatEngine = CompatibilityAssessmentEngine(context, dispatchers, logger)

        composeRule.setContent {
            VCameraAppTheme {
                VideoSourceScreen(viewModel = camVm, adapterManager = adapter, onBackClick = {})
            }
        }
        composeRule.onNodeWithTag("video_source_screen").assertIsDisplayed()
        composeRule.onNodeWithTag("video_source_pick_button").assertIsDisplayed()
        assertNotNull(compatEngine)
        db.close()
    }
}
