package com.vcameraspace.feature.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vcameraspace.core.common.model.ExecutionArchitecture
import com.vcameraspace.core.common.model.Space
import com.vcameraspace.core.common.model.SpaceApp
import com.vcameraspace.core.designsystem.components.AppIconCard
import com.vcameraspace.core.designsystem.components.ConfirmationDialog
import com.vcameraspace.core.designsystem.components.EmptyState
import com.vcameraspace.core.designsystem.components.SpaceIndicator
import com.vcameraspace.core.designsystem.components.StatusCard
import com.vcameraspace.core.designsystem.components.StatusSeverity
import com.vcameraspace.core.designsystem.components.VCameraLogo
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    emptyIllustration: Painter,
    onOpenCloneApp: (spaceId: String) -> Unit,
    onOpenSpaceManager: () -> Unit,
    onNavigateMenuAction: (HomeOverflowAction, spaceId: String) -> Unit,
    onOpenAppInfo: (spaceId: String, packageName: String) -> Unit,
    onOpenAppCameraSettings: (spaceId: String, packageName: String) -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.statusBannerMessage) {
        val message = uiState.statusBannerMessage
        if (!message.isNullOrBlank()) {
            snackbarHostState.showSnackbar(message)
            viewModel.dismissBanner()
        }
    }

    HomeContent(
        uiState = uiState,
        emptyIllustration = emptyIllustration,
        snackbarHostState = snackbarHostState,
        onPageChanged = viewModel::onPageSelected,
        onOpenCloneApp = onOpenCloneApp,
        onOpenSpaceManager = onOpenSpaceManager,
        onMenuAction = { action ->
            when (action) {
                HomeOverflowAction.KILL_ALL_APPS -> viewModel.killAllSupportedProcesses()
                HomeOverflowAction.SWITCH_TO_NORMAL -> viewModel.toggleNormalMode()
                else -> onNavigateMenuAction(action, uiState.activeSpace.id)
            }
        },
        onAppAction = { app, action ->
            when (action) {
                AppLongPressAction.APP_INFORMATION -> onOpenAppInfo(app.spaceId, app.packageName)
                AppLongPressAction.CAMERA_SETTINGS -> onOpenAppCameraSettings(app.spaceId, app.packageName)
                else -> viewModel.handleAppAction(app, action)
            }
        },
        modifier = modifier
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeContent(
    uiState: HomeUiState,
    emptyIllustration: Painter,
    snackbarHostState: SnackbarHostState,
    onPageChanged: (Int) -> Unit,
    onOpenCloneApp: (spaceId: String) -> Unit,
    onOpenSpaceManager: () -> Unit,
    onMenuAction: (HomeOverflowAction) -> Unit,
    onAppAction: (SpaceApp, AppLongPressAction) -> Unit,
    modifier: Modifier = Modifier
) {
    val pagerState = rememberPagerState(
        initialPage = uiState.activeSpaceIndex,
        pageCount = { uiState.spaces.size }
    )
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(uiState.activeSpaceIndex, uiState.spaces.size) {
        val target = uiState.activeSpaceIndex.coerceIn(0, (uiState.spaces.size - 1).coerceAtLeast(0))
        if (pagerState.currentPage != target && uiState.spaces.isNotEmpty()) {
            pagerState.animateScrollToPage(target)
        }
    }

    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }
            .distinctUntilChanged()
            .collect { page -> onPageChanged(page) }
    }

    var overflowExpanded by remember { mutableStateOf(false) }
    var showKillAllConfirm by remember { mutableStateOf(false) }
    var selectedAppForMenu by remember { mutableStateOf<SpaceApp?>(null) }
    var pendingDestructiveAppAction by remember { mutableStateOf<Pair<SpaceApp, AppLongPressAction>?>(null) }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("home_screen"),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                ),
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        VCameraLogo(
                            size = 34.dp,
                            contentDescription = "VCamera Space logo"
                        )
                        Column {
                            Text(
                                text = "VCamera",
                                style = MaterialTheme.typography.titleLarge.copy(
                                    fontWeight = FontWeight.Bold
                                )
                            )
                        }
                        Spacer(modifier = Modifier.width(2.dp))
                        Surface(
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .clickable { onOpenSpaceManager() }
                                .testTag("home_current_space_badge"),
                            shape = RoundedCornerShape(999.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            border = BorderStroke(
                                width = 1.dp,
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
                            )
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Layers,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    text = uiState.activeSpace.name,
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        fontWeight = FontWeight.SemiBold
                                    ),
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                    }
                },
                actions = {
                    IconButton(
                        onClick = onOpenSpaceManager,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("home_manage_spaces_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Layers,
                            contentDescription = "Manage spaces"
                        )
                    }
                    Box {
                        IconButton(
                            onClick = { overflowExpanded = true },
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("home_overflow_menu_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = "More options"
                            )
                        }
                        DropdownMenu(
                            expanded = overflowExpanded,
                            onDismissRequest = { overflowExpanded = false },
                            modifier = Modifier.testTag("home_overflow_dropdown")
                        ) {
                            HomeOverflowAction.entries.forEachIndexed { index, action ->
                                val label = if (action == HomeOverflowAction.SWITCH_TO_NORMAL && uiState.normalModeEnabled) {
                                    "Switch to Space Mode"
                                } else {
                                    action.label
                                }
                                if (index == 4 || index == 5 || index == 10) {
                                    HorizontalDivider()
                                }
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = label,
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = overflowIconFor(action),
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    },
                                    onClick = {
                                        overflowExpanded = false
                                        if (action == HomeOverflowAction.KILL_ALL_APPS) {
                                            showKillAllConfirm = true
                                        } else {
                                            onMenuAction(action)
                                        }
                                    },
                                    modifier = Modifier.testTag("home_menu_${action.name.lowercase()}")
                                )
                            }
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { onOpenCloneApp(uiState.activeSpace.id) },
                shape = CircleShape,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 4.dp),
                modifier = Modifier
                    .size(60.dp)
                    .testTag("home_clone_fab")
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Clone or add application",
                    modifier = Modifier.size(28.dp)
                )
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            AnimatedVisibility(visible = uiState.normalModeEnabled) {
                Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    StatusCard(
                        title = "Normal Mode Active",
                        message = "Virtual workspace actions are paused. Switch back to Space Mode from the menu whenever needed.",
                        severity = StatusSeverity.WARNING,
                        icon = Icons.Default.SwapHoriz,
                        action = {
                            TextButton(
                                onClick = { onMenuAction(HomeOverflowAction.SWITCH_TO_NORMAL) },
                                modifier = Modifier.testTag("resume_space_mode_button")
                            ) {
                                Text("Return to Space Mode")
                            }
                        }
                    )
                }
            }

            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .testTag("home_space_pager")
            ) { pageIndex ->
                val space = uiState.spaces[pageIndex]
                val spaceApps = uiState.appsBySpaceId[space.id].orEmpty()

                SpacePageContent(
                    space = space,
                    apps = spaceApps,
                    emptyIllustration = emptyIllustration,
                    onOpenCloneApp = { onOpenCloneApp(space.id) },
                    onAppClick = { app ->
                        if (app.architecture != ExecutionArchitecture.UNSUPPORTED) {
                            onAppAction(app, AppLongPressAction.OPEN)
                        }
                    },
                    onAppLongClick = { app ->
                        selectedAppForMenu = app
                    }
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(bottom = 18.dp, top = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    SpaceIndicator(
                        pageCount = uiState.spaces.size,
                        currentPage = pagerState.currentPage,
                        onPageClick = { targetPage ->
                            coroutineScope.launch { pagerState.animateScrollToPage(targetPage) }
                        }
                    )
                    Text(
                        text = "${uiState.activeSpace.name} • Swipe to switch space",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    if (showKillAllConfirm) {
        ConfirmationDialog(
            title = "Kill All Supported Apps?",
            message = "This will stop all active supported processes and release media/camera preview resources managed by VCamera Space.",
            confirmText = "Kill All",
            isDestructive = true,
            icon = Icons.Default.WarningAmber,
            onConfirm = {
                showKillAllConfirm = false
                onMenuAction(HomeOverflowAction.KILL_ALL_APPS)
            },
            onDismiss = { showKillAllConfirm = false }
        )
    }

    selectedAppForMenu?.let { app ->
        AppActionsBottomDialog(
            app = app,
            onDismiss = { selectedAppForMenu = null },
            onActionSelected = { action ->
                selectedAppForMenu = null
                if (action.isDestructive) {
                    pendingDestructiveAppAction = app to action
                } else {
                    onAppAction(app, action)
                }
            }
        )
    }

    pendingDestructiveAppAction?.let { (app, action) ->
        ConfirmationDialog(
            title = "${action.label} ${app.label}?",
            message = when (action) {
                AppLongPressAction.REMOVE -> "Remove ${app.label} and its workspace configuration from ${uiState.activeSpace.name}?"
                AppLongPressAction.CLEAR_DATA -> "Clear VCamera Space scoped config and camera cache for ${app.label}? External app system storage is not modified."
                AppLongPressAction.KILL_APP -> "Stop the supported process record for ${app.label}?"
                else -> "Proceed with ${action.label}?"
            },
            confirmText = action.label,
            isDestructive = true,
            icon = Icons.Default.DeleteOutline,
            onConfirm = {
                pendingDestructiveAppAction = null
                onAppAction(app, action)
            },
            onDismiss = { pendingDestructiveAppAction = null }
        )
    }
}

@Composable
private fun SpacePageContent(
    space: Space,
    apps: List<SpaceApp>,
    emptyIllustration: Painter,
    onOpenCloneApp: () -> Unit,
    onAppClick: (SpaceApp) -> Unit,
    onAppLongClick: (SpaceApp) -> Unit
) {
    if (apps.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .testTag("home_empty_state_${space.id}"),
            contentAlignment = Alignment.Center
        ) {
            EmptyState(
                title = "Empty",
                subtitle = "${space.name} has no applications yet. Tap + to add supported apps.",
                illustrationPainter = emptyIllustration,
                illustrationContentDescription = "Serene landscape with bird, trees, and clouds",
                actionLabel = "Clone App",
                onActionClick = onOpenCloneApp
            )
        }
    } else {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val columns = when {
                maxWidth >= 840.dp -> 6
                maxWidth >= 600.dp -> 5
                else -> 4
            }
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(apps, key = { it.id }) { app ->
                    AppIconCard(
                        appName = app.label,
                        hasCustomCamera = app.hasCustomCameraConfig,
                        onClick = { onAppClick(app) },
                        onLongClick = { onAppLongClick(app) },
                        testTag = "home_app_card_${app.packageName}"
                    )
                }
            }
        }
    }
}

