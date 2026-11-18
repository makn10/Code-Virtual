package com.vcameraspace.core.common.model

import kotlinx.coroutines.CancellationException

/**
 * Represents an isolated workspace configuration inside VCamera Space.
 */
data class Space(
    val id: String,
    val name: String,
    val iconKey: String = "layers",
    val isDefault: Boolean = false,
    val sortOrder: Int = 0,
    val appCount: Int = 0,
    val storageBytes: Long = 0L,
    val isolatedCameraByDefault: Boolean = true,
    val requireLaunchConfirm: Boolean = false,
    val createdAtEpochMs: Long = System.currentTimeMillis()
)

/**
 * Execution strategy used for a managed application entry.
 */
enum class ExecutionArchitecture {
    SUPPORTED_SANDBOX,
    FRAMEWORK_ADAPTER,
    UNSUPPORTED
}

/**
 * Installation and runtime status for a managed application record.
 */
enum class VirtualAppStatus {
    READY,
    RUNNING,
    INSTALLING,
    INCOMPATIBLE,
    ERROR
}

/**
 * Represents an application entry registered within a specific [Space].
 */
data class SpaceApp(
    val id: String,
    val spaceId: String,
    val packageName: String,
    val label: String,
    val versionName: String,
    val architecture: ExecutionArchitecture,
    val status: VirtualAppStatus = VirtualAppStatus.READY,
    val hasCustomCameraConfig: Boolean = false,
    val lastOpenedEpochMs: Long? = null
)

/**
 * Metadata and helpers for the built-in Space Virtual Camera application
 * and known external camera/video-calling packages.
 */
object BuiltInVirtualApps {
    const val VIRTUAL_CAMERA_PACKAGE = "com.vcameraspace.virtualcamera"
    const val VIRTUAL_CAMERA_LABEL = "Virtual Camera"
    const val VIRTUAL_CAMERA_VERSION = "1.0-live"

    private val cameraOrSocialKeywords = listOf(
        "camera",
        "whatsapp",
        "instagram",
        "facebook.orca",
        "messenger",
        "imoim",
        "zoom",
        "telegram",
        "snapchat",
        "tiktok",
        "meet",
        "skype"
    )

    fun isBuiltInVirtualCamera(packageName: String?): Boolean =
        packageName == VIRTUAL_CAMERA_PACKAGE

    fun isKnownCameraOrSocialApp(packageName: String, label: String = ""): Boolean {
        val pkgLower = packageName.lowercase()
        val labelLower = label.lowercase()
        return cameraOrSocialKeywords.any { keyword ->
            pkgLower.contains(keyword) || labelLower.contains(keyword)
        }
    }

    fun createVirtualCameraApp(
        spaceId: String,
        hasCustomCameraConfig: Boolean = false
    ): SpaceApp = SpaceApp(
        id = "${spaceId}::$VIRTUAL_CAMERA_PACKAGE",
        spaceId = spaceId,
        packageName = VIRTUAL_CAMERA_PACKAGE,
        label = VIRTUAL_CAMERA_LABEL,
        versionName = VIRTUAL_CAMERA_VERSION,
        architecture = ExecutionArchitecture.SUPPORTED_SANDBOX,
        status = VirtualAppStatus.READY,
        hasCustomCameraConfig = hasCustomCameraConfig
    )
}

/**
 * User-selected theme preference persisted via DataStore.
 */
enum class ThemePreference {
    SYSTEM,
    LIGHT,
    DARK
}

/**
 * Centralized domain error hierarchy for predictable recovery messaging.
 */
sealed class AppError(
    open val message: String,
    open val recoveryHint: String? = null
) {
    data class InstallationFailed(
        override val message: String,
        override val recoveryHint: String? = "Verify APK integrity and available storage."
    ) : AppError(message, recoveryHint)

    data class UnsupportedApk(
        override val message: String,
        override val recoveryHint: String? = "Select a valid Android APK compatible with this device ABI."
    ) : AppError(message, recoveryHint)

    data class InvalidPackage(
        override val message: String,
        override val recoveryHint: String? = "Confirm the package is installed and visible on this device."
    ) : AppError(message, recoveryHint)

    data class AbiMismatch(
        override val message: String,
        override val recoveryHint: String? = "This package requires a native ABI not supported on this device."
    ) : AppError(message, recoveryHint)

    data class DatabaseFailure(
        override val message: String,
        override val recoveryHint: String? = "Retry the operation or restore from a recent configuration backup."
    ) : AppError(message, recoveryHint)

    data class MediaPlaybackError(
        override val message: String,
        override val recoveryHint: String? = "Choose a supported MP4/HLS/DASH media source."
    ) : AppError(message, recoveryHint)

    data class NetworkTimeout(
        override val message: String,
        override val recoveryHint: String? = "Check your network connection and stream endpoint."
    ) : AppError(message, recoveryHint)

    data class PermissionDenied(
        override val message: String,
        override val recoveryHint: String? = "Grant the required permission in Android Settings."
    ) : AppError(message, recoveryHint)

    data class UnsupportedOperation(
        override val message: String,
        override val recoveryHint: String? = "This capability requires an explicitly supported integration or privileged environment."
    ) : AppError(message, recoveryHint)

    data class Unexpected(
        override val message: String,
        val cause: Throwable? = null
    ) : AppError(message, "Try again or inspect diagnostics.")
}

sealed interface AppResult<out T> {
    data class Success<T>(val data: T) : AppResult<T>
    data class Failure(val error: AppError) : AppResult<Nothing>
}

inline fun <T> runCatchingAppResult(
    errorMapper: (Throwable) -> AppError = { AppError.Unexpected(it.message ?: "Unexpected error", it) },
    block: () -> T
): AppResult<T> {
    return try {
        AppResult.Success(block())
    } catch (c: CancellationException) {
        throw c
    } catch (t: Throwable) {
        AppResult.Failure(errorMapper(t))
    }
}
