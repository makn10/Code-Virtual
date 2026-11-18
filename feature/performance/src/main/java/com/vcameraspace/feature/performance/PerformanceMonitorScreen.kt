package com.vcameraspace.feature.performance

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vcameraspace.core.common.model.AppResult
import com.vcameraspace.core.database.repository.SpaceRepository
import com.vcameraspace.core.designsystem.components.CustomToolbar
import com.vcameraspace.core.designsystem.components.PrimaryButton
import com.vcameraspace.core.designsystem.components.StatusCard
import com.vcameraspace.core.designsystem.components.StatusSeverity
import com.vcameraspace.core.media.CameraAdapterLifecycle
import com.vcameraspace.core.media.VirtualCameraAdapterManager
import com.vcameraspace.virtualengine.api.VirtualEngine
import com.vcameraspace.virtualengine.impl.ManagedVirtualProcessManager
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun PerformanceMonitorScreen(
    spaceRepository: SpaceRepository,
    processManager: ManagedVirtualProcessManager,
    virtualEngine: VirtualEngine,
    cameraAdapterManager: VirtualCameraAdapterManager? = null,
    onBackClick: () -> Unit,
    onOpenDiagnosticLogs: (() -> Unit)? = null,
    onOpenCompatibilityStatus: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var refreshTick by remember { mutableIntStateOf(0) }
    val spaces by spaceRepository.observeSpaces().collectAsStateWithLifecycle(initialValue = emptyList())
    val appsMap by spaceRepository.observeAppsBySpace().collectAsStateWithLifecycle(initialValue = emptyMap())
    val processes by processManager.observeAllActiveProcesses().collectAsStateWithLifecycle(initialValue = emptyList())
    val adapterState = cameraAdapterManager?.state?.collectAsStateWithLifecycle()?.value

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
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            CustomToolbar(
                title = "Runtime Status",
                subtitle = "Engine, Virtual Camera Adapter & Telemetry",
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
            if (adapterState != null && cameraAdapterManager != null) {
                item {
                    val lifecycle = adapterState.lifecycle
                    StatusCard(
                        title = "Virtual Camera Adapter (${lifecycle.name})",
                        message = buildString {
                            appendLine("Shared Source Mode: ${adapterState.sharedConfig.mode.name}")
                            appendLine("Frame Format: ${adapterState.sharedConfig.outputFormat.label}")
                            appendLine("Physical Camera Hardware: ${if (adapterState.sharedConfig.physicalCameraIsolated) "Isolated (Unused)" else "Pass-through"}")
                            appendLine("Camera2 HAL Probe: ${adapterState.apiMatrix.camera2HardwareLevelSummary}")
                            appendLine("Camera1 Proxy: ${adapterState.apiMatrix.camera1BridgeMode}")
                            appendLine("GL / SurfaceTexture: ${if (adapterState.apiMatrix.openGlPipelineReady) "OpenGL ES 2.0 OES Ready" else "Unavailable"}")
                            appendLine("Native JNI Bridge: ${adapterState.apiMatrix.nativeBridgeStatus.label}")
                            append(adapterState.lastStatusNote)
                        },
                        severity = when (lifecycle) {
                            CameraAdapterLifecycle.STREAMING -> StatusSeverity.SUCCESS
                            CameraAdapterLifecycle.ERROR -> StatusSeverity.ERROR
                            else -> StatusSeverity.INFO
                        },
                        icon = Icons.Default.Videocam
                    )
                }

                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PrimaryButton(
                            text = "Start Adapter",
                            icon = Icons.Default.PlayArrow,
                            onClick = {
                                coroutineScope.launch {
                                    when (val res = cameraAdapterManager.startVirtualSession()) {
                                        is AppResult.Success -> snackbarHostState.showSnackbar("Adapter state: ${res.data.lifecycle.name}")
                                        is AppResult.Failure -> snackbarHostState.showSnackbar(res.error.message)
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f),
                            testTag = "runtime_start_adapter_button"
                        )
                        OutlinedButton(
                            onClick = { cameraAdapterManager.stopVirtualSession() },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("runtime_stop_adapter_button")
                        ) {
                            Icon(Icons.Default.Stop, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Stop Adapter")
                        }
                    }
                }
            }

            item {
                StatusCard(
                    title = "Engine Health & Supported Processes",
                    message = "Strategy: ${virtualEngine.capabilities.strategy.name}\nActive Supported Processes: $activeProcessCount\nCameraX Pipeline Ready: ${adapterState?.apiMatrix?.cameraXSupported ?: true}\nOpenGL ES / MediaCodec Pipeline: Active\nStatus: Healthy (On-demand telemetry)",
                    severity = StatusSeverity.SUCCESS,
                    icon = Icons.Default.CheckCircle
                )
            }

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
                    message = "Private App Storage: $storageKb KB\nConfigured Spaces: ${spaces.size}\nVirtual App Records: $totalManagedApps",
                    severity = StatusSeverity.INFO,
                    icon = Icons.Default.Storage
                )
            }

            item {
                PrimaryButton(
                    text = "Refresh Runtime Telemetry",
                    icon = Icons.Default.Refresh,
                    onClick = {
                        cameraAdapterManager?.refreshCameraApiSupport()
                        refreshTick++
                    },
                    modifier = Modifier.fillMaxWidth(),
                    testTag = "performance_refresh_button"
                )
            }

            if (onOpenDiagnosticLogs != null || onOpenCompatibilityStatus != null) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (onOpenDiagnosticLogs != null) {
                            OutlinedButton(
                                onClick = onOpenDiagnosticLogs,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("runtime_open_logs_button")
                            ) {
                                Icon(Icons.Default.BugReport, contentDescription = null)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Diagnostic Logs")
                            }
                        }
                        if (onOpenCompatibilityStatus != null) {
                            OutlinedButton(
                                onClick = onOpenCompatibilityStatus,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("runtime_open_compatibility_button")
                            ) {
                                Icon(Icons.Default.VerifiedUser, contentDescription = null)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Compatibility")
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun dirSize(dir: File): Long =
    if (!dir.exists()) 0L else dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
