package com.vcameraspace.feature.clone

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vcameraspace.core.designsystem.components.CloneAppItem
import com.vcameraspace.core.designsystem.components.ConfirmationDialog
import com.vcameraspace.core.designsystem.components.CustomToolbar
import com.vcameraspace.core.designsystem.components.EmptyState
import com.vcameraspace.core.designsystem.components.LoadingIndicator
import com.vcameraspace.core.designsystem.components.PrimaryButton
import com.vcameraspace.core.designsystem.components.SearchBar
import com.vcameraspace.core.designsystem.components.SectionHeader
import com.vcameraspace.core.designsystem.components.StatusCard
import com.vcameraspace.core.designsystem.components.StatusSeverity
import com.vcameraspace.virtualengine.api.CompatibilitySeverity
import com.vcameraspace.virtualengine.impl.ApkInspectionResult

@Composable
fun CloneAppScreen(
    viewModel: CloneAppViewModel,
    onBackClick: () -> Unit,
    onOpenApkImport: () -> Unit,
    onSelectAppForInstall: (packageName: String) -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    CloneAppContent(
        uiState = uiState,
        onBackClick = onBackClick,
        onOpenApkImport = onOpenApkImport,
        onToggleSearch = viewModel::toggleSearch,
        onSearchQueryChange = viewModel::onSearchQueryChange,
        onSelectApp = onSelectAppForInstall,
        onRetry = viewModel::loadApps,
        modifier = modifier
    )
}

