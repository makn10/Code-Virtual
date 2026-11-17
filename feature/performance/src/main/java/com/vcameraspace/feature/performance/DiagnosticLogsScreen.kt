package com.vcameraspace.feature.performance

import android.content.Intent
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vcameraspace.core.common.diagnostics.DiagnosticLogEntry
import com.vcameraspace.core.common.diagnostics.DiagnosticLogger
import com.vcameraspace.core.common.diagnostics.DiagnosticSeverity
import com.vcameraspace.core.designsystem.components.CustomToolbar
import com.vcameraspace.core.designsystem.components.EmptyState
import com.vcameraspace.core.designsystem.components.StatusCard
import com.vcameraspace.core.designsystem.components.StatusSeverity

@Composable
fun DiagnosticLogsScreen(
    logger: DiagnosticLogger,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val logs by logger.logs.collectAsStateWithLifecycle()
    val lastCrash by logger.lastPersistedCrashReport.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var selectedFilter by remember { mutableStateOf<DiagnosticSeverity?>(null) }

    val filteredLogs = remember(logs, selectedFilter) {
        if (selectedFilter == null) logs else logs.filter { it.severity == selectedFilter }
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("diagnostic_logs_screen"),
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            CustomToolbar(
                title = "Diagnostic Logs",
                subtitle = "${logs.size} events captured (credentials redacted)",
                onBackClick = onBackClick,
                actions = {
                    IconButton(
                        onClick = {
                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_SUBJECT, "VCamera Space Diagnostics")
                                putExtra(Intent.EXTRA_TEXT, logger.exportText())
                            }
                            runCatching {
                                context.startActivity(Intent.createChooser(shareIntent, "Share Diagnostic Report"))
                            }
                        },
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("share_diagnostic_logs_button")
                    ) {
                        Icon(Icons.Default.Share, contentDescription = "Share diagnostic logs")
                    }
                    IconButton(
                        onClick = { logger.clearLogs() },
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("clear_diagnostic_logs_button")
                    ) {
                        Icon(Icons.Default.DeleteSweep, contentDescription = "Clear logs")
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
                    selected = selectedFilter == null,
                    onClick = { selectedFilter = null },
                    label = { Text("All (${logs.size})") }
                )
                DiagnosticSeverity.entries.forEach { severity ->
                    val count = logs.count { it.severity == severity }
                    FilterChip(
                        selected = selectedFilter == severity,
                        onClick = { selectedFilter = severity },
                        label = { Text("${severity.name} ($count)") }
                    )
                }
            }

            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                lastCrash?.let { crashText ->
                    item {
                        StatusCard(
                            title = "Persisted Uncaught Crash Report",
                            message = crashText,
                            severity = StatusSeverity.ERROR,
                            icon = Icons.Default.BugReport,
                            action = {
                                TextButton(onClick = { logger.clearPersistedCrashReport() }) {
                                    Text("Dismiss Crash Report")
                                }
                            }
                        )
                    }
                }

                if (filteredLogs.isEmpty()) {
                    item {
                        EmptyState(
                            title = "No Diagnostic Events",
                            subtitle = "Actions such as APK import, compatibility probing, MediaCodec frame decoding, and virtual camera adapter start/stop appear here automatically."
                        )
                    }
                } else {
                    items(filteredLogs, key = { it.id }) { entry ->
                        DiagnosticLogCard(entry)
                    }
                }
            }
        }
    }
}

@Composable
private fun DiagnosticLogCard(entry: DiagnosticLogEntry) {
    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "[${entry.severity.name}] ${entry.category.name}",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = when (entry.severity) {
                        DiagnosticSeverity.INFO -> MaterialTheme.colorScheme.primary
                        DiagnosticSeverity.WARNING -> MaterialTheme.colorScheme.tertiary
                        DiagnosticSeverity.ERROR, DiagnosticSeverity.CRASH -> MaterialTheme.colorScheme.error
                    }
                )
                Text(
                    text = entry.formattedTimestamp(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = entry.message,
                style = MaterialTheme.typography.bodySmall
            )
            entry.detail?.takeIf { it.isNotBlank() }?.let { detail ->
                Text(
                    text = detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