@Composable
private fun AppActionsBottomDialog(
    app: SpaceApp,
    onDismiss: () -> Unit,
    onActionSelected: (AppLongPressAction) -> Unit
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = app.label,
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                AppLongPressAction.entries.forEach { action ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.small)
                            .clickable { onActionSelected(action) },
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                    ) {
                        Text(
                            text = action.label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (action.isDestructive) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)
                        )
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

private fun overflowIconFor(action: HomeOverflowAction): ImageVector = when (action) {
    HomeOverflowAction.PREMIUM -> Icons.Default.Star
    HomeOverflowAction.ADVANCE_FEATURE -> Icons.Default.Tune
    HomeOverflowAction.VIRTUAL_CAMERA_SETTING -> Icons.Default.Videocam
    HomeOverflowAction.GOOGLE_FRAMEWORK -> Icons.Default.Shield
    HomeOverflowAction.KILL_ALL_APPS -> Icons.Default.PowerSettingsNew
    HomeOverflowAction.RATE_US -> Icons.Default.Star
    HomeOverflowAction.ABOUT -> Icons.Default.Info
    HomeOverflowAction.GITHUB -> Icons.Default.Code
    HomeOverflowAction.SHARE -> Icons.Default.Share
    HomeOverflowAction.CONTACT_US -> Icons.Default.Email
    HomeOverflowAction.SWITCH_TO_NORMAL -> Icons.Default.SwapHoriz
}
