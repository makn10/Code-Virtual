package com.vcameraspace.feature.spaces

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vcameraspace.core.common.model.Space
import com.vcameraspace.core.designsystem.components.ConfirmationDialog
import com.vcameraspace.core.designsystem.components.CustomToolbar
import com.vcameraspace.core.designsystem.components.LoadingIndicator
import com.vcameraspace.core.designsystem.components.SectionHeader
import com.vcameraspace.core.designsystem.components.SettingSwitch
import com.vcameraspace.core.designsystem.components.StatusCard
import com.vcameraspace.core.designsystem.components.StatusSeverity
import com.vcameraspace.core.designsystem.theme.VCameraTheme

data class SpaceIconOption(
    val key: String,
    val label: String,
    val icon: ImageVector
)

val SpaceIconCatalog = listOf(
    SpaceIconOption("layers", "Layers", Icons.Default.Layers),
    SpaceIconOption("work", "Work", Icons.Default.Work),
    SpaceIconOption("camera", "Camera", Icons.Default.CameraAlt),
    SpaceIconOption("videocam", "Stream", Icons.Default.Videocam),
    SpaceIconOption("lock", "Private", Icons.Default.Lock),
    SpaceIconOption("games", "Gaming", Icons.Default.SportsEsports),
    SpaceIconOption("public", "Browser", Icons.Default.Public),
    SpaceIconOption("star", "Featured", Icons.Default.Star)
)

fun spaceIconForKey(key: String): ImageVector =
    SpaceIconCatalog.firstOrNull { it.key == key }?.icon ?: Icons.Default.Layers

@Composable
fun SpaceManagerScreen(
    viewModel: SpaceManagerViewModel,
    onBackClick: () -> Unit,
    onSpaceActivated: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.feedbackMessage, uiState.errorMessage) {
        val message = uiState.errorMessage ?: uiState.feedbackMessage
        if (!message.isNullOrBlank()) {
            snackbarHostState.showSnackbar(message)
            viewModel.clearMessages()
        }
    }

    SpaceManagerContent(
        uiState = uiState,
        snackbarHostState = snackbarHostState,
        onBackClick = onBackClick,
        onActivateSpace = { spaceId ->
            viewModel.activateSpace(spaceId)
            onSpaceActivated()
        },
        onCreateSpace = viewModel::createSpace,
        onRenameSpace = viewModel::renameSpace,
        onUpdateSpaceIcon = viewModel::updateSpaceIcon,
        onSetDefaultSpace = viewModel::setDefaultSpace,
        onMoveSpaceUp = viewModel::moveSpaceUp,
        onMoveSpaceDown = viewModel::moveSpaceDown,
        onUpdateSpaceSettings = viewModel::updateSpaceSettings,
        onDeleteSpace = viewModel::deleteSpace,
        modifier = modifier
    )
}

