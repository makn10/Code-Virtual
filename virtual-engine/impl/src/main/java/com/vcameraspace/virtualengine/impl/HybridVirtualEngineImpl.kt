package com.vcameraspace.virtualengine.impl

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.location.Location
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import com.vcameraspace.core.common.coroutines.DispatcherProvider
import com.vcameraspace.core.common.model.AppError
import com.vcameraspace.core.common.model.AppResult
import com.vcameraspace.core.common.model.ExecutionArchitecture
import com.vcameraspace.core.common.model.SpaceApp
import com.vcameraspace.core.common.model.VirtualAppStatus
import com.vcameraspace.core.database.dao.CameraConfigDao
import com.vcameraspace.core.database.dao.InstallationDao
import com.vcameraspace.core.database.dao.LocationConfigDao
import com.vcameraspace.core.database.dao.VirtualAppDao
import com.vcameraspace.core.database.entity.CameraConfigEntity
import com.vcameraspace.core.database.entity.InstallationEntity
import com.vcameraspace.core.database.entity.LocationConfigEntity
import com.vcameraspace.core.database.entity.VirtualAppEntity
import com.vcameraspace.core.security.UrlSecurityValidator
import com.vcameraspace.virtualengine.api.CameraSourceMode
import com.vcameraspace.virtualengine.api.CameraSourceSpec
import com.vcameraspace.virtualengine.api.CompatibilityChecker
import com.vcameraspace.virtualengine.api.CompatibilityIssue
import com.vcameraspace.virtualengine.api.CompatibilityReport
import com.vcameraspace.virtualengine.api.CompatibilitySeverity
import com.vcameraspace.virtualengine.api.LocationPoint
import com.vcameraspace.virtualengine.api.ProcessState
import com.vcameraspace.virtualengine.api.VirtualAppInstaller
import com.vcameraspace.virtualengine.api.VirtualCameraProvider
import com.vcameraspace.virtualengine.api.VirtualEngine
import com.vcameraspace.virtualengine.api.VirtualEngineCapabilities
import com.vcameraspace.virtualengine.api.VirtualLocationProvider
import com.vcameraspace.virtualengine.api.VirtualPackageManager
import com.vcameraspace.virtualengine.api.VirtualProcessManager
import com.vcameraspace.virtualengine.api.VirtualStorageManager
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipFile

data class InstalledAppCandidate(
    val packageName: String,
    val label: String,
    val versionName: String,
    val isSystemApp: Boolean,
    val icon: Drawable?
)

data class ApkInspectionResult(
    val displayName: String,
    val packageName: String,
    val appLabel: String,
    val versionName: String,
    val versionCode: Long,
    val minSdk: Int,
    val targetSdk: Int,
    val signingSha256: String,
    val apkAbis: List<String>,
    val fileSizeBytes: Long,
    val availableStorageBytes: Long,
    val isCompatible: Boolean,
    val issues: List<CompatibilityIssue>,
    val cachedApkPath: String
)

class HybridVirtualEngine(
    private val storageManager: VirtualStorageManager
) : VirtualEngine {
    override val capabilities: VirtualEngineCapabilities = VirtualEngineCapabilities(
        strategy = ExecutionArchitecture.SUPPORTED_SANDBOX,
        supportsScopedConfigDirectories = true,
        supportsInternalVirtualCameraPreview = true,
        supportsSystemWideCameraInterception = false,
        supportsArbitraryProcessMemoryIsolation = false,
        limitationSummary = "VCamera Space runs in Strategy A (Supported Android Sandbox & Scoped Config). " +
            "Universal interception of third-party app cameras, microphones, or private /data/data storage " +
            "requires a privileged system environment or licensed virtualization framework adapter."
    )

    override suspend fun initialize(): AppResult<Unit> {
        return when (val res = storageManager.ensureSpaceDirectories("space_1")) {
            is AppResult.Success -> AppResult.Success(Unit)
            is AppResult.Failure -> res
        }
    }

    override suspend fun shutdown(): AppResult<Unit> = AppResult.Success(Unit)
}

