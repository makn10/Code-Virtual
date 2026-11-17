package com.vcameraspace.feature.clone

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.vcameraspace.core.common.diagnostics.DiagnosticCategory
import com.vcameraspace.core.common.diagnostics.DiagnosticLogger
import com.vcameraspace.core.common.model.AppResult
import com.vcameraspace.core.common.model.Space
import com.vcameraspace.core.database.repository.SpaceRepository
import com.vcameraspace.virtualengine.api.VirtualAppInstaller
import com.vcameraspace.virtualengine.impl.AndroidCompatibilityChecker
import com.vcameraspace.virtualengine.impl.ApkImportManager
import com.vcameraspace.virtualengine.impl.ImportedApkRecord
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ImportedAppsUiState(
    val importedApks: List<ImportedApkRecord> = emptyList(),
    val spaces: List<Space> = emptyList(),
    val installedPackageNames: Set<String> = emptySet(),
    val isProcessing: Boolean = false,
    val statusMessage: String? = null,
    val errorMessage: String? = null
)

class ImportedAppsViewModel(
    private val apkImportManager: ApkImportManager,
    private val spaceRepository: SpaceRepository,
    private val appInstaller: VirtualAppInstaller,
    private val compatibilityChecker: AndroidCompatibilityChecker,
    private val logger: DiagnosticLogger? = null
) : ViewModel() {

    private val _status = MutableStateFlow<Triple<Boolean, String?, String?>>(Triple(false, null, null))
    private val _installedPkgs = MutableStateFlow<Set<String>>(emptySet())

    val uiState: StateFlow<ImportedAppsUiState> = combine(
        apkImportManager.observeImportedApks(),
        spaceRepository.observeSpaces(),
        _installedPkgs,
        _status
    ) { apks, spaces, installed, status ->
        ImportedAppsUiState(
            importedApks = apks,
            spaces = spaces,
            installedPackageNames = installed,
            isProcessing = status.first,
            statusMessage = status.second,
            errorMessage = status.third
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ImportedAppsUiState()
    )

    init {
        refreshInstalledPackages()
    }

    fun refreshInstalledPackages() {
        viewModelScope.launch {
            val installed = runCatching {
                compatibilityChecker.queryInstalledLaunchableApps().map { it.packageName }.toSet()
            }.getOrDefault(emptySet())
            _installedPkgs.value = installed
        }
    }

    fun importApk(uri: Uri) {
        viewModelScope.launch {
            _status.value = Triple(true, null, null)
            when (val res = apkImportManager.importApkFromUri(uri)) {
                is AppResult.Success -> {
                    _status.value = Triple(
                        false,
                        "Imported ${res.data.appLabel} v${res.data.versionName} into private storage.",
                        null
                    )
                    refreshInstalledPackages()
                }
                is AppResult.Failure -> {
                    _status.value = Triple(false, null, res.error.message)
                }
            }
        }
    }

    fun revalidateApk(id: String) {
        viewModelScope.launch {
            when (val res = apkImportManager.revalidateImportedApk(id)) {
                is AppResult.Success -> {
                    _status.value = Triple(false, "${res.data.appLabel}: ${res.data.compatibilitySummary}", null)
                }
                is AppResult.Failure -> {
                    _status.value = Triple(false, null, res.error.message)
                }
            }
        }
    }

    fun removeApk(id: String) {
        viewModelScope.launch {
            when (val res = apkImportManager.removeImportedApk(id)) {
                is AppResult.Success -> {
                    _status.value = Triple(false, "Removed imported APK from private storage.", null)
                }
                is AppResult.Failure -> {
                    _status.value = Triple(false, null, res.error.message)
                }
            }
        }
    }

    fun addToSpace(packageName: String, spaceId: String) {
        viewModelScope.launch {
            when (val res = appInstaller.installIntoSpace(spaceId, packageName)) {
                is AppResult.Success -> {
                    logger?.logInfo(DiagnosticCategory.VIRTUAL_APPS, "Registered $packageName in $spaceId")
                    _status.value = Triple(false, "Added ${res.data.label} into $spaceId.", null)
                }
                is AppResult.Failure -> {
                    _status.value = Triple(
                        false,
                        null,
                        "Package $packageName must be installed on Android before registering inside a virtual workspace. (${res.error.message})"
                    )
                }
            }
        }
    }

    fun clearBanner() {
        _status.update { it.copy(second = null, third = null) }
    }

    companion object {
        fun factory(
            apkImportManager: ApkImportManager,
            spaceRepository: SpaceRepository,
            appInstaller: VirtualAppInstaller,
            compatibilityChecker: AndroidCompatibilityChecker,
            logger: DiagnosticLogger? = null
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return ImportedAppsViewModel(
                    apkImportManager = apkImportManager,
                    spaceRepository = spaceRepository,
                    appInstaller = appInstaller,
                    compatibilityChecker = compatibilityChecker,
                    logger = logger
                ) as T
            }
        }
    }
}
