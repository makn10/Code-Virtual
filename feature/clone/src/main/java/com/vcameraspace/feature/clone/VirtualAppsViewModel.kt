package com.vcameraspace.feature.clone

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.vcameraspace.core.common.diagnostics.DiagnosticCategory
import com.vcameraspace.core.common.diagnostics.DiagnosticLogger
import com.vcameraspace.core.common.model.AppResult
import com.vcameraspace.core.common.model.BuiltInVirtualApps
import com.vcameraspace.core.common.model.Space
import com.vcameraspace.core.common.model.SpaceApp
import com.vcameraspace.core.database.repository.SpaceRepository
import com.vcameraspace.core.media.CameraConfigurationRepository
import com.vcameraspace.core.media.CameraSourceRegistry
import com.vcameraspace.core.media.VirtualCameraAdapterManager
import com.vcameraspace.virtualengine.api.CameraSourceMode
import com.vcameraspace.virtualengine.api.ProcessState
import com.vcameraspace.virtualengine.api.VirtualPackageManager
import com.vcameraspace.virtualengine.api.VirtualStorageManager
import com.vcameraspace.virtualengine.impl.ManagedVirtualProcessManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class VirtualAppEntry(
    val app: SpaceApp,
    val spaceName: String,
    val processState: ProcessState?,
    val cameraModeLabel: String,
    val isSharedCameraSource: Boolean
)

data class PreLaunchVirtualAppPrompt(
    val app: SpaceApp,
    val activeMode: CameraSourceMode,
    val activeSourceLabel: String
)

data class VirtualAppsUiState(
    val spaces: List<Space> = emptyList(),
    val selectedSpaceFilter: String? = null,
    val entries: List<VirtualAppEntry> = emptyList(),
    val pendingPreLaunchPrompt: PreLaunchVirtualAppPrompt? = null,
    val feedbackMessage: String? = null
) {
    val filteredEntries: List<VirtualAppEntry>
        get() = if (selectedSpaceFilter == null) entries else entries.filter { it.app.spaceId == selectedSpaceFilter }
}