class RoomVirtualPackageManager(
    private val virtualAppDao: VirtualAppDao,
    private val dispatchers: DispatcherProvider
) : VirtualPackageManager {

    override fun observeAppsForSpace(spaceId: String): Flow<List<SpaceApp>> {
        return virtualAppDao.observeAppsForSpace(spaceId).map { list ->
            list.map { entity ->
                SpaceApp(
                    id = entity.id,
                    spaceId = entity.spaceId,
                    packageName = entity.packageName,
                    label = entity.label,
                    versionName = entity.versionName,
                    architecture = entity.architecture,
                    status = entity.status,
                    hasCustomCameraConfig = entity.hasCustomCameraConfig,
                    lastOpenedEpochMs = entity.lastOpenedEpochMs
                )
            }
        }
    }

    override suspend fun registerApp(app: SpaceApp): AppResult<Unit> = withContext(dispatchers.io) {
        runCatching {
            virtualAppDao.upsertApp(
                VirtualAppEntity(
                    id = app.id,
                    spaceId = app.spaceId,
                    packageName = app.packageName,
                    label = app.label,
                    versionName = app.versionName,
                    architecture = app.architecture,
                    status = app.status,
                    hasCustomCameraConfig = app.hasCustomCameraConfig,
                    lastOpenedEpochMs = app.lastOpenedEpochMs,
                    addedAtEpochMs = System.currentTimeMillis()
                )
            )
        }.fold(
            onSuccess = { AppResult.Success(Unit) },
            onFailure = { AppResult.Failure(AppError.DatabaseFailure(it.message ?: "Failed to register app")) }
        )
    }

    override suspend fun removeApp(spaceId: String, packageName: String): AppResult<Unit> =
        withContext(dispatchers.io) {
            runCatching {
                virtualAppDao.deleteApp(spaceId, packageName)
            }.fold(
                onSuccess = { AppResult.Success(Unit) },
                onFailure = { AppResult.Failure(AppError.DatabaseFailure(it.message ?: "Failed to remove app")) }
            )
        }

    override suspend fun updateAppStatus(
        spaceId: String,
        packageName: String,
        status: VirtualAppStatus
    ): AppResult<Unit> = withContext(dispatchers.io) {
        runCatching {
            val existing = virtualAppDao.observeAppsForSpace(spaceId).first()
                .firstOrNull { it.packageName == packageName }
            if (existing != null) {
                virtualAppDao.upsertApp(existing.copy(status = status))
            }
        }.fold(
            onSuccess = { AppResult.Success(Unit) },
            onFailure = { AppResult.Failure(AppError.DatabaseFailure(it.message ?: "Failed to update status")) }
        )
    }
}

