package com.vcameraspace.feature.clone

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.vcameraspace.core.common.coroutines.DispatcherProvider
import com.vcameraspace.core.common.model.AppResult
import com.vcameraspace.core.common.model.SpaceApp
import com.vcameraspace.virtualengine.api.CompatibilityReport
import com.vcameraspace.virtualengine.api.VirtualAppInstaller
import com.vcameraspace.virtualengine.api.VirtualPackageManager
import com.vcameraspace.virtualengine.api.VirtualProcessManager
import com.vcameraspace.virtualengine.api.VirtualStorageManager
import com.vcameraspace.virtualengine.impl.AndroidCompatibilityChecker
import com.vcameraspace.virtualengine.impl.ApkImportManager
import com.vcameraspace.virtualengine.impl.ApkInspectionResult
import com.vcameraspace.virtualengine.impl.InstalledAppCandidate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class CloneAppUiState(
    val spaceId: String = "",
    val isLoading: Boolean = true,
    val searchVisible: Boolean = false,
    val searchQuery: String = "",
    val allApps: List<InstalledAppCandidate> = emptyList(),
    val existingPackagesInSpace: Set<String> = emptySet(),
    val errorMessage: String? = null
) {
    val filteredApps: List<InstalledAppCandidate>
        get() {
            val q = searchQuery.trim().lowercase()
            if (q.isEmpty()) return allApps
            return allApps.filter {
                it.label.lowercase().contains(q) || it.packageName.lowercase().contains(q)
            }
        }
}

class CloneAppViewModel(
    private val spaceId: String,
    private val compatibilityChecker: AndroidCompatibilityChecker,
    private val virtualPackageManager: VirtualPackageManager,
    private val dispatchers: DispatcherProvider
) : ViewModel() {

    private val _uiState = MutableStateFlow(CloneAppUiState(spaceId = spaceId))
    val uiState: StateFlow<CloneAppUiState> = _uiState.asStateFlow()

    init {
        loadApps()
    }

    fun loadApps() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            runCatching {
                val installed = withContext(dispatchers.io) {
                    compatibilityChecker.queryInstalledLaunchableApps()
                }
                val existing = virtualPackageManager.observeAppsForSpace(spaceId).first()
                    .map { it.packageName }
                    .toSet()
                installed to existing
            }.onSuccess { (installed, existing) ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        allApps = installed,
                        existingPackagesInSpace = existing
                    )
                }
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = err.message ?: "Unable to query visible applications."
                    )
                }
            }
        }
    }

    fun toggleSearch() {
        _uiState.update {
            it.copy(
                searchVisible = !it.searchVisible,
                searchQuery = if (it.searchVisible) "" else it.searchQuery
            )
        }
    }

    fun onSearchQueryChange(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    companion object {
        fun factory(
            spaceId: String,
            compatibilityChecker: AndroidCompatibilityChecker,
            virtualPackageManager: VirtualPackageManager,
            dispatchers: DispatcherProvider
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return CloneAppViewModel(
                    spaceId = spaceId,
                    compatibilityChecker = compatibilityChecker,
                    virtualPackageManager = virtualPackageManager,
                    dispatchers = dispatchers
                ) as T
            }
        }
    }
}

data class ApkImportUiState(
    val spaceId: String = "",
    val isInspecting: Boolean = false,
    val selectedUriString: String? = null,
    val inspection: ApkInspectionResult? = null,
    val errorMessage: String? = null,
    val statusMessage: String? = null
)

class ApkImportViewModel(
    private val spaceId: String,
    private val compatibilityChecker: AndroidCompatibilityChecker,
    private val appInstaller: VirtualAppInstaller,
    private val dispatchers: DispatcherProvider,
    private val apkImportManager: ApkImportManager? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(ApkImportUiState(spaceId = spaceId))
    val uiState: StateFlow<ApkImportUiState> = _uiState.asStateFlow()

    fun inspectApk(uri: Uri) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isInspecting = true,
                    selectedUriString = uri.toString(),
                    inspection = null,
                    errorMessage = null,
                    statusMessage = null
                )
            }
            val result = withContext(dispatchers.io) {
                compatibilityChecker.inspectApkFromUri(uri)
            }
            when (result) {
                is AppResult.Success -> {
                    apkImportManager?.importApkFromUri(uri)
                    _uiState.update {
                        it.copy(isInspecting = false, inspection = result.data)
                    }
                }
                is AppResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            isInspecting = false,
                            errorMessage = "${result.error.message} ${result.error.recoveryHint.orEmpty()}".trim()
                        )
                    }
                }
            }
        }
    }

    fun registerInstalledApkInSpace() {
        val packageName = _uiState.value.inspection?.packageName ?: return
        viewModelScope.launch {
            when (val res = appInstaller.installIntoSpace(spaceId, packageName)) {
                is AppResult.Success -> {
                    _uiState.update {
                        it.copy(statusMessage = "Added ${res.data.label} into $spaceId.")
                    }
                }
                is AppResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            errorMessage = "Complete Android's system package installation first if not installed yet. (${res.error.message})"
                        )
                    }
                }
            }
        }
    }

    companion object {
        fun factory(
            spaceId: String,
            compatibilityChecker: AndroidCompatibilityChecker,
            appInstaller: VirtualAppInstaller,
            dispatchers: DispatcherProvider,
            apkImportManager: ApkImportManager? = null
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return ApkImportViewModel(
                    spaceId = spaceId,
                    compatibilityChecker = compatibilityChecker,
                    appInstaller = appInstaller,
                    dispatchers = dispatchers,
                    apkImportManager = apkImportManager
                ) as T
            }
        }
    }
}

