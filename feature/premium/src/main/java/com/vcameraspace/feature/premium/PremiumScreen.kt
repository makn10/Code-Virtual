package com.vcameraspace.feature.premium

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.vcameraspace.core.designsystem.components.CustomToolbar
import com.vcameraspace.core.designsystem.components.PremiumCard
import com.vcameraspace.core.designsystem.components.PrimaryButton
import com.vcameraspace.core.designsystem.components.SectionHeader
import com.vcameraspace.core.designsystem.components.StatusCard
import com.vcameraspace.core.designsystem.components.StatusSeverity
import kotlinx.coroutines.launch

@Composable
fun PremiumScreen(
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    fun notifyBillingNotConfigured() {
        coroutineScope.launch {
            snackbarHostState.showSnackbar(
                "Billing is not active: Google Play Billing Library is not configured in this build."
            )
        }
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("premium_screen"),
        contentWindowInsets = WindowInsets.safeDrawing,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            CustomToolbar(
                title = "VCamera Space Premium",
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
                StatusCard(
                    title = "Billing Integration Notice",
                    message = "Purchasing and entitlement restoration are inactive placeholders until a legitimate Google Play Billing Library integration and store product IDs are configured. No simulated purchases are performed.",
                    severity = StatusSeverity.WARNING,
                    icon = Icons.Default.Info
                )
            }

            item {
                PremiumCard(
                    title = "Advanced Space Management",
                    description = "Extended workspace templates, per-space rules, and priority workspace profiles.",
                    icon = Icons.Default.Layers
                )
            }
            item {
                PremiumCard(
                    title = "Additional Configuration Options",
                    description = "Expanded camera stream profiles, per-app overrides, and testing presets.",
                    icon = Icons.Default.Tune
                )
            }
            item {
                PremiumCard(
                    title = "Advanced Camera Diagnostics",
                    description = "Extended Camera Lab telemetry exports and stream recovery logs.",
                    icon = Icons.Default.Science
                )
            }
            item {
                PremiumCard(
                    title = "Backup Tools",
                    description = "Structured workspace snapshot profiles and multi-space configuration portability.",
                    icon = Icons.Default.Backup
                )
            }
            item {
                PremiumCard(
                    title = "Priority Feature Access",
                    description = "Early access to upcoming supported virtualization adapters and diagnostics.",
                    icon = Icons.Default.Star
                )
            }

            item {
                SectionHeader(
                    title = "Plan Comparison",
                    subtitle = "Transparent feature breakdown"
                )
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Card(
                        modifier = Modifier.weight(1f),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text("Free Plan", style = MaterialTheme.typography.titleMedium)
                            Text("• Multiple Spaces\n• Virtual Camera Sources\n• Camera Lab & Backup", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Card(
                        modifier = Modifier.weight(1f),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text("Premium Plan", style = MaterialTheme.typography.titleMedium)
                            Text("• Billing Not Active\n• Extended Presets\n• Priority Access", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            item {
                PrimaryButton(
                    text = "Purchase (Billing Setup Required)",
                    onClick = ::notifyBillingNotConfigured,
                    modifier = Modifier.fillMaxWidth(),
                    testTag = "premium_purchase_placeholder"
                )
            }
            item {
                OutlinedButton(
                    onClick = ::notifyBillingNotConfigured,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("premium_restore_placeholder")
                ) {
                    Text("Restore Purchase (Billing Setup Required)")
                }
            }
        }
    }
}
