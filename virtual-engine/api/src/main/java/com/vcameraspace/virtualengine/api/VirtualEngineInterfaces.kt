package com.vcameraspace.virtualengine.api

import com.vcameraspace.core.common.model.AppResult
import com.vcameraspace.core.common.model.ExecutionArchitecture
import com.vcameraspace.core.common.model.SpaceApp
import com.vcameraspace.core.common.model.VirtualAppStatus
import kotlinx.coroutines.flow.Flow

/**
 * Capability declaration for the active virtualization execution strategy.
 * Explicitly separates supported standard Android mechanisms from capabilities
 * that require a privileged system environment or licensed framework adapter.
 */
data class VirtualEngineCapabilities(
    val strategy: ExecutionArchitecture,
    val supportsScopedConfigDirectories: Boolean,
    val supportsInternalVirtualCameraPreview: Boolean,
    val supportsSystemWideCameraInterception: Boolean = false,
    val supportsArbitraryProcessMemoryIsolation: Boolean = false,
    val limitationSummary: String
)

enum class CompatibilitySeverity {
    INFO,
    WARNING,
    BLOCKER
}

data class CompatibilityIssue(
    val code: String,
    val title: String,
    val detail: String,
    val severity: CompatibilitySeverity
)

data class CompatibilityReport(
    val packageName: String,
    val isLaunchSupported: Boolean,
    val recommendedArchitecture: ExecutionArchitecture,
    val issues: List<CompatibilityIssue>
)

data class ProcessState(
    val spaceId: String,
    val packageName: String,
    val isRunning: Boolean,
    val lastStartedEpochMs: Long? = null,
    val crashCount: Int = 0,
    val lastCrashMessage: String? = null
)

enum class CameraSourceMode {
    REAL_DEVICE,
    LOCAL_VIDEO,
    NETWORK_STREAM,
    LOCAL_PICTURE
}

data class CameraSourceSpec(
    val spaceId: String,
    val packageName: String?,
    val mode: CameraSourceMode,
    val sourceUri: String? = null,
    val enableAudio: Boolean = false,
    val width: Int = 0,
    val height: Int = 0,
    val autoReconnect: Boolean = true
)

data class LocationPoint(
    val latitude: Double,
    val longitude: Double,
    val label: String? = null
)

interface VirtualEngine {
    val capabilities: VirtualEngineCapabilities
    suspend fun initialize(): AppResult<Unit>
    suspend fun shutdown(): AppResult<Unit>
}

interface VirtualPackageManager {
    fun observeAppsForSpace(spaceId: String): Flow<List<SpaceApp>>
    suspend fun registerApp(app: SpaceApp): AppResult<Unit>
    suspend fun removeApp(spaceId: String, packageName: String): AppResult<Unit>
    suspend fun updateAppStatus(
        spaceId: String,
        packageName: String,
        status: VirtualAppStatus
    ): AppResult<Unit>
}

interface VirtualProcessManager {
    fun observeProcesses(spaceId: String): Flow<List<ProcessState>>
    suspend fun startProcess(spaceId: String, packageName: String): AppResult<ProcessState>
    suspend fun stopProcess(spaceId: String, packageName: String): AppResult<Unit>
    suspend fun restartProcess(spaceId: String, packageName: String): AppResult<ProcessState>
    suspend fun stopAllProcesses(spaceId: String? = null): AppResult<Int>
}

interface VirtualStorageManager {
    suspend fun ensureSpaceDirectories(spaceId: String): AppResult<String>
    suspend fun calculateSpaceConfigUsageBytes(spaceId: String): AppResult<Long>
    suspend fun clearSpaceConfigCache(spaceId: String, packageName: String? = null): AppResult<Unit>
}

interface VirtualAppInstaller {
    suspend fun inspectInstalledPackage(packageName: String): AppResult<CompatibilityReport>
    suspend fun installIntoSpace(spaceId: String, packageName: String): AppResult<SpaceApp>
}

interface VirtualCameraProvider {
    fun observeCameraSpec(spaceId: String, packageName: String?): Flow<CameraSourceSpec>
    suspend fun saveCameraSpec(spec: CameraSourceSpec): AppResult<Unit>
}

interface VirtualLocationProvider {
    suspend fun isMockLocationReady(): AppResult<Boolean>
    suspend fun setSpaceAppLocation(
        spaceId: String,
        packageName: String,
        point: LocationPoint
    ): AppResult<Unit>
}

interface CompatibilityChecker {
    suspend fun evaluatePackage(packageName: String): AppResult<CompatibilityReport>
}
