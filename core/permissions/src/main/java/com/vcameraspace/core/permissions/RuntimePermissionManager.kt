package com.vcameraspace.core.permissions

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat

enum class PermissionStatus {
    GRANTED,
    DENIED,
    NOT_APPLICABLE
}

data class PermissionSnapshot(
    val camera: PermissionStatus,
    val microphone: PermissionStatus,
    val location: PermissionStatus,
    val notifications: PermissionStatus,
    val networkAvailable: Boolean
)

class RuntimePermissionManager(
    private val context: Context
) {
    fun isGranted(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    fun snapshot(networkAvailable: Boolean): PermissionSnapshot {
        val cameraStatus = if (isGranted(Manifest.permission.CAMERA)) {
            PermissionStatus.GRANTED
        } else {
            PermissionStatus.DENIED
        }

        val micStatus = if (isGranted(Manifest.permission.RECORD_AUDIO)) {
            PermissionStatus.GRANTED
        } else {
            PermissionStatus.DENIED
        }

        val locationStatus = if (
            isGranted(Manifest.permission.ACCESS_FINE_LOCATION) ||
            isGranted(Manifest.permission.ACCESS_COARSE_LOCATION)
        ) {
            PermissionStatus.GRANTED
        } else {
            PermissionStatus.DENIED
        }

        val notifStatus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (isGranted(Manifest.permission.POST_NOTIFICATIONS)) {
                PermissionStatus.GRANTED
            } else {
                PermissionStatus.DENIED
            }
        } else {
            PermissionStatus.NOT_APPLICABLE
        }

        return PermissionSnapshot(
            camera = cameraStatus,
            microphone = micStatus,
            location = locationStatus,
            notifications = notifStatus,
            networkAvailable = networkAvailable
        )
    }

    fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    fun openDeveloperOptions() {
        val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
            .onFailure { openAppSettings() }
    }
}