class VirtualAppsViewModel(
    private val spaceRepository: SpaceRepository,
    private val virtualPackageManager: VirtualPackageManager,
    private val processManager: ManagedVirtualProcessManager,
    private val storageManager: VirtualStorageManager,
    private val cameraAdapterManager: VirtualCameraAdapterManager,
    private val logger: DiagnosticLogger? = null,
    private val cameraConfigRepository: CameraConfigurationRepository? = null
) : ViewModel() {

    private val _selectedSpaceFilter = MutableStateFlow<String?>(null)
    private val _pendingPreLaunch = MutableStateFlow<PreLaunchVirtualAppPrompt?>(null)
    private val _feedback = MutableStateFlow<String?>(null)

    val uiState: StateFlow<VirtualAppsUiState> = combine(
        spaceRepository.observeSpaces(),
        spaceRepository.observeAppsBySpace(),
        processManager.observeAllActiveProcesses(),
        cameraAdapterManager.state,
        combine(_selectedSpaceFilter, _pendingPreLaunch, _feedback) { f, p, msg -> Triple(f, p, msg) }
    ) { spaces, appsMap, processes, adapterState, (filter, pendingPrompt, msg) ->
        val spaceNames = spaces.associate { it.id to it.name }
        val flattened = appsMap.values.flatten().map { app ->
            val proc = processes.firstOrNull { it.spaceId == app.spaceId && it.packageName == app.packageName }
            VirtualAppEntry(
                app = app,
                spaceName = spaceNames[app.spaceId] ?: app.spaceId,
                processState = proc,
                cameraModeLabel = if (app.hasCustomCameraConfig) {
                    "Per-App Override (${adapterState.sharedConfig.mode.name})"
                } else {
                    "Shared Global (${adapterState.sharedConfig.mode.name})"
                },
                isSharedCameraSource = !app.hasCustomCameraConfig
            )
        }.sortedWith(compareBy({ it.spaceName }, { it.app.label.lowercase() }))

        VirtualAppsUiState(
            spaces = spaces,
            selectedSpaceFilter = filter,
            entries = flattened,
            pendingPreLaunchPrompt = pendingPrompt,
            feedbackMessage = msg
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = VirtualAppsUiState()
    )

    fun selectSpaceFilter(spaceId: String?) {
        _selectedSpaceFilter.value = spaceId
    }

    fun requestLaunchApp(
        app: SpaceApp,
        onOpenBuiltInVirtualCamera: (spaceId: String, packageName: String?) -> Unit
    ) {
        if (BuiltInVirtualApps.isBuiltInVirtualCamera(app.packageName)) {
            onOpenBuiltInVirtualCamera(app.spaceId, app.packageName)
            launchApp(app)
            return
        }
        viewModelScope.launch {
            val resolved = cameraAdapterManager.resolveEffectiveConfig(app.spaceId, app.packageName)
            val isVirtualActive = resolved.mode != CameraSourceMode.REAL_DEVICE
            val isCameraApp = BuiltInVirtualApps.isKnownCameraOrSocialApp(app.packageName, app.label)
            if (isVirtualActive || isCameraApp) {
                val label = CameraSourceRegistry.sourceFor(resolved.mode).label
                _pendingPreLaunch.value = PreLaunchVirtualAppPrompt(
                    app = app,
                    activeMode = resolved.mode,
                    activeSourceLabel = label
                )
            } else {
                launchApp(app)
            }
        }
    }

    fun dismissPreLaunchPrompt() {
        _pendingPreLaunch.value = null
    }

    fun launchApp(app: SpaceApp) {
        _pendingPreLaunch.value = null
        viewModelScope.launch {
            when (val res = processManager.startProcess(app.spaceId, app.packageName)) {
                is AppResult.Success -> {
                    logger?.logInfo(DiagnosticCategory.VIRTUAL_APPS, "Launched ${app.label} in ${app.spaceId}")
                    _feedback.value = "Launched ${app.label} (${app.spaceId})."
                }
                is AppResult.Failure -> {
                    logger?.logError(DiagnosticCategory.VIRTUAL_APPS, "Failed launching ${app.packageName}", res.error.message)
                    _feedback.value = res.error.message
                }
            }
        }
    }

    fun stopApp(app: SpaceApp) {
        viewModelScope.launch {
            processManager.stopProcess(app.spaceId, app.packageName)
            _feedback.value = "Stopped tracked instance for ${app.label}."
        }
    }

    fun clearData(app: SpaceApp) {
        viewModelScope.launch {
            storageManager.clearSpaceConfigCache(app.spaceId, app.packageName)
            logger?.logInfo(DiagnosticCategory.VIRTUAL_APPS, "Cleared workspace data for ${app.packageName} in ${app.spaceId}")
            _feedback.value = "Cleared scoped data for ${app.label} in ${app.spaceId}."
        }
    }

    fun removeFromSpace(app: SpaceApp) {
        viewModelScope.launch {
            when (val res = spaceRepository.removeAppFromSpace(app.spaceId, app.packageName)) {
                is AppResult.Success -> {
                    storageManager.clearSpaceConfigCache(app.spaceId, app.packageName)
                    logger?.logInfo(DiagnosticCategory.VIRTUAL_APPS, "Removed ${app.packageName} from ${app.spaceId}")
                    _feedback.value = "Removed ${app.label} from ${app.spaceId}."
                }
                is AppResult.Failure -> _feedback.value = res.error.message
            }
        }
    }

    fun cloneInstanceToSpace(sourceApp: SpaceApp, targetSpaceId: String) {
        viewModelScope.launch {
            if (sourceApp.spaceId == targetSpaceId) {
                _feedback.value = "${sourceApp.label} is already in $targetSpaceId."
                return@launch
            }
            storageManager.ensureSpaceDirectories(targetSpaceId)
            val cloned = sourceApp.copy(
                id = "${targetSpaceId}::${sourceApp.packageName}",
                spaceId = targetSpaceId
            )
            when (val res = virtualPackageManager.registerApp(cloned)) {
                is AppResult.Success -> {
                    logger?.logInfo(DiagnosticCategory.VIRTUAL_APPS, "Created workspace instance of ${sourceApp.packageName} in $targetSpaceId")
                    _feedback.value = "Created isolated workspace instance for ${sourceApp.label} in $targetSpaceId."
                }
                is AppResult.Failure -> _feedback.value = res.error.message
            }
        }
    }

    fun clearFeedback() {
        _feedback.update { null }
    }

    companion object {
        fun factory(
            spaceRepository: SpaceRepository,
            virtualPackageManager: VirtualPackageManager,
            processManager: ManagedVirtualProcessManager,
            storageManager: VirtualStorageManager,
            cameraAdapterManager: VirtualCameraAdapterManager,
            logger: DiagnosticLogger? = null,
            cameraConfigRepository: CameraConfigurationRepository? = null
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return VirtualAppsViewModel(
                    spaceRepository = spaceRepository,
                    virtualPackageManager = virtualPackageManager,
                    processManager = processManager,
                    storageManager = storageManager,
                    cameraAdapterManager = cameraAdapterManager,
                    logger = logger,
                    cameraConfigRepository = cameraConfigRepository
                ) as T
            }
        }
    }
}