class ManagedVirtualProcessManager(
    private val context: Context,
    private val virtualAppDao: VirtualAppDao,
    private val dispatchers: DispatcherProvider
) : VirtualProcessManager {

    private val _processes = MutableStateFlow<List<ProcessState>>(emptyList())
    private val _crashLogs = MutableStateFlow<List<String>>(emptyList())
    val crashLogs: Flow<List<String>> = _crashLogs.asStateFlow()

    val crashHandler = CoroutineExceptionHandler { _, throwable ->
        val safeMessage = UrlSecurityValidator.sanitizeLogMessage(
            throwable.message ?: throwable.javaClass.simpleName
        )
        _crashLogs.update { (listOf("Recovered engine exception: $safeMessage") + it).take(30) }
    }

    override fun observeProcesses(spaceId: String): Flow<List<ProcessState>> =
        _processes.map { list -> list.filter { it.spaceId == spaceId } }

    fun observeAllActiveProcesses(): Flow<List<ProcessState>> = _processes.asStateFlow()

    override suspend fun startProcess(spaceId: String, packageName: String): AppResult<ProcessState> =
        withContext(dispatchers.io) {
            runCatching {
                val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
                    ?: error("Package $packageName does not expose a launchable activity or is not visible.")
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

                val existing = _processes.value.firstOrNull {
                    it.spaceId == spaceId && it.packageName == packageName
                }
                val next = ProcessState(
                    spaceId = spaceId,
                    packageName = packageName,
                    isRunning = true,
                    lastStartedEpochMs = System.currentTimeMillis(),
                    crashCount = existing?.crashCount ?: 0,
                    lastCrashMessage = existing?.lastCrashMessage
                )
                upsertProcessState(next)

                val entity = virtualAppDao.observeAppsForSpace(spaceId).first()
                    .firstOrNull { it.packageName == packageName }
                if (entity != null) {
                    virtualAppDao.upsertApp(
                        entity.copy(
                            status = VirtualAppStatus.RUNNING,
                            lastOpenedEpochMs = System.currentTimeMillis()
                        )
                    )
                }

                withContext(dispatchers.main) {
                    context.startActivity(launchIntent)
                }
                next
            }.fold(
                onSuccess = { AppResult.Success(it) },
                onFailure = { throwable ->
                    val safeMsg = UrlSecurityValidator.sanitizeLogMessage(
                        throwable.message ?: "Launch failed"
                    )
                    val previous = _processes.value.firstOrNull {
                        it.spaceId == spaceId && it.packageName == packageName
                    }
                    val crashed = ProcessState(
                        spaceId = spaceId,
                        packageName = packageName,
                        isRunning = false,
                        lastStartedEpochMs = previous?.lastStartedEpochMs,
                        crashCount = (previous?.crashCount ?: 0) + 1,
                        lastCrashMessage = safeMsg
                    )
                    upsertProcessState(crashed)
                    _crashLogs.update { (listOf("[$packageName] $safeMsg") + it).take(30) }
                    AppResult.Failure(AppError.InvalidPackage(safeMsg))
                }
            )
        }

    override suspend fun stopProcess(spaceId: String, packageName: String): AppResult<Unit> =
        withContext(dispatchers.io) {
            _processes.update { list ->
                list.map {
                    if (it.spaceId == spaceId && it.packageName == packageName) {
                        it.copy(isRunning = false)
                    } else {
                        it
                    }
                }
            }
            val entity = virtualAppDao.observeAppsForSpace(spaceId).first()
                .firstOrNull { it.packageName == packageName }
            if (entity != null) {
                virtualAppDao.upsertApp(entity.copy(status = VirtualAppStatus.READY))
            }
            AppResult.Success(Unit)
        }

    override suspend fun restartProcess(spaceId: String, packageName: String): AppResult<ProcessState> {
        val current = _processes.value.firstOrNull {
            it.spaceId == spaceId && it.packageName == packageName
        }
        if ((current?.crashCount ?: 0) >= 3) {
            return AppResult.Failure(
                AppError.UnsupportedOperation(
                    message = "Restart paused after repeated failures for $packageName.",
                    recoveryHint = "Review App Information compatibility report before retrying."
                )
            )
        }
        stopProcess(spaceId, packageName)
        return startProcess(spaceId, packageName)
    }

    override suspend fun stopAllProcesses(spaceId: String?): AppResult<Int> =
        withContext(dispatchers.io) {
            var count = 0
            _processes.update { list ->
                list.map { state ->
                    if ((spaceId == null || state.spaceId == spaceId) && state.isRunning) {
                        count++
                        state.copy(isRunning = false)
                    } else {
                        state
                    }
                }
            }
            AppResult.Success(count)
        }

    private fun upsertProcessState(state: ProcessState) {
        _processes.update { current ->
            val filtered = current.filterNot {
                it.spaceId == state.spaceId && it.packageName == state.packageName
            }
            filtered + state
        }
    }
}

