package com.vcameraspace.virtualengine.impl

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import com.vcameraspace.core.common.coroutines.DispatcherProvider
import com.vcameraspace.core.common.diagnostics.DiagnosticCategory
import com.vcameraspace.core.common.diagnostics.DiagnosticLogger
import kotlinx.coroutines.withContext

enum class FeatureSupportLevel(val label: String) {
    SUPPORTED("Supported"),
    PARTIALLY_SUPPORTED("Partially Supported"),
    NOT_VERIFIED_ON_DEVICE("Not Verified on Device"),
    NOT_SUPPORTED("Not Supported")
}

data class VirtualizationFrameworkOption(
    val id: String,
    val name: String,
    val licenseOrStatus: String,
    val android16Support: FeatureSupportLevel,
    val policyCompliance: FeatureSupportLevel,
    val isActiveInApp: Boolean,
    val assessmentSummary: String
)

data class TargetAppCompatibilityAssessment(
    val packageName: String,
    val displayName: String,
    val installedOnDevice: Boolean,
    val installedVersionName: String?,
    val apkImportSupport: FeatureSupportLevel,
    val virtualLaunchStatus: FeatureSupportLevel,
    val loginAndAccountStatus: FeatureSupportLevel,
    val dataIsolationStatus: FeatureSupportLevel,
    val micAudioStatus: FeatureSupportLevel,
    val virtualCameraStatus: FeatureSupportLevel,
    val stabilityStatus: FeatureSupportLevel,
    val runtimeNotes: String
)

data class CompatibilityMatrixSnapshot(
    val androidApiLevel: Int,
    val androidRelease: String,
    val deviceAbis: List<String>,
    val frameworks: List<VirtualizationFrameworkOption>,
    val targetApps: List<TargetAppCompatibilityAssessment>,
    val generatedAtEpochMs: Long
)

