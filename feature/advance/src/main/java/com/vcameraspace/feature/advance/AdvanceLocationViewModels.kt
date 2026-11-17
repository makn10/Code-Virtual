package com.vcameraspace.feature.advance

import android.content.Context
import android.location.Geocoder
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.vcameraspace.core.common.coroutines.DispatcherProvider
import com.vcameraspace.core.common.model.AppResult
import com.vcameraspace.core.common.model.SpaceApp
import com.vcameraspace.core.database.entity.LocationConfigEntity
import com.vcameraspace.core.database.repository.SpaceRepository
import com.vcameraspace.core.datastore.UserPreferencesRepository
import com.vcameraspace.virtualengine.api.LocationPoint
import com.vcameraspace.virtualengine.impl.AndroidVirtualLocationProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

data class AdvanceFeatureUiState(
    val mockLocationEnabled: Boolean = false,
    val activeSpaceId: String = "space_1",
    val isDeveloperMockReady: Boolean = false
)

class AdvanceFeatureViewModel(
    private val userPreferencesRepository: UserPreferencesRepository,
    private val locationProvider: AndroidVirtualLocationProvider
) : ViewModel() {

    private val _mockReady = MutableStateFlow(false)

    init {
        refreshMockReadiness()
    }

    val uiState: StateFlow<AdvanceFeatureUiState> = combine(
        userPreferencesRepository.userPreferences,
        _mockReady
    ) { prefs, ready ->
        AdvanceFeatureUiState(
            mockLocationEnabled = prefs.mockLocationEnabled,
            activeSpaceId = prefs.activeSpaceId,
            isDeveloperMockReady = ready
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = AdvanceFeatureUiState()
    )

    fun setMockLocationEnabled(enabled: Boolean) {
        viewModelScope.launch {
            userPreferencesRepository.setMockLocationEnabled(enabled)
            refreshMockReadiness()
        }
    }

    fun refreshMockReadiness() {
        viewModelScope.launch {
            val res = locationProvider.isMockLocationReady()
            _mockReady.value = (res as? AppResult.Success)?.data ?: false
        }
    }

    companion object {
        fun factory(
            userPreferencesRepository: UserPreferencesRepository,
            locationProvider: AndroidVirtualLocationProvider
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return AdvanceFeatureViewModel(userPreferencesRepository, locationProvider) as T
            }
        }
    }
}

data class LocationManagerUiState(
    val spaceId: String = "",
    val apps: List<SpaceApp> = emptyList(),
    val locationConfigs: Map<String, LocationConfigEntity> = emptyMap()
)

class LocationManagerViewModel(
    private val spaceId: String,
    private val spaceRepository: SpaceRepository,
    private val locationProvider: AndroidVirtualLocationProvider
) : ViewModel() {

    val uiState: StateFlow<LocationManagerUiState> = combine(
        spaceRepository.observeAppsBySpace(),
        locationProvider.observeSpaceLocations(spaceId)
    ) { appsMap, configs ->
        LocationManagerUiState(
            spaceId = spaceId,
            apps = appsMap[spaceId].orEmpty(),
            locationConfigs = configs.associateBy { it.packageName }
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = LocationManagerUiState(spaceId = spaceId)
    )

    companion object {
        fun factory(
            spaceId: String,
            spaceRepository: SpaceRepository,
            locationProvider: AndroidVirtualLocationProvider
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return LocationManagerViewModel(spaceId, spaceRepository, locationProvider) as T
            }
        }
    }
}

data class MapPickerUiState(
    val spaceId: String = "",
    val packageName: String = "",
    val searchQuery: String = "",
    val latitude: Double = 37.7749,
    val longitude: Double = -122.4194,
    val latitudeInput: String = "37.7749",
    val longitudeInput: String = "-122.4194",
    val selectedLocationLabel: String = "San Francisco, CA",
    val isSearching: Boolean = false,
    val mockReady: Boolean = false,
    val feedbackMessage: String? = null
)