class ScopedVirtualStorageManager(
    private val context: Context,
    private val dispatchers: DispatcherProvider
) : VirtualStorageManager {

    override suspend fun ensureSpaceDirectories(spaceId: String): AppResult<String> =
        withContext(dispatchers.io) {
            runCatching {
                val root = File(File(context.filesDir, "spaces"), spaceId)
                listOf("config", "camera", "media").forEach { sub ->
                    File(root, sub).mkdirs()
                }
                root.absolutePath
            }.fold(
                onSuccess = { AppResult.Success(it) },
                onFailure = { AppResult.Failure(AppError.Unexpected(it.message ?: "Storage error", it)) }
            )
        }

    override suspend fun calculateSpaceConfigUsageBytes(spaceId: String): AppResult<Long> =
        withContext(dispatchers.io) {
            runCatching {
                val root = File(File(context.filesDir, "spaces"), spaceId)
                if (!root.exists()) 0L else root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            }.fold(
                onSuccess = { AppResult.Success(it) },
                onFailure = { AppResult.Failure(AppError.Unexpected(it.message ?: "Storage error", it)) }
            )
        }

    override suspend fun clearSpaceConfigCache(spaceId: String, packageName: String?): AppResult<Unit> =
        withContext(dispatchers.io) {
            runCatching {
                val root = File(File(context.filesDir, "spaces"), spaceId)
                if (packageName == null) {
                    File(root, "config").deleteRecursively()
                    File(root, "config").mkdirs()
                } else {
                    File(File(root, "config"), "$packageName.json").delete()
                }
                Unit
            }.fold(
                onSuccess = { AppResult.Success(Unit) },
                onFailure = { AppResult.Failure(AppError.Unexpected(it.message ?: "Clear cache error", it)) }
            )
        }
}

