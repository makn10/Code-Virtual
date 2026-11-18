package com.example.navigation

import android.content.Context
import android.content.Intent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.example.AppContainer
import com.example.BuildConfig
import com.example.R
import com.vcameraspace.core.designsystem.components.ConfirmationDialog
import com.vcameraspace.core.navigation.VCameraDestination
import com.vcameraspace.feature.about.AboutScreen
import com.vcameraspace.feature.advance.AdvanceFeatureScreen
import com.vcameraspace.feature.advance.AdvanceFeatureViewModel
import com.vcameraspace.feature.advance.LocationManagerScreen
import com.vcameraspace.feature.advance.LocationManagerViewModel
import com.vcameraspace.feature.advance.MapPickerScreen
import com.vcameraspace.feature.advance.MapPickerViewModel
import com.vcameraspace.feature.backup.BackupRestoreScreen
import com.vcameraspace.feature.backup.BackupRestoreViewModel
import com.vcameraspace.feature.camera.CameraLabScreen
import com.vcameraspace.feature.camera.CameraPreviewScreen
import com.vcameraspace.feature.camera.CameraViewModel
import com.vcameraspace.feature.camera.CaptureIntentMode
import com.vcameraspace.feature.camera.PhotoSourceScreen
import com.vcameraspace.feature.camera.VideoSourceScreen
import com.vcameraspace.feature.camera.VirtualCameraSettingScreen
import com.vcameraspace.feature.camera.VirtualCameraViewfinderScreen
import com.vcameraspace.feature.camera.VirtualViewfinderViewModel
import com.vcameraspace.feature.clone.ApkImportScreen
import com.vcameraspace.feature.clone.ApkImportViewModel
import com.vcameraspace.feature.clone.AppInformationScreen
import com.vcameraspace.feature.clone.AppInformationViewModel
import com.vcameraspace.feature.clone.CloneAppScreen
import com.vcameraspace.feature.clone.CloneAppViewModel
import com.vcameraspace.feature.clone.ImportedAppsScreen
import com.vcameraspace.feature.clone.ImportedAppsViewModel
import com.vcameraspace.feature.clone.InstallationProgressScreen
import com.vcameraspace.feature.clone.InstallationProgressViewModel
import com.vcameraspace.feature.clone.VirtualAppsScreen
import com.vcameraspace.feature.clone.VirtualAppsViewModel
import com.vcameraspace.feature.googleframework.GoogleFrameworkScreen
import com.vcameraspace.feature.home.HomeOverflowAction
import com.vcameraspace.feature.home.HomeQuickDestination
import com.vcameraspace.feature.home.HomeScreen
import com.vcameraspace.feature.home.HomeViewModel
import com.vcameraspace.feature.onboarding.OnboardingScreen
import com.vcameraspace.feature.onboarding.OnboardingViewModel
import com.vcameraspace.feature.performance.CompatibilityStatusScreen
import com.vcameraspace.feature.performance.DiagnosticLogsScreen
import com.vcameraspace.feature.performance.PerformanceMonitorScreen
import com.vcameraspace.feature.premium.PremiumScreen
import com.vcameraspace.feature.privacy.PrivacyDashboardScreen
import com.vcameraspace.feature.settings.GeneralSettingsScreen
import com.vcameraspace.feature.spaces.SpaceManagerScreen
import com.vcameraspace.feature.spaces.SpaceManagerViewModel
import com.vcameraspace.feature.splash.SplashScreen
import com.vcameraspace.feature.splash.SplashViewModel

