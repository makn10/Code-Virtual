package com.vcameraspace.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.vcameraspace.core.common.model.AppResult
import com.vcameraspace.core.common.model.Space
import com.vcameraspace.core.common.model.SpaceApp
import com.vcameraspace.core.database.repository.SpaceRepository
import com.vcameraspace.core.datastore.UserPreferencesRepository
import com.vcameraspace.core.datastore.UserPreferencesSnapshot
import com.vcameraspace.virtualengine.api.VirtualProcessManager
import com.vcameraspace.virtualengine.api.VirtualStorageManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class HomeOverflowAction(val label: String) {
    PREMIUM("Premium"),
    ADVANCE_FEATURE("Advance Feature"),
    VIRTUAL_CAMERA_SETTING("Virtual Camera Setting"),
    GOOGLE_FRAMEWORK("Google Framework"),
    KILL_ALL_APPS("Kill All Apps"),
    RATE_US("Rate us"),
    ABOUT("About"),
    GITHUB("Github"),
    SHARE("Share"),
    CONTACT_US("Contact US"),
    SWITCH_TO_NORMAL("Switch to Normal")
}

enum class AppLongPressAction(val label: String, val isDestructive: Boolean = false) {
    OPEN("Open"),
    KILL_APP("Kill App", isDestructive = true),
    CLEAR_DATA("Clear Data", isDestructive = true),
    REMOVE("Remove", isDestructive = true),
    APP_INFORMATION("App Information"),
    CAMERA_SETTINGS("Camera Settings"),
    ADD_SHORTCUT("Add Shortcut")
}

data class HomeUiState(
    val spaces: List<Space> = defaultFoundationSpaces(),
    val activeSpaceIndex: Int = 0,
    val appsBySpaceId: Map<String, List<SpaceApp>> = emptyMap(),
    val normalModeEnabled: Boolean = false,
    val statusBannerMessage: String? = null
) {
    val activeSpace: Space
        get() = spaces.getOrElse(activeSpaceIndex) { spaces.first() }

    companion object {
        fun defaultFoundationSpaces(): List<Space> = listOf(
            Space(
                id = UserPreferencesSnapshot.DEFAULT_SPACE_ID,
                name = "Space 1",
                iconKey = "layers",
                isDefault = true,
                sortOrder = 0
            ),
            Space(
                id = "space_2",
                name = "Space 2",
                iconKey = "work",
                isDefault = false,
                sortOrder = 1
            )
        )
    }
}

class HomeViewModel(
    private val spaceRepository: SpaceRepository,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val virtualProcessManager: VirtualProcessManager? = null,
    private val virtualStorageManager: VirtualStorageManager? = null
) : ViewModel() {

    private val _bannerMessage = MutableStateFlow<String?>(null)

    init {
        viewModelScope.launch {
            spaceRepository.ensureDefaultSpacesSeeded()
        }
    }

    val uiState: StateFlow<HomeUiState> = combine(
        spaceRepository.observeSpaces(),
        spaceRepository.observeAppsBySpace(),
        userPreferencesRepository.userPreferences,
        _bannerMessage
    ) { dbSpaces, appsBySpace, prefs, banner ->
        val spaces = dbSpaces.ifEmpty { HomeUiState.defaultFoundationSpaces() }
        val activeIdx = spaces.indexOfFirst { it.id == prefs.activeSpaceId }.let { idx ->
            if (idx >= 0) idx else spaces.indexOfFirst { it.isDefault }.coerceAtLeast(0)
        }
        HomeUiState(
            spaces = spaces,
            activeSpaceIndex = activeIdx,
            appsBySpaceId = appsBySpace,
            normalModeEnabled = prefs.normalModeEnabled,
            statusBannerMessage = banner
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = HomeUiState()
    )

    fun onPageSelected(index: Int) {
        val space = uiState.value.spaces.getOrNull(index) ?: return
        viewModelScope.launch {
            userPreferencesRepository.setActiveSpaceId(space.id)
        }
    }

    fun toggleNormalMode() {
        viewModelScope.launch {
            val next = !uiState.value.normalModeEnabled
            userPreferencesRepository.setNormalModeEnabled(next)
            _bannerMessage.value = if (next) {
                "Normal mode active. Virtual workspace shortcuts are paused."
            } else {
                "Returned to VCamera Space workspace mode."
            }
        }
    }

    fun killAllSupportedProcesses() {
        viewModelScope.launch {
            val stopped = (virtualProcessManager?.stopAllProcesses() as? AppResult.Success)?.data ?: 0
            _bannerMessage.value = if (stopped > 0) {
                "Stopped $stopped active supported process(es) in VCamera Space."
            } else {
                "All tracked supported processes are already stopped."
            }
        }
    }

    fun handleAppAction(app: SpaceApp, action: AppLongPressAction) {
        viewModelScope.launch {
            when (action) {
                AppLongPressAction.OPEN -> {
                    val procManager = virtualProcessManager
                    if (procManager != null) {
                        when (val res = procManager.startProcess(app.spaceId, app.packageName)) {
                            is AppResult.Success -> _bannerMessage.value = "Launched ${app.label} in ${app.spaceId}."
                            is AppResult.Failure -> _bannerMessage.value = res.error.message
                        }
                    } else {
                        _bannerMessage.value = "Prepared launch for ${app.label}."
                    }
                }
                AppLongPressAction.REMOVE -> {
                    when (val res = spaceRepository.removeAppFromSpace(app.spaceId, app.packageName)) {
                        is AppResult.Success -> _bannerMessage.value = "Removed ${app.label} from workspace."
                        is AppResult.Failure -> _bannerMessage.value = res.error.message
                    }
                }
                AppLongPressAction.KILL_APP -> {
                    virtualProcessManager?.stopProcess(app.spaceId, app.packageName)
                    _bannerMessage.value = "Stopped supported process record for ${app.label}."
                }
                AppLongPressAction.CLEAR_DATA -> {
                    virtualStorageManager?.clearSpaceConfigCache(app.spaceId, app.packageName)
                    _bannerMessage.value = "Cleared scoped VCamera config cache for ${app.label}."
                }
                AppLongPressAction.ADD_SHORTCUT -> {
                    _bannerMessage.value = "Pinned shortcut request prepared for ${app.label}."
                }
                else -> Unit
            }
        }
    }

    fun dismissBanner() {
        _bannerMessage.value = null
    }

    companion object {
        fun factory(
            spaceRepository: SpaceRepository,
            userPreferencesRepository: UserPreferencesRepository,
            virtualProcessManager: VirtualProcessManager? = null,
            virtualStorageManager: VirtualStorageManager? = null
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return HomeViewModel(
                    spaceRepository = spaceRepository,
                    userPreferencesRepository = userPreferencesRepository,
                    virtualProcessManager = virtualProcessManager,
                    virtualStorageManager = virtualStorageManager
                ) as T
            }
        }
    }
}