class AndroidCompatibilityChecker(
    private val context: Context,
    private val dispatchers: DispatcherProvider
) : CompatibilityChecker {

    override suspend fun evaluatePackage(packageName: String): AppResult<CompatibilityReport> =
        withContext(dispatchers.io) {
            runCatching {
                val pm = context.packageManager
                val pkgInfo = getPackageInfoCompat(pm, packageName)
                val appInfo = pkgInfo.applicationInfo
                    ?: return@runCatching CompatibilityReport(
                        packageName = packageName,
                        isLaunchSupported = false,
                        recommendedArchitecture = ExecutionArchitecture.UNSUPPORTED,
                        issues = listOf(
                            CompatibilityIssue(
                                code = "NO_APP_INFO",
                                title = "Missing Application Metadata",
                                detail = "Package metadata could not be read from PackageManager.",
                                severity = CompatibilitySeverity.BLOCKER
                            )
                        )
                    )

                val issues = mutableListOf<CompatibilityIssue>()
                val minSdk = appInfo.minSdkVersion
                if (minSdk > Build.VERSION.SDK_INT) {
                    issues += CompatibilityIssue(
                        code = "SDK_TOO_NEW",
                        title = "Android API Incompatibility",
                        detail = "Requires Android API $minSdk; device runs API ${Build.VERSION.SDK_INT}.",
                        severity = CompatibilitySeverity.BLOCKER
                    )
                }

                val hasLaunchIntent = pm.getLaunchIntentForPackage(packageName) != null
                if (!hasLaunchIntent) {
                    issues += CompatibilityIssue(
                        code = "NO_LAUNCHER",
                        title = "No Launchable Entry Activity",
                        detail = "Package has no MAIN/LAUNCHER intent visible to VCamera Space.",
                        severity = CompatibilitySeverity.WARNING
                    )
                }

                val nativeDir = appInfo.nativeLibraryDir
                if (!nativeDir.isNullOrBlank()) {
                    val nativeFolder = File(nativeDir)
                    val soCount = nativeFolder.listFiles()?.size ?: 0
                    if (soCount > 0) {
                        issues += CompatibilityIssue(
                            code = "NATIVE_LIBS",
                            title = "Native Libraries Detected ($soCount)",
                            detail = "Device ABIs: ${Build.SUPPORTED_ABIS.joinToString(", ")}. Standard sandbox launches use host Android ABI.",
                            severity = CompatibilitySeverity.INFO
                        )
                    }
                }

                val requestedPerms = pkgInfo.requestedPermissions?.toSet().orEmpty()
                if (requestedPerms.any { it.contains("CAMERA") }) {
                    issues += CompatibilityIssue(
                        code = "CAMERA_RESTRICTION",
                        title = "Camera Integration Scope",
                        detail = "Ordinary Android apps cannot universally intercept Camera1/2/X inside unrelated apps without an explicit integration or privileged framework.",
                        severity = CompatibilitySeverity.INFO
                    )
                }

                val gmsInstalled = isPackageVisible(pm, "com.google.android.gms")
                if (!gmsInstalled) {
                    issues += CompatibilityIssue(
                        code = "GMS_ABSENT",
                        title = "Google Play Services Not Detected",
                        detail = "Apps depending on GMS may have reduced functionality on this device.",
                        severity = CompatibilitySeverity.WARNING
                    )
                }

                val hasBlocker = issues.any { it.severity == CompatibilitySeverity.BLOCKER }
                CompatibilityReport(
                    packageName = packageName,
                    isLaunchSupported = !hasBlocker && hasLaunchIntent,
                    recommendedArchitecture = if (hasBlocker) {
                        ExecutionArchitecture.UNSUPPORTED
                    } else {
                        ExecutionArchitecture.SUPPORTED_SANDBOX
                    },
                    issues = issues
                )
            }.fold(
                onSuccess = { AppResult.Success(it) },
                onFailure = {
                    AppResult.Failure(
                        AppError.InvalidPackage(
                            message = "Unable to inspect $packageName: ${it.message ?: "Package not visible"}"
                        )
                    )
                }
            )
        }

    fun queryInstalledLaunchableApps(): List<InstalledAppCandidate> {
        val pm = context.packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolveInfos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(mainIntent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(mainIntent, 0)
        }
        return resolveInfos
            .mapNotNull { info ->
                val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
                if (pkg == context.packageName) return@mapNotNull null
                val appInfo = info.activityInfo.applicationInfo
                val pkgInfo = runCatching { getPackageInfoCompat(pm, pkg) }.getOrNull()
                InstalledAppCandidate(
                    packageName = pkg,
                    label = info.loadLabel(pm).toString().ifBlank { pkg },
                    versionName = pkgInfo?.versionName ?: "1.0",
                    isSystemApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                    icon = runCatching { info.loadIcon(pm) }.getOrNull()
                )
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }

    fun inspectApkFromUri(uri: Uri): AppResult<ApkInspectionResult> {
        return runCatching {
            val resolver = context.contentResolver
            val displayName = uri.lastPathSegment ?: "imported.apk"
            val tempApk = File(context.cacheDir, "inspect_${System.currentTimeMillis()}.apk")
            resolver.openInputStream(uri)?.use { input ->
                tempApk.outputStream().use { output -> input.copyTo(output) }
            } ?: error("Cannot read selected file from Storage Access Framework.")

            if (tempApk.length() < 4L) {
                tempApk.delete()
                error("Selected file is empty or invalid.")
            }

            // Verify ZIP magic bytes ("PK")
            val header = ByteArray(2)
            tempApk.inputStream().use { it.read(header) }
            if (header[0] != 0x50.toByte() || header[1] != 0x4B.toByte()) {
                tempApk.delete()
                error("Selected file is not a valid Android APK archive.")
            }

            val pm = context.packageManager
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                PackageManager.GET_SIGNING_CERTIFICATES
            } else {
                @Suppress("DEPRECATION")
                PackageManager.GET_SIGNATURES
            }
            val pkgInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageArchiveInfo(tempApk.absolutePath, PackageManager.PackageInfoFlags.of(flags.toLong()))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageArchiveInfo(tempApk.absolutePath, flags)
            } ?: run {
                tempApk.delete()
                error("Android PackageManager could not parse APK manifest.")
            }

            val appInfo = pkgInfo.applicationInfo?.apply {
                sourceDir = tempApk.absolutePath
                publicSourceDir = tempApk.absolutePath
            }

            val apkAbis = extractApkAbis(tempApk)
            val availableBytes = availableStorageBytes()
            val issues = mutableListOf<CompatibilityIssue>()

            val minSdk = appInfo?.minSdkVersion ?: 21
            val targetSdk = appInfo?.targetSdkVersion ?: 34
            if (minSdk > Build.VERSION.SDK_INT) {
                issues += CompatibilityIssue(
                    code = "APK_MIN_SDK",
                    title = "SDK Requirement Not Met",
                    detail = "APK requires Android API $minSdk, device is API ${Build.VERSION.SDK_INT}.",
                    severity = CompatibilitySeverity.BLOCKER
                )
            }
            if (apkAbis.isNotEmpty() && apkAbis.none { it in Build.SUPPORTED_ABIS }) {
                issues += CompatibilityIssue(
                    code = "APK_ABI_MISMATCH",
                    title = "ABI Mismatch",
                    detail = "APK supports ${apkAbis.joinToString()}, device supports ${Build.SUPPORTED_ABIS.joinToString()}.",
                    severity = CompatibilitySeverity.BLOCKER
                )
            }
            if (availableBytes < tempApk.length() * 2) {
                issues += CompatibilityIssue(
                    code = "LOW_STORAGE",
                    title = "Low Available Storage",
                    detail = "Insufficient free space for safe installation.",
                    severity = CompatibilitySeverity.BLOCKER
                )
            }

            val signatureDigest = extractSignatureSha256(pkgInfo)
            val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pkgInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                pkgInfo.versionCode.toLong()
            }

            ApkInspectionResult(
                displayName = displayName,
                packageName = pkgInfo.packageName,
                appLabel = appInfo?.loadLabel(pm)?.toString() ?: pkgInfo.packageName,
                versionName = pkgInfo.versionName ?: "1.0",
                versionCode = versionCode,
                minSdk = minSdk,
                targetSdk = targetSdk,
                signingSha256 = signatureDigest,
                apkAbis = apkAbis,
                fileSizeBytes = tempApk.length(),
                availableStorageBytes = availableBytes,
                isCompatible = issues.none { it.severity == CompatibilitySeverity.BLOCKER },
                issues = issues,
                cachedApkPath = tempApk.absolutePath
            )
        }.fold(
            onSuccess = { AppResult.Success(it) },
            onFailure = {
                AppResult.Failure(
                    AppError.UnsupportedApk(it.message ?: "Invalid APK file selected.")
                )
            }
        )
    }

    fun availableStorageBytes(): Long {
        val stat = StatFs(Environment.getDataDirectory().path)
        return stat.availableBytes
    }

    private fun extractApkAbis(apkFile: File): List<String> {
        return runCatching {
            ZipFile(apkFile).use { zip ->
                val entries = zip.entries()
                val abis = linkedSetOf<String>()
                while (entries.hasMoreElements()) {
                    val name = entries.nextElement().name
                    if (name.startsWith("lib/") && name.count { it == '/' } >= 2) {
                        abis += name.substringAfter("lib/").substringBefore('/')
                    }
                }
                abis.toList()
            }
        }.getOrDefault(emptyList())
    }

    private fun extractSignatureSha256(pkgInfo: PackageInfo): String {
        val bytes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pkgInfo.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()
        } else {
            @Suppress("DEPRECATION")
            pkgInfo.signatures?.firstOrNull()?.toByteArray()
        } ?: return "Unsigned / Not Present"
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.take(12).joinToString(":") { "%02X".format(it) }
    }

    private fun getPackageInfoCompat(pm: PackageManager, packageName: String): PackageInfo {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
        }
    }

    private fun isPackageVisible(pm: PackageManager, pkg: String): Boolean {
        return runCatching { getPackageInfoCompat(pm, pkg) }.isSuccess
    }
}

