package com.vcameraspace.feature.splash

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.vcameraspace.core.datastore.UserPreferencesRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

sealed interface SplashNavigationTarget {
    data object Loading : SplashNavigationTarget
    data object Onboarding : SplashNavigationTarget
    data object Home : SplashNavigationTarget
}

class SplashViewModel(
    private val userPreferencesRepository: UserPreferencesRepository
) : ViewModel() {

    private val _target = MutableStateFlow<SplashNavigationTarget>(SplashNavigationTarget.Loading)
    val target: StateFlow<SplashNavigationTarget> = _target.asStateFlow()

    init {
        resolveStartDestination()
    }

    private fun resolveStartDestination() {
        viewModelScope.launch {
            val prefs = userPreferencesRepository.userPreferences.first()
            delay(850L)
            _target.value = if (prefs.onboardingCompleted) {
                SplashNavigationTarget.Home
            } else {
                SplashNavigationTarget.Onboarding
            }
        }
    }

    companion object {
        fun factory(
            userPreferencesRepository: UserPreferencesRepository
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return SplashViewModel(userPreferencesRepository) as T
            }
        }
    }
}
