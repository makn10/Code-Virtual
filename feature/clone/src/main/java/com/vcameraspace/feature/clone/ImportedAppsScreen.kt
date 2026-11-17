package com.vcameraspace.feature.clone

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.vcameraspace.core.common.model.Space
import com.vcameraspace.core.designsystem.components.ConfirmationDialog
import com.vcameraspace.core.designsystem.components.CustomToolbar
import com.vcameraspace.core.designsystem.components.EmptyState
import com.vcameraspace.core.designsystem.components.LoadingIndicator
import com.vcameraspace.core.designsystem.components.PrimaryButton
import com.vcameraspace.core.designsystem.components.StatusCard
import com.vcameraspace.core.designsystem.components.StatusSeverity
import com.vcameraspace.virtualengine.impl.ImportedApkRecord
import java.io.File

@Composable
fun ImportedAppsScreen(
    viewModel: ImportedAppsViewModel,
    onBackClick: () -> Unit,
    onOpenVirtualApps: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var pendingDelete by remember { mutableStateOf<ImportedApkRecord?>(null) }
    var pendingSpacePickForApk by remember { mutableStateOf<ImportedApkRecord?>(null) }

    val apkPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.importApk(uri)
        }
    }

    LaunchedEffect(uiState.statusMessage, uiState.errorMessage) {
        val msg = uiState.errorMessage ?: uiState.statusMessage
        if (!msg.isNullOrBlank()) {
            snackbarHostState.showSnackbar(msg)
            viewModel.clearBanner()
        }
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("imported_apps_screen"),
        contentWindowInsets = WindowInsets.safeDrawing,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            CustomToolbar(
                title = "Imported Applications",
                subtitle = "Private APK Storage (${uiState.importedApks.size} saved)",
                onBackClick = onBackClick,
                actions = {
                    TextButton(onClick = onOpenVirtualApps) {
                        Text("Virtual Apps")
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
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                StatusCard(
                    title = "Separate APK Import Repository",
                    message = "APKs imported via Storage Access Framework are copied into app-private storage (filesDir/imported_apks/) with extracted icons, manifests, ABI checks, and signatures. Importing an APK does not install it into a Virtual Space automatically.",
                    severity = StatusSeverity.INFO,
                    icon = Icons.Default.FileOpen
                )
            }

            item {
                PrimaryButton(
                    text = if (uiState.isProcessing) "Importing APK..." else "Import APK via Storage Access Framework",
                    icon = Icons.Default.Add,
                    enabled = !uiState.isProcessing,
                    onClick = {
                        apkPicker.launch(
                            arrayOf(
                                "application/vnd.android.package-archive",
                                "application/octet-stream"
                            )
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    testTag = "imported_apps_pick_button"
                )
            }

            if (uiState.isProcessing) {
                item {
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        LoadingIndicator(label = "Extracting icon, manifest, ABI & certificate...")
                    }
                }
            }

            if (uiState.importedApks.isEmpty() && !uiState.isProcessing) {
                item {
                    EmptyState(
                        title = "No Imported APKs Yet",
                        subtitle = "Select an .apk file to inspect metadata, verify ABI/SDK compatibility, and archive it safely in private storage."
                    )
                }
            } else {
                items(uiState.importedApks, key = { it.id }) { apk ->
                    ImportedApkCard(
                        apk = apk,
                        isInstalledOnDevice = apk.packageName in uiState.installedPackageNames,
                        onRevalidate = { viewModel.revalidateApk(apk.id) },
                        onAddToSpace = { pendingSpacePickForApk = apk },
                        onDelete = { pendingDelete = apk }
                    )
                }
            }
        }
    }

    pendingDelete?.let { target ->
        ConfirmationDialog(
            title = "Remove Imported APK?",
            message = "Delete the private APK copy and cached icon for ${target.appLabel} (${target.packageName})?",
            confirmText = "Delete",
            isDestructive = true,
            icon = Icons.Default.DeleteOutline,
            onConfirm = {
                pendingDelete = null
                viewModel.removeApk(target.id)
            },
            onDismiss = { pendingDelete = null }
        )
    }

    pendingSpacePickForApk?.let { apk ->
        SelectTargetSpaceDialog(
            apk = apk,
            spaces = uiState.spaces,
            onSelectSpace = { spaceId ->
                pendingSpacePickForApk = null
                viewModel.addToSpace(apk.packageName, spaceId)
            },
            onDismiss = { pendingSpacePickForApk = null }
        )
    }
}

@Composable
private fun ImportedApkCard(
    apk: ImportedApkRecord,
    isInstalledOnDevice: Boolean,
    onRevalidate: () -> Unit,
    onAddToSpace: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("imported_apk_card_${apk.packageName}")
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                val iconFile = apk.iconFilePath?.let { File(it) }?.takeIf { it.exists() }
                if (iconFile != null) {
                    AsyncImage(
                        model = iconFile,
                        contentDescription = "${apk.appLabel} icon",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(12.dp))
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Android,
                            contentDescription = "${apk.appLabel} placeholder icon",
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = apk.appLabel,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                    )
                    Text(
                        text = apk.packageName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("delete_imported_apk_${apk.id}")
                ) {
                    Icon(
                        imageVector = Icons.Default.DeleteOutline,
                        contentDescription = "Remove imported APK",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }

            Text(
                text = "Version: ${apk.versionName} (${apk.versionCode}) • Size: ${apk.fileSizeBytes / 1024} KB",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                text = "SDK: min ${apk.minSdk} / target ${apk.targetSdk} • ABIs: ${if (apk.abis.isEmpty()) "Universal" else apk.abis.joinToString()}",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                text = "Cert SHA-256: ${apk.signingSha256}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AssistChip(
                    onClick = onRevalidate,
                    label = {
                        Text(if (apk.isCompatible) "Compatible" else "Incompatible")
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = if (apk.isCompatible) Icons.Default.CheckCircle else Icons.Default.WarningAmber,
                            contentDescription = null
                        )
                    }
                )
                AssistChip(
                    onClick = {},
                    label = {
                        Text(if (isInstalledOnDevice) "Installed on Device" else "APK Archive Only")
                    },
                    leadingIcon = {
                        Icon(Icons.Default.VerifiedUser, contentDescription = null)
                    }
                )
            }

            Text(
                text = apk.compatibilitySummary,
                style = MaterialTheme.typography.bodySmall,
                color = if (apk.isCompatible) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onRevalidate,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Check Compatibility")
                }
                PrimaryButton(
                    text = "Add to Space",
                    enabled = apk.isCompatible && isInstalledOnDevice,
                    onClick = onAddToSpace,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun SelectTargetSpaceDialog(
    apk: ImportedApkRecord,
    spaces: List<Space>,
    onSelectSpace: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add ${apk.appLabel} to Space") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Select the target workspace for this installed package:",
                    style = MaterialTheme.typography.bodySmall
                )
                spaces.forEach { space ->
                    OutlinedButton(
                        onClick = { onSelectSpace(space.id) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("${space.name} (${space.appCount} apps)")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