class AndroidVirtualAppInstaller(
    private val context: Context,
    private val virtualAppDao: VirtualAppDao,
    private val installationDao: InstallationDao,
    private val compatibilityChecker: AndroidCompatibilityChecker,
    private val storageManager: VirtualStorageManager,
    private val dispatchers: DispatcherProvider
) : VirtualAppInstaller {

    override suspend fun inspectInstalledPackage(packageName: String): AppResult<CompatibilityReport> =
        compatibilityChecker.evaluatePackage(packageName)

    override suspend fun installIntoSpace(spaceId: String, packageName: String): AppResult<SpaceApp> =
        withContext(dispatchers.io) {
            val installId = UUID.randomUUID().toString()
            val startedAt = System.currentTimeMillis()
            installationDao.upsertInstallation(
                InstallationEntity(
                    id = installId,
                    spaceId = spaceId,
                    packageName = packageName,
                    sourceType = "INSTALLED_PACKAGE",
                    status = "CHECKING",
                    errorMessage = null,
                    startedAtEpochMs = startedAt,
                    completedAtEpochMs = null
                )
            )

            val existing = virtualAppDao.observeAppsForSpace(spaceId).first()
                .firstOrNull { it.packageName == packageName }
            if (existing != null) {
                val msg = "$packageName is already added to this space."
                recordInstallEnd(installId, spaceId, packageName, "FAILED", msg, startedAt)
                return@withContext AppResult.Failure(AppError.InstallationFailed(msg))
            }

            if (compatibilityChecker.availableStorageBytes() < 10L * 1024L * 1024L) {
                val msg = "Insufficient device storage (< 10 MB free)."
                recordInstallEnd(installId, spaceId, packageName, "FAILED", msg, startedAt)
                return@withContext AppResult.Failure(AppError.InstallationFailed(msg))
            }

            val reportResult = compatibilityChecker.evaluatePackage(packageName)
            if (reportResult is AppResult.Failure) {
                recordInstallEnd(installId, spaceId, packageName, "FAILED", reportResult.error.message, startedAt)
                return@withContext reportResult
            }

            val report = (reportResult as AppResult.Success).data
            if (report.recommendedArchitecture == ExecutionArchitecture.UNSUPPORTED) {
                val blocker = report.issues.firstOrNull { it.severity == CompatibilitySeverity.BLOCKER }
                val msg = blocker?.detail ?: "Application is incompatible with this Android environment."
                recordInstallEnd(installId, spaceId, packageName, "FAILED", msg, startedAt)
                return@withContext AppResult.Failure(AppError.InstallationFailed(msg))
            }

            val pm = context.packageManager
            val appInfo = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    pm.getApplicationInfo(packageName, 0)
                }
            }.getOrNull() ?: run {
                val msg = "Package metadata disappeared during setup."
                recordInstallEnd(installId, spaceId, packageName, "FAILED", msg, startedAt)
                return@withContext AppResult.Failure(AppError.InvalidPackage(msg))
            }

            val pkgInfo = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    pm.getPackageInfo(packageName, 0)
                }
            }.getOrNull()

            storageManager.ensureSpaceDirectories(spaceId)
            val label = pm.getApplicationLabel(appInfo).toString()
            val version = pkgInfo?.versionName ?: "1.0"
            val id = "${spaceId}::$packageName"

            virtualAppDao.upsertApp(
                VirtualAppEntity(
                    id = id,
                    spaceId = spaceId,
                    packageName = packageName,
                    label = label,
                    versionName = version,
                    architecture = report.recommendedArchitecture,
                    status = VirtualAppStatus.READY,
                    hasCustomCameraConfig = false,
                    lastOpenedEpochMs = null,
                    addedAtEpochMs = System.currentTimeMillis()
                )
            )
            recordInstallEnd(installId, spaceId, packageName, "COMPLETED", null, startedAt)

            AppResult.Success(
                SpaceApp(
                    id = id,
                    spaceId = spaceId,
                    packageName = packageName,
                    label = label,
                    versionName = version,
                    architecture = report.recommendedArchitecture,
                    status = VirtualAppStatus.READY
                )
            )
        }

    private suspend fun recordInstallEnd(
        id: String,
        spaceId: String,
        packageName: String,
        status: String,
        error: String?,
        startedAt: Long
    ) {
        installationDao.upsertInstallation(
            InstallationEntity(
                id = id,
                spaceId = spaceId,
                packageName = packageName,
                sourceType = "INSTALLED_PACKAGE",
                status = status,
                errorMessage = error,
                startedAtEpochMs = startedAt,
                completedAtEpochMs = System.currentTimeMillis()
            )
        )
    }
}