sealed interface InstallStepStatus {
    data object Running : InstallStepStatus
    data class Completed(val app: SpaceApp, val report: CompatibilityReport?) : InstallStepStatus
    data class Failed(val message: String, val recoveryHint: String?) : InstallStepStatus
}

data class InstallationProgressUiState(
    val spaceId: String = "",
    val packageName: String = "",
    val currentStepLabel: String = "Reading package metadata...",
    val progressFraction: Float = 0.2f,
    val status: InstallStepStatus = InstallStepStatus.Running
)

class InstallationProgressViewModel(
    private val spaceId: String,
    private val packageName: String,
    private val appInstaller: VirtualAppInstaller
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        InstallationProgressUiState(spaceId = spaceId, packageName = packageName)
    )
    val uiState: StateFlow<InstallationProgressUiState> = _uiState.asStateFlow()

    init {
        startInstallation()
    }

    fun startInstallation() {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    currentStepLabel = "Inspecting metadata, ABI, SDK & storage...",
                    progressFraction = 0.35f,
                    status = InstallStepStatus.Running
                )
            }
            val reportResult = appInstaller.inspectInstalledPackage(packageName)
            val report = (reportResult as? AppResult.Success)?.data

            _uiState.update {
                it.copy(
                    currentStepLabel = "Configuring scoped space directories...",
                    progressFraction = 0.72f
                )
            }

            when (val res = appInstaller.installIntoSpace(spaceId, packageName)) {
                is AppResult.Success -> {
                    _uiState.update {
                        it.copy(
                            currentStepLabel = "Completed",
                            progressFraction = 1f,
                            status = InstallStepStatus.Completed(res.data, report)
                        )
                    }
                }
                is AppResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            currentStepLabel = "Installation halted",
                            progressFraction = 1f,
                            status = InstallStepStatus.Failed(
                                message = res.error.message,
                                recoveryHint = res.error.recoveryHint
                            )
                        )
                    }
                }
            }
        }
    }

    companion object {
        fun factory(
            spaceId: String,
            packageName: String,
            appInstaller: VirtualAppInstaller
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return InstallationProgressViewModel(spaceId, packageName, appInstaller) as T
            }
        }
    }
}

data class AppInfoUiState(
    val spaceId: String = "",
    val packageName: String = "",
    val app: SpaceApp? = null,
    val compatibilityReport: CompatibilityReport? = null,
    val isLoading: Boolean = true,
    val feedbackMessage: String? = null
)

class AppInformationViewModel(
    private val spaceId: String,
    private val packageName: String,
    private val virtualPackageManager: VirtualPackageManager,
    private val virtualProcessManager: VirtualProcessManager,
    private val storageManager: VirtualStorageManager,
    private val compatibilityChecker: AndroidCompatibilityChecker
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        AppInfoUiState(spaceId = spaceId, packageName = packageName)
    )
    val uiState: StateFlow<AppInfoUiState> = _uiState.asStateFlow()

    init {
        loadDetails()
    }

    private fun loadDetails() {
        viewModelScope.launch {
            val apps = virtualPackageManager.observeAppsForSpace(spaceId).first()
            val match = apps.firstOrNull { it.packageName == packageName }
            val report = (compatibilityChecker.evaluatePackage(packageName) as? AppResult.Success)?.data
            _uiState.update {
                it.copy(
                    app = match,
                    compatibilityReport = report,
                    isLoading = false
                )
            }
        }
    }

    fun launchApp() {
        viewModelScope.launch {
            when (val res = virtualProcessManager.startProcess(spaceId, packageName)) {
                is AppResult.Success -> _uiState.update { it.copy(feedbackMessage = "Launched $packageName.") }
                is AppResult.Failure -> _uiState.update { it.copy(feedbackMessage = res.error.message) }
            }
        }
    }

    fun clearScopedConfig() {
        viewModelScope.launch {
            storageManager.clearSpaceConfigCache(spaceId, packageName)
            _uiState.update { it.copy(feedbackMessage = "Cleared scoped VCamera configuration cache.") }
        }
    }

    fun removeApp(onRemoved: () -> Unit) {
        viewModelScope.launch {
            virtualPackageManager.removeApp(spaceId, packageName)
            onRemoved()
        }
    }

    fun clearFeedback() {
        _uiState.update { it.copy(feedbackMessage = null) }
    }

    companion object {
        fun factory(
            spaceId: String,
            packageName: String,
            virtualPackageManager: VirtualPackageManager,
            virtualProcessManager: VirtualProcessManager,
            storageManager: VirtualStorageManager,
            compatibilityChecker: AndroidCompatibilityChecker
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return AppInformationViewModel(
                    spaceId,
                    packageName,
                    virtualPackageManager,
                    virtualProcessManager,
                    storageManager,
                    compatibilityChecker
                ) as T
            }
        }
    }
}

fun drawableToPainter(drawable: Drawable?): Painter? {
    if (drawable == null) return null
    val bitmap = if (drawable is BitmapDrawable && drawable.bitmap != null) {
        drawable.bitmap
    } else {
        val width = drawable.intrinsicWidth.takeIf { it > 0 } ?: 96
        val height = drawable.intrinsicHeight.takeIf { it > 0 } ?: 96
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        bmp
    }
    return BitmapPainter(bitmap.asImageBitmap())
}