@Composable
fun CloneAppContent(
    uiState: CloneAppUiState,
    onBackClick: () -> Unit,
    onOpenApkImport: () -> Unit,
    onToggleSearch: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onSelectApp: (packageName: String) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("clone_app_screen"),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            CustomToolbar(
                title = "Clone App",
                onBackClick = onBackClick,
                actions = {
                    TextButton(
                        onClick = onOpenApkImport,
                        modifier = Modifier.testTag("clone_apk_text_button")
                    ) {
                        Text(
                            text = "Clone Apk",
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                    IconButton(
                        onClick = onToggleSearch,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("clone_search_icon_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "Search installed applications"
                        )
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
            AnimatedVisibility(visible = uiState.searchVisible) {
                SearchBar(
                    query = uiState.searchQuery,
                    onQueryChange = onSearchQueryChange,
                    placeholder = "Search app name or package...",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    testTag = "clone_search_bar"
                )
            }

            when {
                uiState.isLoading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        LoadingIndicator(label = "Scanning visible Android applications...")
                    }
                }
                uiState.errorMessage != null -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(20.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        StatusCard(
                            title = "Package Discovery Error",
                            message = uiState.errorMessage,
                            severity = StatusSeverity.ERROR,
                            icon = Icons.Default.ErrorOutline,
                            action = {
                                PrimaryButton(text = "Retry", onClick = onRetry)
                            }
                        )
                    }
                }
                uiState.filteredApps.isEmpty() -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        EmptyState(
                            title = "No Matching Apps",
                            subtitle = "Android 11+ targeted <queries> shows launcher-visible apps without requesting broad QUERY_ALL_PACKAGES."
                        )
                    }
                }
                else -> {
                    LazyColumn(
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("clone_apps_list")
                    ) {
                        item {
                            StatusCard(
                                title = "Targeted Package Visibility",
                                message = "Showing ${uiState.filteredApps.size} visible launcher applications on this device. Tap an app to run ABI/SDK checks and add it to the current workspace.",
                                severity = StatusSeverity.INFO,
                                icon = Icons.Default.Info
                            )
                        }
                        items(uiState.filteredApps, key = { it.packageName }) { app ->
                            val alreadyAdded = app.packageName in uiState.existingPackagesInSpace
                            val painter = remember(app.packageName) { drawableToPainter(app.icon) }
                            CloneAppItem(
                                appName = app.label,
                                packageName = app.packageName,
                                badgeText = if (alreadyAdded) "ADDED" else "CLONE",
                                iconPainter = painter,
                                onClick = { onSelectApp(app.packageName) },
                                testTag = "clone_item_${app.packageName}"
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ApkImportScreen(
    viewModel: ApkImportViewModel,
    onBackClick: () -> Unit,
    onDoneNavigateHome: () -> Unit,
    onOpenImportedApps: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    val apkPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.inspectApk(uri)
        }
    }

    LaunchedEffect(uiState.statusMessage) {
        val msg = uiState.statusMessage
        if (!msg.isNullOrBlank()) {
            snackbarHostState.showSnackbar(msg)
            onDoneNavigateHome()
        }
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("apk_import_screen"),
        contentWindowInsets = WindowInsets.safeDrawing,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            CustomToolbar(
                title = "APK Import",
                subtitle = "Storage Access Framework Inspector",
                onBackClick = onBackClick,
                actions = {
                    if (onOpenImportedApps != null) {
                        TextButton(onClick = onOpenImportedApps) {
                            Text("Imported List")
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .widthIn(max = 680.dp),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                StatusCard(
                    title = "Verified APK Inspection",
                    message = "Select an .apk file via Android Storage Access Framework. VCamera Space copies it into private storage, extracts icons & signatures, checks ABIs/SDKs, and keeps imported APKs separate from virtual space installs.",
                    severity = StatusSeverity.INFO,
                    icon = Icons.Default.FileOpen
                )
            }

            item {
                PrimaryButton(
                    text = "Choose APK File",
                    icon = Icons.Default.FileOpen,
                    onClick = {
                        apkPickerLauncher.launch(
                            arrayOf(
                                "application/vnd.android.package-archive",
                                "application/octet-stream"
                            )
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    testTag = "pick_apk_button"
                )
            }

            if (uiState.isInspecting) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        LoadingIndicator(label = "Validating APK signature, ABIs, and manifest...")
                    }
                }
            }

            uiState.errorMessage?.let { error ->
                item {
                    StatusCard(
                        title = "APK Validation Failed",
                        message = error,
                        severity = StatusSeverity.ERROR,
                        icon = Icons.Default.ErrorOutline
                    )
                }
            }

            uiState.inspection?.let { result ->
                item {
                    ApkInspectionSummaryCard(
                        result = result,
                        onLaunchAndroidInstaller = {
                            uiState.selectedUriString?.toUri()?.let { contentUri ->
                                val installIntent = Intent(Intent.ACTION_VIEW).apply {
                                    setDataAndType(contentUri, "application/vnd.android.package-archive")
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                runCatching { context.startActivity(installIntent) }
                            }
                        },
                        onRegisterInSpace = viewModel::registerInstalledApkInSpace
                    )
                }
            }
        }
    }
}

@Composable
private fun ApkInspectionSummaryCard(
    result: ApkInspectionResult,
    onLaunchAndroidInstaller: () -> Unit,
    onRegisterInSpace: () -> Unit
) {
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SectionHeader(
                title = result.appLabel,
                subtitle = result.packageName
            )
            Text(
                text = "Version: ${result.versionName} (${result.versionCode})",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                text = "SDK Range: minSdk ${result.minSdk} • targetSdk ${result.targetSdk}",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                text = "Signing SHA-256: ${result.signingSha256}",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                text = "Native ABIs: ${if (result.apkAbis.isEmpty()) "Universal / Pure Java-Kotlin" else result.apkAbis.joinToString()}",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                text = "APK Size: ${result.fileSizeBytes / 1024} KB • Free Storage: ${result.availableStorageBytes / (1024 * 1024)} MB",
                style = MaterialTheme.typography.bodySmall
            )

            if (result.issues.isNotEmpty()) {
                result.issues.forEach { issue ->
                    StatusCard(
                        title = issue.title,
                        message = issue.detail,
                        severity = when (issue.severity) {
                            CompatibilitySeverity.BLOCKER -> StatusSeverity.ERROR
                            CompatibilitySeverity.WARNING -> StatusSeverity.WARNING
                            CompatibilitySeverity.INFO -> StatusSeverity.INFO
                        }
                    )
                }
            } else {
                StatusCard(
                    title = "Compatible APK",
                    message = "All manifest, ABI, SDK, and storage checks passed.",
                    severity = StatusSeverity.SUCCESS,
                    icon = Icons.Default.CheckCircle
                )
            }

            Spacer(modifier = Modifier.height(6.dp))
            PrimaryButton(
                text = "Install via Android Package Installer",
                enabled = result.isCompatible,
                onClick = onLaunchAndroidInstaller,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedButton(
                onClick = onRegisterInSpace,
                enabled = result.isCompatible,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Add Installed Package to Space")
            }
        }
    }
}

@Composable
fun InstallationProgressScreen(
    viewModel: InstallationProgressViewModel,
    onBackClick: () -> Unit,
    onDoneHome: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("installation_progress_screen"),
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            CustomToolbar(
                title = "Workspace Setup",
                subtitle = uiState.packageName,
                onBackClick = onBackClick
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(20.dp)
                .widthIn(max = 620.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            LinearProgressIndicator(
                progress = { uiState.progressFraction },
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = uiState.currentStepLabel,
                style = MaterialTheme.typography.titleMedium
            )

            when (val status = uiState.status) {
                InstallStepStatus.Running -> {
                    LoadingIndicator(label = "Verifying package compatibility...")
                }
                is InstallStepStatus.Completed -> {
                    StatusCard(
                        title = "Added ${status.app.label}",
                        message = "Configured under ${status.app.spaceId} using ${status.app.architecture.name}.",
                        severity = StatusSeverity.SUCCESS,
                        icon = Icons.Default.CheckCircle
                    )
                    status.report?.issues?.forEach { issue ->
                        StatusCard(
                            title = issue.title,
                            message = issue.detail,
                            severity = when (issue.severity) {
                                CompatibilitySeverity.BLOCKER -> StatusSeverity.ERROR
                                CompatibilitySeverity.WARNING -> StatusSeverity.WARNING
                                CompatibilitySeverity.INFO -> StatusSeverity.INFO
                            }
                        )
                    }
                    PrimaryButton(
                        text = "Return to Home",
                        onClick = onDoneHome,
                        modifier = Modifier.fillMaxWidth(),
                        testTag = "install_done_home_button"
                    )
                }
                is InstallStepStatus.Failed -> {
                    StatusCard(
                        title = "Setup Failed",
                        message = "${status.message}\n${status.recoveryHint.orEmpty()}".trim(),
                        severity = StatusSeverity.ERROR,
                        icon = Icons.Default.ErrorOutline
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(
                            onClick = onBackClick,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Back")
                        }
                        PrimaryButton(
                            text = "Retry",
                            onClick = viewModel::startInstallation,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun AppInformationScreen(
    viewModel: AppInformationViewModel,
    onBackClick: () -> Unit,
    onOpenCameraSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var showRemoveConfirm by remember { mutableStateOf(false) }

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
            .testTag("app_information_screen"),
        contentWindowInsets = WindowInsets.safeDrawing,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            CustomToolbar(
                title = "App Information",
                subtitle = uiState.app?.label ?: uiState.packageName,
                onBackClick = onBackClick
            )
        }
    ) { innerPadding ->
        if (uiState.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                LoadingIndicator(label = "Evaluating compatibility report...")
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .widthIn(max = 680.dp),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    val app = uiState.app
                    StatusCard(
                        title = app?.label ?: uiState.packageName,
                        message = "Package: ${uiState.packageName}\nVersion: ${app?.versionName ?: "Unknown"}\nExecution: ${app?.architecture?.name ?: "SUPPORTED_SANDBOX"}",
                        severity = StatusSeverity.INFO,
                        icon = Icons.Default.Info
                    )
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PrimaryButton(
                            text = "Open App",
                            icon = Icons.AutoMirrored.Filled.OpenInNew,
                            onClick = viewModel::launchApp,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedButton(
                            onClick = onOpenCameraSettings,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Videocam, contentDescription = null)
                            Spacer(modifier = Modifier.padding(4.dp))
                            Text("Camera")
                        }
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(
                            onClick = viewModel::clearScopedConfig,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Clear Scoped Cache")
                        }
                        OutlinedButton(
                            onClick = { showRemoveConfirm = true },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.DeleteOutline, contentDescription = null)
                            Spacer(modifier = Modifier.padding(4.dp))
                            Text("Remove")
                        }
                    }
                }
                item {
                    SectionHeader(
                        title = "Compatibility Report",
                        subtitle = "ABI, SDK, GMS, and camera integration diagnostics"
                    )
                }
                val issues = uiState.compatibilityReport?.issues.orEmpty()
                if (issues.isEmpty()) {
                    item {
                        StatusCard(
                            title = "No Compatibility Blockers",
                            message = "Supported in Strategy A sandbox workspace.",
                            severity = StatusSeverity.SUCCESS,
                            icon = Icons.Default.CheckCircle
                        )
                    }
                } else {
                    items(issues) { issue ->
                        StatusCard(
                            title = issue.title,
                            message = issue.detail,
                            severity = when (issue.severity) {
                                CompatibilitySeverity.BLOCKER -> StatusSeverity.ERROR
                                CompatibilitySeverity.WARNING -> StatusSeverity.WARNING
                                CompatibilitySeverity.INFO -> StatusSeverity.INFO
                            },
                            icon = Icons.Default.WarningAmber
                        )
                    }
                }
            }
        }
    }

    if (showRemoveConfirm) {
        ConfirmationDialog(
            title = "Remove from Space?",
            message = "Remove ${uiState.app?.label ?: uiState.packageName} from ${uiState.spaceId}?",
            confirmText = "Remove",
            isDestructive = true,
            onConfirm = {
                showRemoveConfirm = false
                viewModel.removeApp(onRemoved = onBackClick)
            },
            onDismiss = { showRemoveConfirm = false }
        )
    }
}
