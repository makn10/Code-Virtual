package com.vcameraspace.feature.advance

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.DeveloperMode
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vcameraspace.core.designsystem.components.CustomToolbar
import com.vcameraspace.core.designsystem.components.PrimaryButton
import com.vcameraspace.core.designsystem.components.SaveButton
import com.vcameraspace.core.designsystem.components.SearchBar
import com.vcameraspace.core.designsystem.components.SectionHeader
import com.vcameraspace.core.designsystem.components.SettingSwitch
import com.vcameraspace.core.designsystem.components.StatusCard
import com.vcameraspace.core.designsystem.components.StatusSeverity
import com.vcameraspace.core.permissions.RuntimePermissionManager

@Composable
fun AdvanceFeatureScreen(
    viewModel: AdvanceFeatureViewModel,
    permissionManager: RuntimePermissionManager,
    onBackClick: () -> Unit,
    onOpenLocationManager: (spaceId: String) -> Unit,
    onOpenPrivacyDashboard: () -> Unit,
    onOpenPerformanceMonitor: () -> Unit,
    onOpenBackupRestore: () -> Unit,
    onOpenGeneralSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("advance_feature_screen"),
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            CustomToolbar(
                title = "Advance Feature",
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
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                SectionHeader(
                    title = "Location Setting",
                    subtitle = "Supported testing environment configuration"
                )
            }

            item {
                SettingSwitch(
                    title = "Enable Mock Location",
                    subtitle = if (uiState.mockLocationEnabled) "Enabled for supported testing" else "Disabled by default",
                    checked = uiState.mockLocationEnabled,
                    onCheckedChange = viewModel::setMockLocationEnabled,
                    leadingIcon = Icons.Default.LocationOn,
                    testTag = "enable_mock_location_switch"
                )
            }

            item {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.medium)
                        .clickable(enabled = uiState.mockLocationEnabled) {
                            onOpenLocationManager(uiState.activeSpaceId)
                        }
                        .testTag("location_manager_entry"),
                    shape = MaterialTheme.shapes.medium,
                    color = if (uiState.mockLocationEnabled) {
                        MaterialTheme.colorScheme.surface
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                    },
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.LocationOn,
                            contentDescription = null,
                            tint = if (uiState.mockLocationEnabled) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 14.dp)
                        ) {
                            Text(
                                text = "Location Manager",
                                style = MaterialTheme.typography.titleSmall,
                                color = if (uiState.mockLocationEnabled) {
                                    MaterialTheme.colorScheme.onSurface
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                            Text(
                                text = "Go to set Location for Apps",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            item {
                StatusCard(
                    title = "Developer Options Requirement",
                    message = if (uiState.isDeveloperMockReady) {
                        "VCamera Space is permitted as an Android mock location testing app. Saved coordinates are scoped to supported testing workflows and never change system location silently."
                    } else {
                        "Android requires selecting VCamera Space under Settings > Developer options > Select mock location app to publish test provider coordinates. Location Manager still lets you store per-space app test coordinates safely."
                    },
                    severity = if (uiState.isDeveloperMockReady) StatusSeverity.SUCCESS else StatusSeverity.INFO,
                    icon = Icons.Default.DeveloperMode,
                    action = {
                        OutlinedButton(onClick = { permissionManager.openDeveloperOptions() }) {
                            Text("Open Developer Options")
                        }
                    }
                )
            }

            item {
                Spacer(modifier = Modifier.height(6.dp))
                SectionHeader(
                    title = "Workspace Diagnostics & Tools",
                    subtitle = "Privacy, performance, backup, and preferences"
                )
            }

            item {
                ToolLinkRow(
                    title = "Privacy Dashboard",
                    subtitle = "Inspect permissions, storage usage & active processes",
                    icon = Icons.Default.PrivacyTip,
                    onClick = onOpenPrivacyDashboard,
                    testTag = "open_privacy_dashboard_item"
                )
            }
            item {
                ToolLinkRow(
                    title = "Performance Monitor",
                    subtitle = "Memory, storage, spaces, and engine health",
                    icon = Icons.Default.Speed,
                    onClick = onOpenPerformanceMonitor,
                    testTag = "open_performance_monitor_item"
                )
            }
            item {
                ToolLinkRow(
                    title = "Backup and Restore",
                    subtitle = "Export or import versioned workspace configurations",
                    icon = Icons.Default.Backup,
                    onClick = onOpenBackupRestore,
                    testTag = "open_backup_restore_item"
                )
            }
            item {
                ToolLinkRow(
                    title = "General Settings",
                    subtitle = "Theme mode, default space, and workspace options",
                    icon = Icons.Default.Settings,
                    onClick = onOpenGeneralSettings,
                    testTag = "open_general_settings_item"
                )
            }
        }
    }
}

@Composable
private fun ToolLinkRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit,
    testTag: String
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .testTag(testTag),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 14.dp)
            ) {
                Text(text = title, style = MaterialTheme.typography.titleSmall)
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun LocationManagerScreen(
    viewModel: LocationManagerViewModel,
    onBackClick: () -> Unit,
    onConfigureAppLocation: (packageName: String) -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("location_manager_screen"),
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            CustomToolbar(
                title = "Location Manager",
                subtitle = "Space: ${uiState.spaceId}",
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
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                val spaceDefaultLoc = uiState.locationConfigs["__space_default__"]
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Workspace Default Test Location",
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            text = if (spaceDefaultLoc != null) {
                                "${spaceDefaultLoc.locationLabel ?: ""} (${spaceDefaultLoc.latitude}, ${spaceDefaultLoc.longitude})"
                            } else {
                                "No default test coordinates saved for ${uiState.spaceId}"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        PrimaryButton(
                            text = "Configure Location",
                            icon = Icons.Default.LocationOn,
                            onClick = { onConfigureAppLocation("__space_default__") },
                            testTag = "configure_space_default_location"
                        )
                    }
                }
            }

            item {
                SectionHeader(
                    title = "Workspace Applications",
                    subtitle = "Assign per-app test coordinates inside ${uiState.spaceId}"
                )
            }

            if (uiState.apps.isEmpty()) {
                item {
                    StatusCard(
                        title = "No Apps in This Space",
                        message = "Clone an application from Home to assign app-specific location profiles, or configure the workspace default location above.",
                        severity = StatusSeverity.INFO
                    )
                }
            } else {
                items(uiState.apps, key = { it.id }) { app ->
                    val cfg = uiState.locationConfigs[app.packageName]
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(text = app.label, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    text = app.packageName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (cfg != null) {
                                    Text(
                                        text = "Saved: ${cfg.latitude}, ${cfg.longitude}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                            OutlinedButton(
                                onClick = { onConfigureAppLocation(app.packageName) },
                                modifier = Modifier.testTag("configure_loc_${app.packageName}")
                            ) {
                                Text("Configure Location")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun MapPickerScreen(
    viewModel: MapPickerViewModel,
    onBackClick: () -> Unit,
    onSaved: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

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
            .testTag("map_picker_screen"),
        contentWindowInsets = WindowInsets.safeDrawing,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            CustomToolbar(
                title = "Location Picker",
                subtitle = uiState.packageName,
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
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SearchBar(
                        query = uiState.searchQuery,
                        onQueryChange = viewModel::onSearchQueryChange,
                        placeholder = "Search city or 'lat, lng'...",
                        modifier = Modifier.weight(1f),
                        testTag = "map_search_bar"
                    )
                    PrimaryButton(
                        text = "Find",
                        icon = Icons.Default.Search,
                        onClick = viewModel::searchLocation,
                        testTag = "map_search_button"
                    )
                }
            }

            item {
                InteractiveCoordinateMap(
                    latitude = uiState.latitude,
                    longitude = uiState.longitude,
                    onDropPin = { lat, lng -> viewModel.onPinSelected(lat, lng) }
                )
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = uiState.latitudeInput,
                        onValueChange = { viewModel.onCoordinateInputsChange(it, uiState.longitudeInput) },
                        label = { Text("Latitude") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = uiState.longitudeInput,
                        onValueChange = { viewModel.onCoordinateInputsChange(uiState.latitudeInput, it) },
                        label = { Text("Longitude") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            item {
                StatusCard(
                    title = "Current Selected Location",
                    message = "${uiState.selectedLocationLabel}\nLat: ${uiState.latitude}, Lng: ${uiState.longitude}",
                    severity = StatusSeverity.INFO,
                    icon = Icons.Default.LocationOn
                )
            }

            item {
                SaveButton(
                    text = "Save",
                    onClick = { viewModel.saveLocation(onSaved) },
                    modifier = Modifier.fillMaxWidth(),
                    testTag = "map_picker_save_button"
                )
            }
        }
    }
}

@Composable
private fun InteractiveCoordinateMap(
    latitude: Double,
    longitude: Double,
    onDropPin: (Double, Double) -> Unit
) {
    val gridColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
    val bgColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
    val pinColor = MaterialTheme.colorScheme.primary

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp)
            .clip(RoundedCornerShape(18.dp))
            .testTag("interactive_coordinate_map"),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Box {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures { offset ->
                            val normX = (offset.x / size.width).coerceIn(0f, 1f)
                            val normY = (offset.y / size.height).coerceIn(0f, 1f)
                            val lng = (normX * 360f) - 180f
                            val lat = 85f - (normY * 170f)
                            onDropPin(lat.toDouble(), lng.toDouble())
                        }
                    }
            ) {
                drawRect(color = bgColor)
                for (i in 1..7) {
                    val x = size.width * i / 8f
                    drawLine(gridColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1.5f)
                }
                for (j in 1..5) {
                    val y = size.height * j / 6f
                    drawLine(gridColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.5f)
                }
                // Equator & Prime Meridian
                drawLine(
                    pinColor.copy(alpha = 0.45f),
                    Offset(0f, size.height / 2f),
                    Offset(size.width, size.height / 2f),
                    strokeWidth = 2.5f
                )
                drawLine(
                    pinColor.copy(alpha = 0.45f),
                    Offset(size.width / 2f, 0f),
                    Offset(size.width / 2f, size.height),
                    strokeWidth = 2.5f
                )

                val pinX = (((longitude + 180.0) / 360.0) * size.width).toFloat().coerceIn(0f, size.width)
                val pinY = (((85.0 - latitude) / 170.0) * size.height).toFloat().coerceIn(0f, size.height)
                drawCircle(color = pinColor.copy(alpha = 0.25f), radius = 26f, center = Offset(pinX, pinY))
                drawCircle(color = pinColor, radius = 12f, center = Offset(pinX, pinY))
                drawCircle(color = Color.White, radius = 5f, center = Offset(pinX, pinY))
            }
            Text(
                text = "Tap anywhere on map grid to drop pin",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(10.dp)
            )
        }
    }
}
