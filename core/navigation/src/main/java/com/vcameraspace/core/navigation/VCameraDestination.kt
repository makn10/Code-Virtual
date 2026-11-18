package com.vcameraspace.core.navigation

import kotlinx.serialization.Serializable

/**
 * Type-safe Navigation Compose destinations covering the full VCamera Space screen map.
 */
sealed interface VCameraDestination {
    @Serializable
    data object Splash : VCameraDestination

    @Serializable
    data object Onboarding : VCameraDestination

    @Serializable
    data object Home : VCameraDestination

    @Serializable
    data object SpaceManager : VCameraDestination

    @Serializable
    data class CloneApp(val spaceId: String) : VCameraDestination

    @Serializable
    data class ApkImport(val spaceId: String) : VCameraDestination

    @Serializable
    data class InstallationProgress(val spaceId: String, val packageName: String) : VCameraDestination

    @Serializable
    data class VirtualCameraSetting(
        val spaceId: String,
        val packageName: String? = null
    ) : VCameraDestination

    @Serializable
    data class CameraPreview(
        val spaceId: String,
        val packageName: String? = null
    ) : VCameraDestination

    @Serializable
    data class VirtualCameraViewfinder(
        val spaceId: String,
        val packageName: String? = null
    ) : VCameraDestination

    @Serializable
    data class CameraLab(
        val spaceId: String,
        val packageName: String? = null
    ) : VCameraDestination

    @Serializable
    data object AdvanceFeature : VCameraDestination

    @Serializable
    data class LocationManager(val spaceId: String) : VCameraDestination

    @Serializable
    data class MapPicker(
        val spaceId: String,
        val packageName: String
    ) : VCameraDestination

    @Serializable
    data object Premium : VCameraDestination

    @Serializable
    data object GoogleFramework : VCameraDestination

    @Serializable
    data class AppInformation(
        val spaceId: String,
        val packageName: String
    ) : VCameraDestination

    @Serializable
    data object PrivacyDashboard : VCameraDestination

    @Serializable
    data object PerformanceMonitor : VCameraDestination

    @Serializable
    data object BackupRestore : VCameraDestination

    @Serializable
    data object GeneralSettings : VCameraDestination

    @Serializable
    data object About : VCameraDestination

    @Serializable
    data object ImportedApps : VCameraDestination

    @Serializable
    data object VirtualApps : VCameraDestination

    @Serializable
    data object CompatibilityStatus : VCameraDestination

    @Serializable
    data class PhotoSourceSelect(
        val spaceId: String = "space_1",
        val packageName: String? = null
    ) : VCameraDestination

    @Serializable
    data class VideoSourceSelect(
        val spaceId: String = "space_1",
        val packageName: String? = null
    ) : VCameraDestination

    @Serializable
    data object DiagnosticLogs : VCameraDestination
}