class CompatibilityAssessmentEngine(
    private val context: Context,
    private val dispatchers: DispatcherProvider,
    private val logger: DiagnosticLogger? = null
) {

    suspend fun evaluateAll(): CompatibilityMatrixSnapshot = withContext(dispatchers.io) {
        val pm = context.packageManager
        val targets = listOf(
            assessTargetApp(
                pm = pm,
                packageName = "com.whatsapp",
                displayName = "WhatsApp",
                note = "Uses Play Integrity, strict certificate signature pinning, and private /data/data storage. Unprivileged Android 16 workspaces cannot inject frames into WhatsApp's Camera2 capture pipeline or clone its login token across sandboxes without system/Work Profile integration."
            ),
            assessTargetApp(
                pm = pm,
                packageName = "com.facebook.orca",
                displayName = "Facebook Messenger",
                note = "Relies on Meta shared authentication providers, native WebRTC/media stacks, and strict cross-app camera/audio boundaries on Android 14–16. Host launcher hand-off is supported if installed; internal camera hook injection requires privileged hooks (unavailable in standard Android sandbox)."
            ),
            assessTargetApp(
                pm = pm,
                packageName = "com.imo.android.imoim",
                displayName = "imo-International Calls & Chat",
                note = "Proprietary native VoIP/video transport with tight device-bound codecs and NDK verification. APK archive inspection & Workspace catalog registration work; device-level video call camera replacement and dual-instance isolation are not verified on hardware."
            ),
            assessTargetApp(
                pm = pm,
                packageName = "com.instagram.android",
                displayName = "Instagram",
                note = "Uses certificate pinning, Camera2/MediaCodec hardware capture pipelines, and Meta process isolation. App cloning alone cannot replace Instagram's live camera stream without privileged OS/system integration."
            ),
            assessTargetApp(
                pm = pm,
                packageName = "us.zoom.videomeetings",
                displayName = "Zoom Workplace",
                note = "Relies on native NDK video conferencing SDKs and strict Android 16 Camera2/microphone permissions. Workspace registration & launch inspection work; live call camera feed replacement is not supported via unprivileged cloning."
            )
        )

        val frameworks = listOf(
            VirtualizationFrameworkOption(
                id = "scoped_sandbox",
                name = "VCamera Scoped Sandbox & Frame Pipeline (Active)",
                licenseOrStatus = "Native Android / Zero DCL",
                android16Support = FeatureSupportLevel.SUPPORTED,
                policyCompliance = FeatureSupportLevel.SUPPORTED,
                isActiveInApp = true,
                assessmentSummary = "Manages isolated workspace directories, SAF APK import/inspection, explicit package launches, CameraX preview, Media3 playback, and real MediaCodec + I420/YUV_420_888 virtual camera frame pipelines within Android security boundaries."
            ),
            VirtualizationFrameworkOption(
                id = "blackbox",
                name = "BlackBox (Open-Source Virtualization)",
                licenseOrStatus = "Apache-2.0 (Upstream Archived)",
                android16Support = FeatureSupportLevel.NOT_SUPPORTED,
                policyCompliance = FeatureSupportLevel.NOT_SUPPORTED,
                isActiveInApp = false,
                assessmentSummary = "Upstream FBlackBox is archived and relies on hidden Binder reflection and runtime native/dex hooking that break under Android 14–16 strict SELinux/hidden-API bans and violate Dynamic Code Loading rules."
            ),
            VirtualizationFrameworkOption(
                id = "virtualapp",
                name = "VirtualApp (Commercial Engine)",
                licenseOrStatus = "Proprietary Commercial SDK",
                android16Support = FeatureSupportLevel.PARTIALLY_SUPPORTED,
                policyCompliance = FeatureSupportLevel.NOT_SUPPORTED,
                isActiveInApp = false,
                assessmentSummary = "Requires commercial binary SDKs and IO/Binder/Camera HAL hooks; unsuitable for open policy-compliant Play builds and triggers Play Integrity/anti-tamper blocks on apps like WhatsApp."
            )
        )

        logger?.logInfo(
            DiagnosticCategory.COMPATIBILITY,
            "Evaluated framework & target compatibility matrix (API ${Build.VERSION.SDK_INT})"
        )

        CompatibilityMatrixSnapshot(
            androidApiLevel = Build.VERSION.SDK_INT,
            androidRelease = Build.VERSION.RELEASE ?: Build.VERSION.SDK_INT.toString(),
            deviceAbis = Build.SUPPORTED_ABIS.toList(),
            frameworks = frameworks,
            targetApps = targets,
            generatedAtEpochMs = System.currentTimeMillis()
        )
    }

    private fun assessTargetApp(
        pm: PackageManager,
        packageName: String,
        displayName: String,
        note: String
    ): TargetAppCompatibilityAssessment {
        val pkgInfo = getInstalledPkg(pm, packageName)
        val installed = pkgInfo != null
        val launchable = installed && pm.getLaunchIntentForPackage(packageName) != null
        return TargetAppCompatibilityAssessment(
            packageName = packageName,
            displayName = displayName,
            installedOnDevice = installed,
            installedVersionName = pkgInfo?.versionName,
            apkImportSupport = FeatureSupportLevel.SUPPORTED,
            virtualLaunchStatus = if (launchable) {
                FeatureSupportLevel.PARTIALLY_SUPPORTED
            } else {
                FeatureSupportLevel.NOT_VERIFIED_ON_DEVICE
            },
            loginAndAccountStatus = FeatureSupportLevel.NOT_VERIFIED_ON_DEVICE,
            dataIsolationStatus = FeatureSupportLevel.PARTIALLY_SUPPORTED,
            micAudioStatus = FeatureSupportLevel.NOT_SUPPORTED,
            virtualCameraStatus = FeatureSupportLevel.NOT_SUPPORTED,
            stabilityStatus = FeatureSupportLevel.NOT_VERIFIED_ON_DEVICE,
            runtimeNotes = note
        )
    }

    private fun getInstalledPkg(pm: PackageManager, pkg: String): PackageInfo? {
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(pkg, 0)
            }
        }.getOrNull()
    }
}
