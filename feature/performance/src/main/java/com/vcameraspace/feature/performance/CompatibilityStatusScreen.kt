package com.vcameraspace.feature.performance

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vcameraspace.core.designsystem.components.CustomToolbar
import com.vcameraspace.core.designsystem.components.LoadingIndicator
import com.vcameraspace.core.designsystem.components.PrimaryButton
import com.vcameraspace.core.designsystem.components.SectionHeader
import com.vcameraspace.core.designsystem.components.StatusCard
import com.vcameraspace.core.designsystem.components.StatusSeverity
import com.vcameraspace.virtualengine.impl.CompatibilityAssessmentEngine
import com.vcameraspace.virtualengine.impl.CompatibilityMatrixSnapshot
import com.vcameraspace.virtualengine.impl.FeatureSupportLevel
import com.vcameraspace.virtualengine.impl.TargetAppCompatibilityAssessment
import com.vcameraspace.virtualengine.impl.VirtualizationFrameworkOption
import kotlinx.coroutines.launch

@Composable
fun CompatibilityStatusScreen(
    compatibilityEngine: CompatibilityAssessmentEngine,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var snapshot by remember { mutableStateOf<CompatibilityMatrixSnapshot?>(null) }
    var isRefreshing by remember { mutableStateOf(true) }
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        snapshot = compatibilityEngine.evaluateAll()
        isRefreshing = false
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("compatibility_status_screen"),
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            CustomToolbar(
                title = "Compatibility Status",
                subtitle = "Android Runtime, Virtualization & Target Apps",
                onBackClick = onBackClick
            )
        }
    ) { innerPadding ->
        val data = snapshot
        if (data == null || isRefreshing) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                LoadingIndicator(label = "Probing Android package visibility & framework capabilities...")
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
                    StatusCard(
                        title = "Host Runtime Environment",
                        message = "Android ${data.androidRelease} (API ${data.androidApiLevel})\nSupported ABIs: ${data.deviceAbis.joinToString()}\nVerification Honesty: Live PackageManager probing active; physical device call-hook tests are marked Not Verified on Device.",
                        severity = StatusSeverity.INFO,
                        icon = Icons.Default.Info
                    )
                }

                item {
                    PrimaryButton(
                        text = "Re-Probe Target Packages on Device",
                        icon = Icons.Default.Refresh,
                        onClick = {
                            coroutineScope.launch {
                                isRefreshing = true
                                snapshot = compatibilityEngine.evaluateAll()
                                isRefreshing = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        testTag = "compatibility_reprobe_button"
                    )
                }

                item {
                    SectionHeader(
                        title = "Virtualization Frameworks Evaluated",
                        subtitle = "Architecture feasibility on Android 14–16"
                    )
                }

                items(data.frameworks, key = { it.id }) { fw ->
                    FrameworkEvaluationCard(fw)
                }

                item {
                    SectionHeader(
                        title = "Target Application Assessments",
                        subtitle = "WhatsApp, Facebook Messenger & imo"
                    )
                }

                items(data.targetApps, key = { it.packageName }) { target ->
                    TargetAppCard(target)
                }
            }
        }
    }
}

@Composable
private fun FrameworkEvaluationCard(fw: VirtualizationFrameworkOption) {
    StatusCard(
        title = fw.name,
        message = buildString {
            appendLine("License / Origin: ${fw.licenseOrStatus}")
            appendLine("Android 16 Compatibility: ${fw.android16Support.label}")
            appendLine("Play Policy Compliance: ${fw.policyCompliance.label}")
            append(fw.assessmentSummary)
        },
        severity = if (fw.isActiveInApp) StatusSeverity.SUCCESS else StatusSeverity.WARNING,
        icon = if (fw.isActiveInApp) Icons.Default.CheckCircle else Icons.Default.WarningAmber
    )
}

@Composable
private fun TargetAppCard(target: TargetAppCompatibilityAssessment) {
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("compat_card_${target.packageName}")
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = target.displayName,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                    )
                    Text(
                        text = target.packageName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = if (target.installedOnDevice) {
                        "Installed (${target.installedVersionName ?: "?"})"
                    } else {
                        "Not Installed"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (target.installedOnDevice) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }

            CompatibilityMetricRow("APK Import & Parse", target.apkImportSupport)
            CompatibilityMetricRow("Workspace Launch", target.virtualLaunchStatus)
            CompatibilityMetricRow("Login & Account Isolation", target.loginAndAccountStatus)
            CompatibilityMetricRow("Scoped Data Isolation", target.dataIsolationStatus)
            CompatibilityMetricRow("Microphone / Audio Injection", target.micAudioStatus)
            CompatibilityMetricRow("System-Wide Virtual Camera Hook", target.virtualCameraStatus)
            CompatibilityMetricRow("Video-Call Stability", target.stabilityStatus)

            Text(
                text = target.runtimeNotes,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

@Composable
private fun CompatibilityMetricRow(label: String, level: FeatureSupportLevel) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, style = MaterialTheme.typography.bodySmall)
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = level.label,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
            color = when (level) {
                FeatureSupportLevel.SUPPORTED -> MaterialTheme.colorScheme.primary
                FeatureSupportLevel.PARTIALLY_SUPPORTED -> MaterialTheme.colorScheme.secondary
                FeatureSupportLevel.NOT_VERIFIED_ON_DEVICE -> MaterialTheme.colorScheme.tertiary
                FeatureSupportLevel.NOT_SUPPORTED -> MaterialTheme.colorScheme.error
            }
        )
    }
}