class RoomVirtualCameraProvider(
    private val cameraConfigDao: CameraConfigDao
) : VirtualCameraProvider {
    override fun observeCameraSpec(spaceId: String, packageName: String?): Flow<CameraSourceSpec> {
        return cameraConfigDao.observeConfigsForSpace(spaceId).map { list ->
            val entity = list.firstOrNull { it.packageName == packageName }
                ?: list.firstOrNull { it.packageName == null }
            if (entity == null) {
                CameraSourceSpec(
                    spaceId = spaceId,
                    packageName = packageName,
                    mode = CameraSourceMode.REAL_DEVICE
                )
            } else {
                CameraSourceSpec(
                    spaceId = entity.spaceId,
                    packageName = entity.packageName,
                    mode = runCatching { CameraSourceMode.valueOf(entity.mode) }
                        .getOrDefault(CameraSourceMode.REAL_DEVICE),
                    sourceUri = entity.sourceUri,
                    enableAudio = entity.enableAudio,
                    width = entity.width,
                    height = entity.height,
                    autoReconnect = entity.autoReconnect
                )
            }
        }
    }

    override suspend fun saveCameraSpec(spec: CameraSourceSpec): AppResult<Unit> {
        return runCatching {
            val id = "${spec.spaceId}::${spec.packageName ?: "__space_default__"}"
            cameraConfigDao.upsertConfig(
                CameraConfigEntity(
                    id = id,
                    spaceId = spec.spaceId,
                    packageName = spec.packageName,
                    mode = spec.mode.name,
                    sourceUri = spec.sourceUri,
                    enableAudio = spec.enableAudio,
                    width = spec.width,
                    height = spec.height,
                    orientationDegrees = 0,
                    autoReconnect = spec.autoReconnect,
                    updatedAtEpochMs = System.currentTimeMillis()
                )
            )
        }.fold(
            onSuccess = { AppResult.Success(Unit) },
            onFailure = { AppResult.Failure(AppError.DatabaseFailure(it.message ?: "Unable to save camera spec.")) }
        )
    }
}

