package com.vcameraspace.feature.clone

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vcameraspace.core.common.model.SpaceApp
import com.vcameraspace.core.designsystem.components.ConfirmationDialog
import com.vcameraspace.core.designsystem.components.CustomToolbar
import com.vcameraspace.core.designsystem.components.EmptyState
import com.vcameraspace.core.designsystem.components.PreLaunchVirtualCameraDialog
import com.vcameraspace.core.designsystem.components.PrimaryButton
import com.vcameraspace.core.designsystem.components.StatusCard
import com.vcameraspace.core.designsystem.components.StatusSeverity

@Composable
fun VirtualAppsScreen(
    viewModel: VirtualAppsViewModel,
    onBackClick: () -> Unit,
    onOpenCloneApp: (spaceId: String) -> Unit,
    onOpenAppInfo: (spaceId: String, packageName: String) -> Unit,
    onOpenAppCameraSettings: (spaceId: String, packageName: String) -> Unit,
    onOpenBuiltInVirtualCamera: ((spaceId: String, packageName: String?) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var pendingRemove by remember { mutableStateOf<SpaceApp?>(null) }
    var pendingClone by remember { mutableStateOf<SpaceApp?>(null) }

    LaunchedEffect(uiState.feedbackMessage) {
        val msg = uiState.feedbackMessage
        if (!msg.isNullOrBlank()) {
            snackbarHostState.showSnackbar(msg)
            viewModel.clearFeedback()
        }
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("virtual_apps_screen"),
        contentWindowInsets = WindowInsets.safeDrawing,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            CustomToolbar(
                title = "Virtual Applications",
                subtitle = "${uiState.entries.size} space instances configured",
                onBackClick = onBackClick,
                actions = {
                    val activeFilterSpace = uiState.selectedSpaceFilter ?: uiState.spaces.firstOrNull()?.id ?: "space_1"
                    TextButton(onClick = { onOpenCloneApp(activeFilterSpace) }) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Add")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .widthIn(max = 680.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = uiState.selectedSpaceFilter == null,
                    onClick = { viewModel.selectSpaceFilter(null) },
                    label = { Text("All Spaces (${uiState.entries.size})") }
                )
                uiState.spaces.forEach { space ->
                    FilterChip(
                        selected = uiState.selectedSpaceFilter == space.id,
                        onClick = { viewModel.selectSpaceFilter(space.id) },
                        label = { Text("${space.name} (${space.appCount})") }
                    )
                }
            }

            if (uiState.filteredEntries.isEmpty()) {
                EmptyState(
                    title = "No Virtual Apps Here",
                    subtitle = "Add visible applications to a workspace to launch instances, assign spaces, clear scoped data, and bind virtual camera settings.",
                    actionLabel = "Add App to Workspace",
                    onActionClick = {
                        onOpenCloneApp(uiState.selectedSpaceFilter ?: uiState.spaces.firstOrNull()?.id ?: "space_1")
                    }
                )
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item {
                        StatusCard(
                            title = "Space Instances & Shared Virtual Camera",
                            message = "Each workspace maintains independent scoped directories and optional camera overrides. Apps without overrides inherit the Shared Virtual Camera Source.",
                            severity = StatusSeverity.INFO
                        )
                    }
                    items(uiState.filteredEntries, key = { it.app.id }) { entry ->
                        VirtualAppInstanceCard(
                            entry = entry,
                            onLaunch = {
                                viewModel.requestLaunchApp(entry.app) { sId, pkg ->
                                    onOpenBuiltInVirtualCamera?.invoke(sId, pkg)
                                        ?: onOpenAppCameraSettings(sId, pkg ?: entry.app.packageName)
                                }
                            },
                            onStop = { viewModel.stopApp(entry.app) },
                            onOpenDetails = { onOpenAppInfo(entry.app.spaceId, entry.app.packageName) },
                            onOpenCamera = { onOpenAppCameraSettings(entry.app.spaceId, entry.app.packageName) },
                            onCloneToSpace = { pendingClone = entry.app },
                            onClearData = { viewModel.clearData(entry.app) },
                            onRemove = { pendingRemove = entry.app }
                        )
                    }
                }
            }
        }
    }

    pendingRemove?.let { app ->
        ConfirmationDialog(
            title = "Uninstall from ${app.spaceId}?",
            message = "Remove ${app.label} (${app.packageName}) and clear its scoped workspace configuration?",
            confirmText = "Remove",
            isDestructive = true,
            icon = Icons.Default.DeleteOutline,
            onConfirm = {
                pendingRemove = null
                viewModel.removeFromSpace(app)
            },
            onDismiss = { pendingRemove = null }
        )
    }

    pendingClone?.let { app ->
        AlertDialog(
            onDismissRequest = { pendingClone = null },
            title = { Text("Create Instance in Another Space") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Copy ${app.label} registration to an independent workspace:")
                    uiState.spaces.filter { it.id != app.spaceId }.forEach { space ->
                        OutlinedButton(
                            onClick = {
                                pendingClone = null
                                viewModel.cloneInstanceToSpace(app, space.id)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(space.name)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { pendingClone = null }) { Text("Close") }
            }
        )
    }

    uiState.pendingPreLaunchPrompt?.let { prompt ->
        PreLaunchVirtualCameraDialog(
            appName = prompt.app.label,
            activeSourceLabel = prompt.activeSourceLabel,
            onOpenBuiltInVirtualCamera = {
                viewModel.dismissPreLaunchPrompt()
                onOpenBuiltInVirtualCamera?.invoke(prompt.app.spaceId, prompt.app.packageName)
                    ?: onOpenAppCameraSettings(prompt.app.spaceId, prompt.app.packageName)
            },
            onConfigureSource = {
                viewModel.dismissPreLaunchPrompt()
                onOpenAppCameraSettings(prompt.app.spaceId, prompt.app.packageName)
            },
            onOpenExternalAppAnyway = {
                viewModel.launchApp(prompt.app)
            },
            onDismiss = viewModel::dismissPreLaunchPrompt,
            icon = Icons.Default.Videocam
        )
    }
}

@Composable
private fun VirtualAppInstanceCard(
    entry: VirtualAppEntry,
    onLaunch: () -> Unit,
    onStop: () -> Unit,
    onOpenDetails: () -> Unit,
    onOpenCamera: () -> Unit,
    onCloneToSpace: () -> Unit,
    onClearData: () -> Unit,
    onRemove: () -> Unit
) {
    val isRunning = entry.processState?.isRunning == true
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("virtual_app_card_${entry.app.id}")
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = entry.app.label,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                    )
                    Text(
                        text = "${entry.app.packageName} • v${entry.app.versionName}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = "${entry.spaceName}${if (isRunning) " • RUNNING" else ""}",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isRunning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Text(
                text = "Camera Source: ${entry.cameraModeLabel} • Architecture: ${entry.app.architecture.name}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryButton(
                    text = "Launch",
                    icon = Icons.AutoMirrored.Filled.OpenInNew,
                    onClick = onLaunch,
                    modifier = Modifier.weight(1f)
                )
                if (isRunning) {
                    OutlinedButton(
                        onClick = onStop,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Stop")
                    }
                } else {
                    OutlinedButton(
                        onClick = onOpenCamera,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Videocam, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Camera")
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onOpenDetails,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Details")
                }
                OutlinedButton(
                    onClick = onCloneToSpace,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Multi-Space")
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onClearData,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.CleaningServices, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Clear Data")
                }
                OutlinedButton(
                    onClick = onRemove,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        Icons.Default.DeleteOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Remove", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}