@Composable
fun SpaceManagerContent(
    uiState: SpaceManagerUiState,
    snackbarHostState: SnackbarHostState,
    onBackClick: () -> Unit,
    onActivateSpace: (String) -> Unit,
    onCreateSpace: (name: String, iconKey: String, setAsDefault: Boolean) -> Unit,
    onRenameSpace: (spaceId: String, newName: String) -> Unit,
    onUpdateSpaceIcon: (spaceId: String, iconKey: String) -> Unit,
    onSetDefaultSpace: (spaceId: String) -> Unit,
    onMoveSpaceUp: (spaceId: String) -> Unit,
    onMoveSpaceDown: (spaceId: String) -> Unit,
    onUpdateSpaceSettings: (spaceId: String, isolatedCamera: Boolean, requireConfirm: Boolean) -> Unit,
    onDeleteSpace: (spaceId: String) -> Unit,
    modifier: Modifier = Modifier
) {
    var showCreateDialog by remember { mutableStateOf(false) }
    var spaceToRename by remember { mutableStateOf<Space?>(null) }
    var spaceForIconPicker by remember { mutableStateOf<Space?>(null) }
    var spaceForSettings by remember { mutableStateOf<Space?>(null) }
    var spaceToDelete by remember { mutableStateOf<Space?>(null) }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("space_manager_screen"),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            CustomToolbar(
                title = "Space Manager",
                subtitle = "${uiState.spaces.size} active workspaces",
                onBackClick = onBackClick
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showCreateDialog = true },
                shape = CircleShape,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier
                    .size(58.dp)
                    .testTag("create_space_fab")
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Create new space"
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter
        ) {
            if (uiState.isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    LoadingIndicator(label = "Loading workspaces...")
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .widthIn(max = 680.dp)
                        .testTag("space_manager_list"),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = 12.dp,
                        bottom = 96.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item {
                        StatusCard(
                            title = "Scoped Workspace Isolation",
                            message = "Each space keeps its own database records, camera source preferences, and scoped config folders under files/spaces/. External system app storage remains owned by Android.",
                            severity = StatusSeverity.INFO,
                            icon = Icons.Default.Storage
                        )
                    }

                    item {
                        SectionHeader(
                            title = "Configured Spaces",
                            subtitle = "Reorder, customize icons, set default, or adjust space rules"
                        )
                    }

                    itemsIndexed(
                        items = uiState.spaces,
                        key = { _, space -> space.id }
                    ) { index, space ->
                        SpaceManagementCard(
                            space = space,
                            isActive = space.id == uiState.activeSpaceId,
                            canMoveUp = index > 0,
                            canMoveDown = index < uiState.spaces.lastIndex,
                            canDelete = uiState.spaces.size > 1,
                            onActivate = { onActivateSpace(space.id) },
                            onRename = { spaceToRename = space },
                            onPickIcon = { spaceForIconPicker = space },
                            onSetDefault = { onSetDefaultSpace(space.id) },
                            onMoveUp = { onMoveSpaceUp(space.id) },
                            onMoveDown = { onMoveSpaceDown(space.id) },
                            onConfigureSettings = { spaceForSettings = space },
                            onDelete = { spaceToDelete = space },
                            modifier = Modifier.animateItem()
                        )
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        CreateSpaceDialog(
            suggestedName = "Space ${uiState.spaces.size + 1}",
            onConfirm = { name, iconKey, setDefault ->
                showCreateDialog = false
                onCreateSpace(name, iconKey, setDefault)
            },
            onDismiss = { showCreateDialog = false }
        )
    }

    spaceToRename?.let { space ->
        RenameSpaceDialog(
            initialName = space.name,
            onConfirm = { newName ->
                spaceToRename = null
                onRenameSpace(space.id, newName)
            },
            onDismiss = { spaceToRename = null }
        )
    }

    spaceForIconPicker?.let { space ->
        SpaceIconPickerDialog(
            currentIconKey = space.iconKey,
            spaceName = space.name,
            onSelect = { key ->
                spaceForIconPicker = null
                onUpdateSpaceIcon(space.id, key)
            },
            onDismiss = { spaceForIconPicker = null }
        )
    }

    spaceForSettings?.let { space ->
        SpaceSettingsDialog(
            space = space,
            onSave = { isolatedCamera, requireConfirm ->
                spaceForSettings = null
                onUpdateSpaceSettings(space.id, isolatedCamera, requireConfirm)
            },
            onDismiss = { spaceForSettings = null }
        )
    }

    spaceToDelete?.let { space ->
        ConfirmationDialog(
            title = "Delete \"${space.name}\"?",
            message = "What WILL be deleted:\n" +
                "• VCamera Space records for \"${space.name}\" (${space.appCount} app entries)\n" +
                "• Per-space camera & location configurations\n" +
                "• Scoped directory files/spaces/${space.id}/ (${formatBytes(space.storageBytes)})\n\n" +
                "What CANNOT be deleted:\n" +
                "• Installed Android apps on your device and their Android system data (/data/data/).",
            confirmText = "Delete Space",
            isDestructive = true,
            icon = Icons.Default.WarningAmber,
            onConfirm = {
                spaceToDelete = null
                onDeleteSpace(space.id)
            },
            onDismiss = { spaceToDelete = null }
        )
    }
}

@Composable
private fun SpaceManagementCard(
    space: Space,
    isActive: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    canDelete: Boolean,
    onActivate: () -> Unit,
    onRename: () -> Unit,
    onPickIcon: () -> Unit,
    onSetDefault: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onConfigureSettings: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val extended = VCameraTheme.extendedColors

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("space_card_${space.id}"),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = if (isActive) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
        border = BorderStroke(
            width = if (isActive) 1.5.dp else 1.dp,
            color = if (isActive) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.outlineVariant
            }
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .clickable(onClick = onPickIcon)
                        .testTag("space_icon_button_${space.id}"),
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = spaceIconForKey(space.iconKey),
                            contentDescription = "Change icon for ${space.name}",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                Spacer(modifier = Modifier.width(14.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = space.name,
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold
                            ),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        if (space.isDefault) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = MaterialTheme.colorScheme.primary
                            ) {
                                Text(
                                    text = "DEFAULT",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = FontWeight.Bold
                                    ),
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                                )
                            }
                        }
                        if (isActive) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = extended.successContainer
                            ) {
                                Text(
                                    text = "ACTIVE",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = FontWeight.Bold
                                    ),
                                    color = extended.success,
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = "${space.appCount} apps • Scoped storage: ${formatBytes(space.storageBytes)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row {
                    IconButton(
                        onClick = onMoveUp,
                        enabled = canMoveUp,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("space_move_up_${space.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.ArrowUpward,
                            contentDescription = "Move ${space.name} up"
                        )
                    }
                    IconButton(
                        onClick = onMoveDown,
                        enabled = canMoveDown,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("space_move_down_${space.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.ArrowDownward,
                            contentDescription = "Move ${space.name} down"
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(
                        onClick = onRename,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("space_rename_${space.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "Rename ${space.name}"
                        )
                    }
                    IconButton(
                        onClick = onConfigureSettings,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("space_settings_${space.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Tune,
                            contentDescription = "Configure ${space.name}"
                        )
                    }
                    if (!space.isDefault) {
                        IconButton(
                            onClick = onSetDefault,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("space_set_default_${space.id}")
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = "Set ${space.name} as default"
                            )
                        }
                    }
                    if (canDelete) {
                        IconButton(
                            onClick = onDelete,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("space_delete_${space.id}")
                        ) {
                            Icon(
                                imageVector = Icons.Default.DeleteOutline,
                                contentDescription = "Delete ${space.name}",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }

                OutlinedButton(
                    onClick = onActivate,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.testTag("space_open_${space.id}")
                ) {
                    Text(if (isActive) "Open" else "Switch")
                }
            }
        }
    }
}

