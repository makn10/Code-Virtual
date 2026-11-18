package com.vcameraspace.core.designsystem.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable
fun ConfirmationDialog(
    title: String,
    message: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    dismissText: String = "Cancel",
    isDestructive: Boolean = false,
    icon: ImageVector? = null
) {
    AlertDialog(
        modifier = modifier.testTag("confirmation_dialog"),
        onDismissRequest = onDismiss,
        icon = if (icon != null) {
            {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (isDestructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                )
            }
        } else null,
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = if (isDestructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                ),
                modifier = Modifier.testTag("dialog_confirm_button")
            ) {
                Text(text = confirmText)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("dialog_dismiss_button")
            ) {
                Text(text = dismissText)
            }
        }
    )
}

@Composable
fun PermissionDialog(
    title: String,
    rationale: String,
    onGrantClick: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    grantText: String = "Continue",
    dismissText: String = "Not Now",
    icon: ImageVector? = null
) {
    AlertDialog(
        modifier = modifier.testTag("permission_dialog"),
        onDismissRequest = onDismiss,
        icon = if (icon != null) {
            {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        } else null,
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Text(
                text = rationale,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        confirmButton = {
            TextButton(
                onClick = onGrantClick,
                modifier = Modifier.testTag("permission_grant_button")
            ) {
                Text(text = grantText)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("permission_dismiss_button")
            ) {
                Text(text = dismissText)
            }
        }
    )
}

@Composable
fun PreLaunchVirtualCameraDialog(
    appName: String,
    activeSourceLabel: String,
    onOpenBuiltInVirtualCamera: () -> Unit,
    onConfigureSource: () -> Unit,
    onOpenExternalAppAnyway: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null
) {
    AlertDialog(
        modifier = modifier.testTag("prelaunch_virtual_camera_dialog"),
        onDismissRequest = onDismiss,
        icon = if (icon != null) {
            {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        } else null,
        title = {
            Text(
                text = "Launch $appName",
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Active Media Section Source: $activeSourceLabel",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Unrooted Android isolates external apps like $appName to the physical camera hardware when launched directly. Open the Built-in Virtual Camera to preview and capture with your selected Media Section source, or choose VCamera Space when another app requests a photo or video clip.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(
                    onClick = onOpenBuiltInVirtualCamera,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("prelaunch_open_virtual_camera")
                ) {
                    Text("Open Built-in Virtual Camera")
                }
                OutlinedButton(
                    onClick = onConfigureSource,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("prelaunch_configure_source")
                ) {
                    Text("Configure Media Source")
                }
                OutlinedButton(
                    onClick = onOpenExternalAppAnyway,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("prelaunch_open_external")
                ) {
                    Text("Open External App (Uses Physical Camera)")
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("prelaunch_dismiss_button")
            ) {
                Text("Cancel")
            }
        }
    )
}
