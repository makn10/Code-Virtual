package com.vcameraspace.feature.privacy

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vcameraspace.core.designsystem.components.CustomToolbar
import com.vcameraspace.core.designsystem.components.PrimaryButton
import com.vcameraspace.core.designsystem.components.SectionHeader
import com.vcameraspace.core.designsystem.components.StatusCard
import com.vcameraspace.core.designsystem.components.StatusSeverity
import com.vcameraspace.core.media.MediaInspector
import com.vcameraspace.core.permissions.PermissionStatus
import com.vcameraspace.core.permissions.RuntimePermissionManager
import com.vcameraspace.virtualengine.impl.ManagedVirtualProcessManager
import java.io.File

@Composable
fun PrivacyDashboardScreen(
    permissionManager: RuntimePermissionManager,
    mediaInspector: MediaInspector,
    processManager: ManagedVirtualProcessManager,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val snapshot = remember(context) {
        permissionManager.snapshot(mediaInspector.isNetworkAvailable())
    }
    val processes by processManager.observeAllActiveProcesses()
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val storageBytes = remember(context) { calculateAppStorageBytes(context) }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("privacy_dashboard_screen"),
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            CustomToolbar(
                title = "Privacy Dashboard",
                subtitle = "VCamera Space Permissions & Scope",
                onBackClick = onBackClick
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .widthIn(max = 680.dp),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                SectionHeader(
                    title = "Runtime Permissions",
                    subtitle = "Only permissions granted to VCamera Space are shown"
                )
            }
            item {
                PermissionStatusCard(
                    title = "Camera Permission",
                    status = snapshot.camera,
                    icon = Icons.Default.CameraAlt,
                    detail = "Used exclusively when Real Camera Device preview is active."
                )
            }
            item {
                PermissionStatusCard(
                    title = "Microphone Permission",
                    status = snapshot.microphone,
                    icon = Icons.Default.Mic,
                    detail = "Not required for local video audio playback; never injected into unrelated apps."
                )
            }
            item {
                PermissionStatusCard(
                    title = "Location Permission",
                    status = snapshot.location,
                    icon = Icons.Default.LocationOn,
                    detail = "Requested only if using device GPS inside Location Picker."
                )
            }
            item {
                PermissionStatusCard(
                    title = "Notifications (API 33+)",
                    status = snapshot.notifications,
                    icon = Icons.Default.Notifications,
                    detail = "Optional alerts for setup & diagnostic workflows."
                )
            }
            item {
                StatusCard(
                    title = "Media & Network Access",
                    message = "Media files use zero-permission Storage Access Framework & Photo Picker. Network state: ${if (snapshot.networkAvailable) "Online" else "Offline"}.",
                    severity = StatusSeverity.INFO,
                    icon = Icons.Default.NetworkCheck
                )
            }
            item {
                StatusCard(
                    title = "App Storage & Supported Processes",
                    message = "Scoped app storage used: ${storageBytes / 1024} KB • Active supported process records: ${processes.count { it.isRunning }}",
                    severity = StatusSeverity.INFO,
                    icon = Icons.Default.Folder
                )
            }
            item {
                PrimaryButton(
                    text = "Open Android App Settings",
                    icon = Icons.Default.Settings,
                    onClick = { permissionManager.openAppSettings() },
                    modifier = Modifier.fillMaxWidth(),
                    testTag = "privacy_open_settings_button"
                )
            }
        }
    }
}

@Composable
private fun PermissionStatusCard(
    title: String,
    status: PermissionStatus,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    detail: String
) {
    val label = when (status) {
        PermissionStatus.GRANTED -> "Granted"
        PermissionStatus.DENIED -> "Not Granted"
        PermissionStatus.NOT_APPLICABLE -> "Not Required on This Android Version"
    }
    val severity = when (status) {
        PermissionStatus.GRANTED -> StatusSeverity.SUCCESS
        PermissionStatus.DENIED -> StatusSeverity.INFO
        PermissionStatus.NOT_APPLICABLE -> StatusSeverity.INFO
    }
    StatusCard(
        title = "$title — $label",
        message = detail,
        severity = severity,
        icon = icon
    )
}

private fun calculateAppStorageBytes(context: Context): Long {
    fun dirBytes(dir: File): Long =
        if (!dir.exists()) 0L else dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    return dirBytes(context.filesDir) + dirBytes(context.cacheDir)
}