@Composable
private fun CreateSpaceDialog(
    suggestedName: String,
    onConfirm: (name: String, iconKey: String, setAsDefault: Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(suggestedName) }
    var selectedIcon by remember { mutableStateOf("layers") }
    var setDefault by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create Workspace") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Workspace name") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("create_space_name_input")
                )
                Text(
                    text = "Workspace Icon",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(SpaceIconCatalog, key = { it.key }) { option ->
                        val selected = option.key == selectedIcon
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    if (selected) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surfaceVariant
                                )
                                .border(
                                    width = if (selected) 2.dp else 0.dp,
                                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                    shape = RoundedCornerShape(12.dp)
                                )
                                .clickable { selectedIcon = option.key },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = option.icon,
                                contentDescription = option.label,
                                tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                SettingSwitch(
                    title = "Set as Default Space",
                    subtitle = "Open this workspace automatically on launch",
                    checked = setDefault,
                    onCheckedChange = { setDefault = it }
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name, selectedIcon, setDefault) },
                enabled = name.trim().isNotEmpty(),
                modifier = Modifier.testTag("confirm_create_space_button")
            ) {
                Text("Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun RenameSpaceDialog(
    initialName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(initialName) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename Workspace") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Workspace name") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("rename_space_input")
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name) },
                enabled = name.trim().isNotEmpty(),
                modifier = Modifier.testTag("confirm_rename_space_button")
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun SpaceIconPickerDialog(
    currentIconKey: String,
    spaceName: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose Icon for $spaceName") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SpaceIconCatalog.chunked(4).forEach { rowOptions ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        rowOptions.forEach { option ->
                            val selected = option.key == currentIconKey
                            Surface(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(58.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { onSelect(option.key) },
                                shape = RoundedCornerShape(12.dp),
                                color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                                border = if (selected) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Icon(
                                        imageVector = option.icon,
                                        contentDescription = option.label,
                                        tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = option.label,
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

@Composable
private fun SpaceSettingsDialog(
    space: Space,
    onSave: (isolatedCamera: Boolean, requireConfirm: Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var isolatedCamera by remember { mutableStateOf(space.isolatedCameraByDefault) }
    var requireConfirm by remember { mutableStateOf(space.requireLaunchConfirm) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${space.name} Settings") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SettingSwitch(
                    title = "Isolate Camera Config",
                    subtitle = "Keep virtual camera profiles separate from other spaces",
                    checked = isolatedCamera,
                    onCheckedChange = { isolatedCamera = it }
                )
                SettingSwitch(
                    title = "Confirm App Launch",
                    subtitle = "Prompt before launching supported apps in this space",
                    checked = requireConfirm,
                    onCheckedChange = { requireConfirm = it }
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(isolatedCamera, requireConfirm) },
                modifier = Modifier.testTag("save_space_settings_button")
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 KB"
    val kb = bytes / 1024.0
    if (kb < 1024.0) return String.format("%.1f KB", kb)
    val mb = kb / 1024.0
    return String.format("%.2f MB", mb)
}