class MapPickerViewModel(
    private val context: Context,
    private val spaceId: String,
    private val packageName: String,
    private val locationProvider: AndroidVirtualLocationProvider,
    private val dispatchers: DispatcherProvider
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        MapPickerUiState(spaceId = spaceId, packageName = packageName)
    )
    val uiState: StateFlow<MapPickerUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val readiness = locationProvider.isMockLocationReady()
            val ready = (readiness as? AppResult.Success)?.data ?: false
            _uiState.update { it.copy(mockReady = ready) }
        }
    }

    fun onSearchQueryChange(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun onPinSelected(lat: Double, lng: Double, label: String? = null) {
        val clampedLat = lat.coerceIn(-85.0, 85.0)
        val clampedLng = lng.coerceIn(-180.0, 180.0)
        _uiState.update {
            it.copy(
                latitude = clampedLat,
                longitude = clampedLng,
                latitudeInput = String.format(Locale.US, "%.5f", clampedLat),
                longitudeInput = String.format(Locale.US, "%.5f", clampedLng),
                selectedLocationLabel = label ?: String.format(
                    Locale.US,
                    "%.4f, %.4f",
                    clampedLat,
                    clampedLng
                )
            )
        }
    }

    fun onCoordinateInputsChange(latStr: String, lngStr: String) {
        val parsedLat = latStr.toDoubleOrNull()
        val parsedLng = lngStr.toDoubleOrNull()
        _uiState.update { state ->
            state.copy(
                latitudeInput = latStr,
                longitudeInput = lngStr,
                latitude = parsedLat?.coerceIn(-85.0, 85.0) ?: state.latitude,
                longitude = parsedLng?.coerceIn(-180.0, 180.0) ?: state.longitude
            )
        }
    }

    fun searchLocation() {
        val query = _uiState.value.searchQuery.trim()
        if (query.isEmpty()) return

        // Direct coordinate pair support ("lat, lng")
        val parts = query.split(",").mapNotNull { it.trim().toDoubleOrNull() }
        if (parts.size == 2) {
            onPinSelected(parts[0], parts[1], "Coordinates (${parts[0]}, ${parts[1]})")
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isSearching = true) }
            val result = withContext(dispatchers.io) {
                runCatching {
                    @Suppress("DEPRECATION")
                    if (Geocoder.isPresent()) {
                        val geocoder = Geocoder(context, Locale.getDefault())
                        geocoder.getFromLocationName(query, 1)?.firstOrNull()
                    } else null
                }.getOrNull()
            }
            if (result != null) {
                onPinSelected(
                    lat = result.latitude,
                    lng = result.longitude,
                    label = result.getAddressLine(0) ?: query
                )
                _uiState.update { it.copy(isSearching = false) }
            } else {
                _uiState.update {
                    it.copy(
                        isSearching = false,
                        feedbackMessage = "Geocoder unavailable or no match for \"$query\". Tap the map or enter coordinates directly."
                    )
                }
            }
        }
    }

    fun saveLocation(onSaved: () -> Unit) {
        val state = _uiState.value
        viewModelScope.launch {
            when (
                val res = locationProvider.setSpaceAppLocation(
                    spaceId = spaceId,
                    packageName = packageName,
                    point = LocationPoint(
                        latitude = state.latitude,
                        longitude = state.longitude,
                        label = state.selectedLocationLabel
                    )
                )
            ) {
                is AppResult.Success -> onSaved()
                is AppResult.Failure -> {
                    _uiState.update { it.copy(feedbackMessage = res.error.message) }
                }
            }
        }
    }

    fun clearFeedback() {
        _uiState.update { it.copy(feedbackMessage = null) }
    }

    companion object {
        fun factory(
            context: Context,
            spaceId: String,
            packageName: String,
            locationProvider: AndroidVirtualLocationProvider,
            dispatchers: DispatcherProvider
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return MapPickerViewModel(
                    context = context.applicationContext,
                    spaceId = spaceId,
                    packageName = packageName,
                    locationProvider = locationProvider,
                    dispatchers = dispatchers
                ) as T
            }
        }
    }
}
