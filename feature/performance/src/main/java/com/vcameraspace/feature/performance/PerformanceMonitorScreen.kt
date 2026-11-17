package com.vcameraspace.feature.performance

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vcameraspace.core.database.repository.SpaceRepository
import com.vcameraspace.core.designsystem.components.CustomToolbar
import com.vcameraspace.core.designsystem.components.PrimaryButton
import com.vcameraspace.core.designsystem.components.StatusCard
import com.vcameraspace.core.designsystem.components.StatusSeverity
import com.vcameraspace.virtualengine.api.VirtualEngine
import com.vcameraspace.virtualengine.impl.ManagedVirtualProcessManager
import java.io.File

@Composable
fun PerformanceMonitorScreen(
    spaceRepository: SpaceRepository,
    processManager: ManagedVirtualProcessManager,
    virtualEngine: VirtualEngine,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var refreshTick by remember { mutableIntStateOf(0) }
    val spaces by spaceRepository.observeSpaces().collectAsStateWithLifecycle(initialValue = emptyList())
    val appsMap by spaceRepository.observeAppsBySpace().collectAsStateWithLifecycle(initialValue = emptyMap())
    val processes by processManager.observeAllActiveProcesses().collectAsStateWithLifecycle(initialValue = emptyList())

    val memoryStats = remember(refreshTick) {
        val rt = Runtime.getRuntime()
        val usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024.0 * 1024.0)
        val maxMb = rt.maxMemory() / (1024.0 * 1024.0)
        usedMb to maxMb
    }
    val storageKb = remember(refreshTick, context) {
        dirSize(context.filesDir) / 1024L + dirSize(context.cacheDir) / 1024L
    }
    val totalManagedApps = appsMap.values.sumOf { it.size }
    val activeProcessCount = processes.count { it.isRunning }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("performance_monitor_screen"),
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            CustomToolbar(
                title = "Performance Monitor",
                subtitle = "Lightweight On-Demand Telemetry",
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
                    title = "Application Memory Usage",
                    message = String.format("Heap Used: %.1f MB / %.1f MB Max", memoryStats.first, memoryStats.second),
                    severity = StatusSeverity.INFO,
                    icon = Icons.Default.Memory
                )
            }
            item {
                StatusCard(
                    title = "Scoped Storage & Workspaces",
                    message = "App Storage: $storageKb KB\nConfigured Spaces: ${spaces.size}\nManaged App Records: $totalManagedApps",
                    severity = StatusSeverity.INFO,
                    icon = Icons.Default.Storage
                )
            }
            item {
                StatusCard(
                    title = "Engine Health & Supported Processes",
                    message = "Strategy: ${virtualEngine.capabilities.strategy.name}\nActive Supported Processes: $activeProcessCount\nStatus: Healthy (No background polling daemon)",
                    severity = StatusSeverity.SUCCESS,
                    icon = Icons.Default.CheckCircle
                )
            }
            item {
                PrimaryButton(
                    text = "Refresh Telemetry",
                    icon = Icons.Default.Refresh,
                    onClick = { refreshTick++ },
                    modifier = Modifier.fillMaxWidth(),
                    testTag = "performance_refresh_button"
                )
            }
        }
    }
}

private fun dirSize(dir: File): Long =
    if (!dir.exists()) 0L else dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
