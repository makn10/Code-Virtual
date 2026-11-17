package com.vcameraspace.feature.spaces

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.vcameraspace.core.common.model.AppResult
import com.vcameraspace.core.common.model.Space
import com.vcameraspace.core.database.repository.SpaceRepository
import com.vcameraspace.core.datastore.UserPreferencesRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SpaceManagerUiState(
    val spaces: List<Space> = emptyList(),
    val activeSpaceId: String = "",
    val isLoading: Boolean = true,
    val feedbackMessage: String? = null,
    val errorMessage: String? = null
)

class SpaceManagerViewModel(
    private val spaceRepository: SpaceRepository,
    private val userPreferencesRepository: UserPreferencesRepository
) : ViewModel() {

    private val _feedback = MutableStateFlow<String?>(null)
    private val _error = MutableStateFlow<String?>(null)

    init {
        viewModelScope.launch {
            spaceRepository.ensureDefaultSpacesSeeded()
        }
    }

    val uiState: StateFlow<SpaceManagerUiState> = combine(
        spaceRepository.observeSpaces(),
        userPreferencesRepository.userPreferences,
        _feedback,
        _error
    ) { spaces, prefs, feedback, error ->
        SpaceManagerUiState(
            spaces = spaces,
            activeSpaceId = prefs.activeSpaceId,
            isLoading = false,
            feedbackMessage = feedback,
            errorMessage = error
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SpaceManagerUiState()
    )

    fun createSpace(name: String, iconKey: String, setAsDefault: Boolean) {
        viewModelScope.launch {
            when (val res = spaceRepository.createSpace(name, iconKey, setAsDefault)) {
                is AppResult.Success -> {
                    _feedback.value = "Created workspace \"${res.data.name}\"."
                }
                is AppResult.Failure -> {
                    _error.value = res.error.message
                }
            }
        }
    }

    fun renameSpace(spaceId: String, newName: String) {
        viewModelScope.launch {
            when (val res = spaceRepository.renameSpace(spaceId, newName)) {
                is AppResult.Success -> {
                    _feedback.value = "Updated workspace name to \"${newName.trim()}\"."
                }
                is AppResult.Failure -> {
                    _error.value = res.error.message
                }
            }
        }
    }

    fun updateSpaceIcon(spaceId: String, iconKey: String) {
        viewModelScope.launch {
            when (val res = spaceRepository.updateSpaceIcon(spaceId, iconKey)) {
                is AppResult.Success -> {
                    _feedback.value = "Workspace icon updated."
                }
                is AppResult.Failure -> {
                    _error.value = res.error.message
                }
            }
        }
    }

    fun setDefaultSpace(spaceId: String) {
        viewModelScope.launch {
            when (val res = spaceRepository.setDefaultSpace(spaceId)) {
                is AppResult.Success -> {
                    _feedback.value = "Default launch workspace updated."
                }
                is AppResult.Failure -> {
                    _error.value = res.error.message
                }
            }
        }
    }

    fun activateSpace(spaceId: String) {
        viewModelScope.launch {
            userPreferencesRepository.setActiveSpaceId(spaceId)
        }
    }

    fun moveSpaceUp(spaceId: String) {
        viewModelScope.launch {
            val res = spaceRepository.moveSpace(spaceId, -1)
            if (res is AppResult.Failure) _error.value = res.error.message
        }
    }

    fun moveSpaceDown(spaceId: String) {
        viewModelScope.launch {
            val res = spaceRepository.moveSpace(spaceId, +1)
            if (res is AppResult.Failure) _error.value = res.error.message
        }
    }

    fun updateSpaceSettings(
        spaceId: String,
        isolatedCameraByDefault: Boolean,
        requireLaunchConfirm: Boolean
    ) {
        viewModelScope.launch {
            when (
                val res = spaceRepository.updateSpaceSettings(
                    spaceId = spaceId,
                    isolatedCameraByDefault = isolatedCameraByDefault,
                    requireLaunchConfirm = requireLaunchConfirm
                )
            ) {
                is AppResult.Success -> _feedback.value = "Space settings saved."
                is AppResult.Failure -> _error.value = res.error.message
            }
        }
    }

    fun deleteSpace(spaceId: String) {
        viewModelScope.launch {
            when (val res = spaceRepository.deleteSpace(spaceId)) {
                is AppResult.Success -> {
                    _feedback.value = "Workspace deleted along with its scoped configurations."
                }
                is AppResult.Failure -> {
                    _error.value = res.error.message
                }
            }
        }
    }

    fun clearMessages() {
        _feedback.value = null
        _error.value = null
    }

    companion object {
        fun factory(
            spaceRepository: SpaceRepository,
            userPreferencesRepository: UserPreferencesRepository
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return SpaceManagerViewModel(spaceRepository, userPreferencesRepository) as T
            }
        }
    }
}