@Composable
fun VCameraNavHost(
    appContainer: AppContainer,
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController()
) {
    val context = LocalContext.current
    var infoDialogData by remember { mutableStateOf<Pair<String, String>?>(null) }

    NavHost(
        navController = navController,
        startDestination = VCameraDestination.Splash,
        modifier = modifier
    ) {
        composable<VCameraDestination.Splash> {
            val splashViewModel: SplashViewModel = viewModel(
                factory = SplashViewModel.factory(appContainer.userPreferencesRepository)
            )
            SplashScreen(
                viewModel = splashViewModel,
                onNavigateToOnboarding = {
                    navController.navigate(VCameraDestination.Onboarding) {
                        popUpTo<VCameraDestination.Splash> { inclusive = true }
                    }
                },
                onNavigateToHome = {
                    navController.navigate(VCameraDestination.Home) {
                        popUpTo<VCameraDestination.Splash> { inclusive = true }
                    }
                }
            )
        }

        composable<VCameraDestination.Onboarding> {
            val onboardingViewModel: OnboardingViewModel = viewModel(
                factory = OnboardingViewModel.factory(appContainer.userPreferencesRepository)
            )
            OnboardingScreen(
                viewModel = onboardingViewModel,
                spacesIllustration = painterResource(id = R.drawable.img_onboarding_spaces),
                cameraIllustration = painterResource(id = R.drawable.img_onboarding_camera),
                privacyIllustration = painterResource(id = R.drawable.img_onboarding_privacy),
                onFinished = {
                    navController.navigate(VCameraDestination.Home) {
                        popUpTo<VCameraDestination.Onboarding> { inclusive = true }
                    }
                }
            )
        }

        composable<VCameraDestination.Home> {
            val homeViewModel: HomeViewModel = viewModel(
                factory = HomeViewModel.factory(
                    spaceRepository = appContainer.spaceRepository,
                    userPreferencesRepository = appContainer.userPreferencesRepository,
                    virtualProcessManager = appContainer.processManager,
                    virtualStorageManager = appContainer.storageManager,
                    cameraAdapterManager = appContainer.virtualCameraAdapterManager
                )
            )
            HomeScreen(
                viewModel = homeViewModel,
                emptyIllustration = painterResource(id = R.drawable.img_empty_space),
                onOpenCloneApp = { spaceId ->
                    navController.navigate(VCameraDestination.CloneApp(spaceId))
                },
                onOpenSpaceManager = {
                    navController.navigate(VCameraDestination.SpaceManager)
                },
                onNavigateMenuAction = { action, spaceId ->
                    when (action) {
                        HomeOverflowAction.PREMIUM -> navController.navigate(VCameraDestination.Premium)
                        HomeOverflowAction.ADVANCE_FEATURE -> navController.navigate(VCameraDestination.AdvanceFeature)
                        HomeOverflowAction.VIRTUAL_CAMERA_SETTING -> navController.navigate(
                            VCameraDestination.VirtualCameraSetting(spaceId = spaceId)
                        )
                        HomeOverflowAction.GOOGLE_FRAMEWORK -> navController.navigate(VCameraDestination.GoogleFramework)
                        HomeOverflowAction.ABOUT -> navController.navigate(VCameraDestination.About)
                        HomeOverflowAction.SHARE -> shareAppDetails(context)
                        HomeOverflowAction.RATE_US -> {
                            infoDialogData = "Rate VCamera Space" to
                                "Store ratings become available when VCamera Space is published on a supported distribution store."
                        }
                        HomeOverflowAction.GITHUB -> {
                            infoDialogData = "VCamera Space Source & Docs" to
                                "Architecture details and platform boundaries are documented in About VCamera Space."
                        }
                        HomeOverflowAction.CONTACT_US -> {
                            infoDialogData = "Contact & Support" to
                                "VCamera Space operates locally with zero background telemetry. Review Privacy Dashboard or About for support information."
                        }
                        HomeOverflowAction.KILL_ALL_APPS,
                        HomeOverflowAction.SWITCH_TO_NORMAL -> Unit
                    }
                },
                onOpenAppInfo = { spaceId, packageName ->
                    navController.navigate(VCameraDestination.AppInformation(spaceId, packageName))
                },
                onOpenAppCameraSettings = { spaceId, packageName ->
                    navController.navigate(VCameraDestination.VirtualCameraSetting(spaceId, packageName))
                },
                onOpenQuickDestination = { destination, spaceId ->
                    when (destination) {
                        HomeQuickDestination.VIRTUAL_CAMERA_VIEWFINDER -> navController.navigate(
                            VCameraDestination.VirtualCameraViewfinder(spaceId = spaceId)
                        )
                        HomeQuickDestination.IMPORTED_APKS -> navController.navigate(VCameraDestination.ImportedApps)
                        HomeQuickDestination.VIRTUAL_APPS -> navController.navigate(VCameraDestination.VirtualApps)
                        HomeQuickDestination.VIRTUAL_CAMERA -> navController.navigate(
                            VCameraDestination.VirtualCameraSetting(spaceId = spaceId)
                        )
                        HomeQuickDestination.PHOTO_SOURCE -> navController.navigate(
                            VCameraDestination.PhotoSourceSelect(spaceId = spaceId)
                        )
                        HomeQuickDestination.VIDEO_SOURCE -> navController.navigate(
                            VCameraDestination.VideoSourceSelect(spaceId = spaceId)
                        )
                        HomeQuickDestination.COMPATIBILITY -> navController.navigate(VCameraDestination.CompatibilityStatus)
                        HomeQuickDestination.RUNTIME_STATUS -> navController.navigate(VCameraDestination.PerformanceMonitor)
                        HomeQuickDestination.DIAGNOSTIC_LOGS -> navController.navigate(VCameraDestination.DiagnosticLogs)
                        HomeQuickDestination.SETTINGS -> navController.navigate(VCameraDestination.GeneralSettings)
                    }
                },
                onOpenVirtualCameraViewfinder = { spaceId, pkg ->
                    navController.navigate(
                        VCameraDestination.VirtualCameraViewfinder(
                            spaceId = spaceId,
                            packageName = pkg
                        )
                    )
                }
            )
        }

        composable<VCameraDestination.SpaceManager> {
            val spaceManagerViewModel: SpaceManagerViewModel = viewModel(
                factory = SpaceManagerViewModel.factory(
                    spaceRepository = appContainer.spaceRepository,
                    userPreferencesRepository = appContainer.userPreferencesRepository
                )
            )
            SpaceManagerScreen(
                viewModel = spaceManagerViewModel,
                onBackClick = { navController.popBackStack() },
                onSpaceActivated = { navController.popBackStack() }
            )
        }

        composable<VCameraDestination.CloneApp> { backStackEntry ->
            val route = backStackEntry.toRoute<VCameraDestination.CloneApp>()
            val cloneViewModel: CloneAppViewModel = viewModel(
                factory = CloneAppViewModel.factory(
                    spaceId = route.spaceId,
                    compatibilityChecker = appContainer.compatibilityChecker,
                    virtualPackageManager = appContainer.virtualPackageManager,
                    dispatchers = appContainer.dispatchers
                )
            )
            CloneAppScreen(
                viewModel = cloneViewModel,
                onBackClick = { navController.popBackStack() },
                onOpenApkImport = {
                    navController.navigate(VCameraDestination.ApkImport(route.spaceId))
                },
                onSelectAppForInstall = { pkg ->
                    navController.navigate(
                        VCameraDestination.InstallationProgress(
                            spaceId = route.spaceId,
                            packageName = pkg
                        )
                    )
                }
            )
        }

        composable<VCameraDestination.ApkImport> { backStackEntry ->
            val route = backStackEntry.toRoute<VCameraDestination.ApkImport>()
            val apkImportViewModel: ApkImportViewModel = viewModel(
                factory = ApkImportViewModel.factory(
                    spaceId = route.spaceId,
                    compatibilityChecker = appContainer.compatibilityChecker,
                    appInstaller = appContainer.appInstaller,
                    dispatchers = appContainer.dispatchers,
                    apkImportManager = appContainer.apkImportManager
                )
            )
            ApkImportScreen(
                viewModel = apkImportViewModel,
                onBackClick = { navController.popBackStack() },
                onDoneNavigateHome = {
                    navController.popBackStack(VCameraDestination.Home, inclusive = false)
                },
                onOpenImportedApps = {
                    navController.navigate(VCameraDestination.ImportedApps)
                }
            )
        }

        composable<VCameraDestination.ImportedApps> {
            val importedAppsViewModel: ImportedAppsViewModel = viewModel(
                factory = ImportedAppsViewModel.factory(
                    apkImportManager = appContainer.apkImportManager,
                    spaceRepository = appContainer.spaceRepository,
                    appInstaller = appContainer.appInstaller,
                    compatibilityChecker = appContainer.compatibilityChecker,
                    logger = appContainer.diagnosticLogger
                )
            )
            ImportedAppsScreen(
                viewModel = importedAppsViewModel,
                onBackClick = { navController.popBackStack() },
                onOpenVirtualApps = {
                    navController.navigate(VCameraDestination.VirtualApps)
                }
            )
        }

        composable<VCameraDestination.VirtualApps> {
            val virtualAppsViewModel: VirtualAppsViewModel = viewModel(
                factory = VirtualAppsViewModel.factory(
                    spaceRepository = appContainer.spaceRepository,
                    virtualPackageManager = appContainer.virtualPackageManager,
                    processManager = appContainer.processManager,
                    storageManager = appContainer.storageManager,
                    cameraAdapterManager = appContainer.virtualCameraAdapterManager,
                    logger = appContainer.diagnosticLogger
                )
            )
            VirtualAppsScreen(
                viewModel = virtualAppsViewModel,
                onBackClick = { navController.popBackStack() },
                onOpenCloneApp = { spaceId ->
                    navController.navigate(VCameraDestination.CloneApp(spaceId))
                },
                onOpenAppInfo = { spaceId, pkg ->
                    navController.navigate(VCameraDestination.AppInformation(spaceId, pkg))
                },
                onOpenAppCameraSettings = { spaceId, pkg ->
                    navController.navigate(VCameraDestination.VirtualCameraSetting(spaceId, pkg))
                },
                onOpenBuiltInVirtualCamera = { spaceId, pkg ->
                    navController.navigate(VCameraDestination.VirtualCameraViewfinder(spaceId, pkg))
                }
            )
        }

        composable<VCameraDestination.InstallationProgress> { backStackEntry ->
            val route = backStackEntry.toRoute<VCameraDestination.InstallationProgress>()
            val installViewModel: InstallationProgressViewModel = viewModel(
                factory = InstallationProgressViewModel.factory(
                    spaceId = route.spaceId,
                    packageName = route.packageName,
                    appInstaller = appContainer.appInstaller
                )
            )
            InstallationProgressScreen(
                viewModel = installViewModel,
                onBackClick = { navController.popBackStack() },
                onDoneHome = {
                    navController.popBackStack(VCameraDestination.Home, inclusive = false)
                }
            )
        }

        composable<VCameraDestination.AppInformation> { backStackEntry ->
            val route = backStackEntry.toRoute<VCameraDestination.AppInformation>()
            val appInfoViewModel: AppInformationViewModel = viewModel(
                factory = AppInformationViewModel.factory(
                    spaceId = route.spaceId,
                    packageName = route.packageName,
                    virtualPackageManager = appContainer.virtualPackageManager,
                    virtualProcessManager = appContainer.processManager,
                    storageManager = appContainer.storageManager,
                    compatibilityChecker = appContainer.compatibilityChecker
                )
            )
            AppInformationScreen(
                viewModel = appInfoViewModel,
                onBackClick = { navController.popBackStack() },
                onOpenCameraSettings = {
                    navController.navigate(
                        VCameraDestination.VirtualCameraSetting(
                            spaceId = route.spaceId,
                            packageName = route.packageName
                        )
                    )
                }
            )
        }

        composable<VCameraDestination.VirtualCameraSetting> { backStackEntry ->
            val route = backStackEntry.toRoute<VCameraDestination.VirtualCameraSetting>()
            val cameraViewModel: CameraViewModel = viewModel(
                factory = CameraViewModel.factory(
                    spaceId = route.spaceId,
                    packageName = route.packageName,
                    configRepository = appContainer.cameraConfigRepository,
                    mediaInspector = appContainer.mediaInspector,
                    dispatchers = appContainer.dispatchers,
                    adapterManager = appContainer.virtualCameraAdapterManager
                )
            )
            VirtualCameraSettingScreen(
                viewModel = cameraViewModel,
                onBackClick = { navController.popBackStack() },
                onOpenPreview = {
                    navController.navigate(
                        VCameraDestination.CameraPreview(
                            spaceId = route.spaceId,
                            packageName = route.packageName
                        )
                    )
                },
                onOpenCameraLab = {
                    navController.navigate(
                        VCameraDestination.CameraLab(
                            spaceId = route.spaceId,
                            packageName = route.packageName
                        )
                    )
                },
                onOpenPhotoSourceSelect = {
                    navController.navigate(
                        VCameraDestination.PhotoSourceSelect(
                            spaceId = route.spaceId,
                            packageName = route.packageName
                        )
                    )
                },
                onOpenVideoSourceSelect = {
                    navController.navigate(
                        VCameraDestination.VideoSourceSelect(
                            spaceId = route.spaceId,
                            packageName = route.packageName
                        )
                    )
                },
                onOpenViewfinder = {
                    navController.navigate(
                        VCameraDestination.VirtualCameraViewfinder(
                            spaceId = route.spaceId,
                            packageName = route.packageName
                        )
                    )
                }
            )
        }

        composable<VCameraDestination.PhotoSourceSelect> { backStackEntry ->
            val route = backStackEntry.toRoute<VCameraDestination.PhotoSourceSelect>()
            val cameraViewModel: CameraViewModel = viewModel(
                factory = CameraViewModel.factory(
                    spaceId = route.spaceId,
                    packageName = route.packageName,
                    configRepository = appContainer.cameraConfigRepository,
                    mediaInspector = appContainer.mediaInspector,
                    dispatchers = appContainer.dispatchers,
                    adapterManager = appContainer.virtualCameraAdapterManager
                )
            )
            PhotoSourceScreen(
                viewModel = cameraViewModel,
                adapterManager = appContainer.virtualCameraAdapterManager,
                onBackClick = { navController.popBackStack() }
            )
        }

        composable<VCameraDestination.VideoSourceSelect> { backStackEntry ->
            val route = backStackEntry.toRoute<VCameraDestination.VideoSourceSelect>()
            val cameraViewModel: CameraViewModel = viewModel(
                factory = CameraViewModel.factory(
                    spaceId = route.spaceId,
                    packageName = route.packageName,
                    configRepository = appContainer.cameraConfigRepository,
                    mediaInspector = appContainer.mediaInspector,
                    dispatchers = appContainer.dispatchers,
                    adapterManager = appContainer.virtualCameraAdapterManager
                )
            )
            VideoSourceScreen(
                viewModel = cameraViewModel,
                adapterManager = appContainer.virtualCameraAdapterManager,
                onBackClick = { navController.popBackStack() }
            )
        }

        composable<VCameraDestination.CameraPreview> { backStackEntry ->
            val route = backStackEntry.toRoute<VCameraDestination.CameraPreview>()
            val cameraViewModel: CameraViewModel = viewModel(
                factory = CameraViewModel.factory(
                    spaceId = route.spaceId,
                    packageName = route.packageName,
                    configRepository = appContainer.cameraConfigRepository,
                    mediaInspector = appContainer.mediaInspector,
                    dispatchers = appContainer.dispatchers,
                    adapterManager = appContainer.virtualCameraAdapterManager
                )
            )
            CameraPreviewScreen(
                viewModel = cameraViewModel,
                onBackClick = { navController.popBackStack() },
                onOpenCameraLab = {
                    navController.navigate(
                        VCameraDestination.CameraLab(
                            spaceId = route.spaceId,
                            packageName = route.packageName
                        )
                    )
                }
            )
        }

        composable<VCameraDestination.VirtualCameraViewfinder> { backStackEntry ->
            val route = backStackEntry.toRoute<VCameraDestination.VirtualCameraViewfinder>()
            val authority = "${context.packageName}.fileprovider"
            val viewfinderViewModel: VirtualViewfinderViewModel = viewModel(
                factory = VirtualViewfinderViewModel.factory(
                    spaceId = route.spaceId,
                    packageName = route.packageName,
                    captureMode = CaptureIntentMode.INTERACTIVE,
                    configRepository = appContainer.cameraConfigRepository,
                    adapterManager = appContainer.virtualCameraAdapterManager,
                    captureExporter = appContainer.virtualCaptureExporter,
                    contentUriResolver = { file ->
                        runCatching { FileProvider.getUriForFile(context, authority, file) }.getOrNull()
                    }
                )
            )
            VirtualCameraViewfinderScreen(
                viewModel = viewfinderViewModel,
                onBackClick = { navController.popBackStack() },
                onOpenSettings = {
                    navController.navigate(
                        VCameraDestination.VirtualCameraSetting(
                            spaceId = route.spaceId,
                            packageName = route.packageName
                        )
                    )
                }
            )
        }

        composable<VCameraDestination.CameraLab> { backStackEntry ->
            val route = backStackEntry.toRoute<VCameraDestination.CameraLab>()
            val cameraViewModel: CameraViewModel = viewModel(
                factory = CameraViewModel.factory(
                    spaceId = route.spaceId,
                    packageName = route.packageName,
                    configRepository = appContainer.cameraConfigRepository,
                    mediaInspector = appContainer.mediaInspector,
                    dispatchers = appContainer.dispatchers,
                    adapterManager = appContainer.virtualCameraAdapterManager
                )
            )
            CameraLabScreen(
                viewModel = cameraViewModel,
                onBackClick = { navController.popBackStack() }
            )
        }

        composable<VCameraDestination.AdvanceFeature> {
            val advanceViewModel: AdvanceFeatureViewModel = viewModel(
                factory = AdvanceFeatureViewModel.factory(
                    userPreferencesRepository = appContainer.userPreferencesRepository,
                    locationProvider = appContainer.virtualLocationProvider
                )
            )
            AdvanceFeatureScreen(
                viewModel = advanceViewModel,
                permissionManager = appContainer.permissionManager,
                onBackClick = { navController.popBackStack() },
                onOpenLocationManager = { spaceId ->
                    navController.navigate(VCameraDestination.LocationManager(spaceId))
                },
                onOpenPrivacyDashboard = {
                    navController.navigate(VCameraDestination.PrivacyDashboard)
                },
                onOpenPerformanceMonitor = {
                    navController.navigate(VCameraDestination.PerformanceMonitor)
                },
                onOpenBackupRestore = {
                    navController.navigate(VCameraDestination.BackupRestore)
                },
                onOpenGeneralSettings = {
                    navController.navigate(VCameraDestination.GeneralSettings)
                }
            )
        }

        composable<VCameraDestination.LocationManager> { backStackEntry ->
            val route = backStackEntry.toRoute<VCameraDestination.LocationManager>()
            val locationManagerViewModel: LocationManagerViewModel = viewModel(
                factory = LocationManagerViewModel.factory(
                    spaceId = route.spaceId,
                    spaceRepository = appContainer.spaceRepository,
                    locationProvider = appContainer.virtualLocationProvider
                )
            )
            LocationManagerScreen(
                viewModel = locationManagerViewModel,
                onBackClick = { navController.popBackStack() },
                onConfigureAppLocation = { packageName ->
                    navController.navigate(
                        VCameraDestination.MapPicker(
                            spaceId = route.spaceId,
                            packageName = packageName
                        )
                    )
                }
            )
        }

        composable<VCameraDestination.MapPicker> { backStackEntry ->
            val route = backStackEntry.toRoute<VCameraDestination.MapPicker>()
            val mapPickerViewModel: MapPickerViewModel = viewModel(
                factory = MapPickerViewModel.factory(
                    context = appContainer.applicationContext,
                    spaceId = route.spaceId,
                    packageName = route.packageName,
                    locationProvider = appContainer.virtualLocationProvider,
                    dispatchers = appContainer.dispatchers
                )
            )
            MapPickerScreen(
                viewModel = mapPickerViewModel,
                onBackClick = { navController.popBackStack() },
                onSaved = { navController.popBackStack() }
            )
        }

        composable<VCameraDestination.Premium> {
            PremiumScreen(
                onBackClick = { navController.popBackStack() }
            )
        }

        composable<VCameraDestination.GoogleFramework> {
            GoogleFrameworkScreen(
                onBackClick = { navController.popBackStack() }
            )
        }

        composable<VCameraDestination.PrivacyDashboard> {
            PrivacyDashboardScreen(
                permissionManager = appContainer.permissionManager,
                mediaInspector = appContainer.mediaInspector,
                processManager = appContainer.processManager,
                onBackClick = { navController.popBackStack() }
            )
        }

        composable<VCameraDestination.PerformanceMonitor> {
            PerformanceMonitorScreen(
                spaceRepository = appContainer.spaceRepository,
                processManager = appContainer.processManager,
                virtualEngine = appContainer.virtualEngine,
                cameraAdapterManager = appContainer.virtualCameraAdapterManager,
                onBackClick = { navController.popBackStack() },
                onOpenDiagnosticLogs = { navController.navigate(VCameraDestination.DiagnosticLogs) },
                onOpenCompatibilityStatus = { navController.navigate(VCameraDestination.CompatibilityStatus) }
            )
        }

        composable<VCameraDestination.CompatibilityStatus> {
            CompatibilityStatusScreen(
                compatibilityEngine = appContainer.compatibilityAssessmentEngine,
                onBackClick = { navController.popBackStack() }
            )
        }

        composable<VCameraDestination.DiagnosticLogs> {
            DiagnosticLogsScreen(
                logger = appContainer.diagnosticLogger,
                onBackClick = { navController.popBackStack() }
            )
        }

        composable<VCameraDestination.BackupRestore> {
            val backupViewModel: BackupRestoreViewModel = viewModel(
                factory = BackupRestoreViewModel.factory(
                    context = appContainer.applicationContext,
                    database = appContainer.database,
                    userPreferencesRepository = appContainer.userPreferencesRepository,
                    dispatchers = appContainer.dispatchers
                )
            )
            BackupRestoreScreen(
                viewModel = backupViewModel,
                onBackClick = { navController.popBackStack() }
            )
        }

        composable<VCameraDestination.GeneralSettings> {
            GeneralSettingsScreen(
                userPreferencesRepository = appContainer.userPreferencesRepository,
                spaceRepository = appContainer.spaceRepository,
                onBackClick = { navController.popBackStack() },
                onOpenSpaceManager = { navController.navigate(VCameraDestination.SpaceManager) },
                onOpenPrivacyDashboard = { navController.navigate(VCameraDestination.PrivacyDashboard) },
                onOpenPerformanceMonitor = { navController.navigate(VCameraDestination.PerformanceMonitor) },
                onOpenBackupRestore = { navController.navigate(VCameraDestination.BackupRestore) },
                onOpenAbout = { navController.navigate(VCameraDestination.About) },
                onOpenImportedApps = { navController.navigate(VCameraDestination.ImportedApps) },
                onOpenVirtualApps = { navController.navigate(VCameraDestination.VirtualApps) },
                onOpenCompatibilityStatus = { navController.navigate(VCameraDestination.CompatibilityStatus) },
                onOpenDiagnosticLogs = { navController.navigate(VCameraDestination.DiagnosticLogs) }
            )
        }

        composable<VCameraDestination.About> {
            AboutScreen(
                versionName = BuildConfig.VERSION_NAME,
                onBackClick = { navController.popBackStack() },
                onShareClick = { shareAppDetails(context) },
                onOpenSettingsClick = { navController.navigate(VCameraDestination.GeneralSettings) }
            )
        }
    }

    infoDialogData?.let { (title, message) ->
        ConfirmationDialog(
            title = title,
            message = message,
            confirmText = "OK",
            dismissText = "Close",
            icon = Icons.Default.Info,
            onConfirm = { infoDialogData = null },
            onDismiss = { infoDialogData = null }
        )
    }
}

private fun shareAppDetails(context: Context) {
    val shareIntent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "VCamera Space")
        putExtra(
            Intent.EXTRA_TEXT,
            "VCamera Space — Organize supported apps across isolated spaces and configure supported virtual camera sources."
        )
    }
    context.startActivity(Intent.createChooser(shareIntent, "Share VCamera Space"))
}