class AndroidVirtualLocationProvider(
    private val context: Context,
    private val locationConfigDao: LocationConfigDao,
    private val dispatchers: DispatcherProvider
) : VirtualLocationProvider {

    fun observeSpaceLocations(spaceId: String): Flow<List<LocationConfigEntity>> =
        locationConfigDao.observeConfigsForSpace(spaceId)

    override suspend fun isMockLocationReady(): AppResult<Boolean> = withContext(dispatchers.io) {
        runCatching {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val testProvider = LocationManager.GPS_PROVIDER
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                lm.addTestProvider(
                    testProvider,
                    false,
                    false,
                    false,
                    false,
                    true,
                    true,
                    true,
                    ProviderProperties.POWER_USAGE_LOW,
                    ProviderProperties.ACCURACY_FINE
                )
            } else {
                @Suppress("DEPRECATION")
                lm.addTestProvider(testProvider, false, false, false, false, true, true, true, 1, 1)
            }
            lm.removeTestProvider(testProvider)
            true
        }.fold(
            onSuccess = { AppResult.Success(true) },
            onFailure = { AppResult.Success(false) }
        )
    }

    override suspend fun setSpaceAppLocation(
        spaceId: String,
        packageName: String,
        point: LocationPoint
    ): AppResult<Unit> = withContext(dispatchers.io) {
        runCatching {
            locationConfigDao.upsertConfig(
                LocationConfigEntity(
                    id = "${spaceId}::$packageName",
                    spaceId = spaceId,
                    packageName = packageName,
                    latitude = point.latitude,
                    longitude = point.longitude,
                    locationLabel = point.label,
                    enabled = true,
                    updatedAtEpochMs = System.currentTimeMillis()
                )
            )

            // Apply Android test provider location ONLY if Developer Options permits mock location app
            runCatching {
                val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    lm.addTestProvider(
                        LocationManager.GPS_PROVIDER,
                        false, false, false, false, true, true, true,
                        ProviderProperties.POWER_USAGE_LOW,
                        ProviderProperties.ACCURACY_FINE
                    )
                }
                lm.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true)
                val loc = Location(LocationManager.GPS_PROVIDER).apply {
                    latitude = point.latitude
                    longitude = point.longitude
                    altitude = 12.0
                    accuracy = 5.0f
                    time = System.currentTimeMillis()
                    elapsedRealtimeNanos = android.os.SystemClock.elapsedRealtimeNanos()
                }
                lm.setTestProviderLocation(LocationManager.GPS_PROVIDER, loc)
            }
            Unit
        }.fold(
            onSuccess = { AppResult.Success(Unit) },
            onFailure = {
                AppResult.Failure(
                    AppError.DatabaseFailure(it.message ?: "Unable to save location configuration.")
                )
            }
        )
    }
}
