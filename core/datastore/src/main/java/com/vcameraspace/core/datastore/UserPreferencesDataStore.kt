package com.vcameraspace.core.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.vcameraspace.core.common.model.ThemePreference
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.vcameraPreferencesDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "vcamera_space_preferences"
)

data class UserPreferencesSnapshot(
    val onboardingCompleted: Boolean = false,
    val defaultSpaceId: String = DEFAULT_SPACE_ID,
    val activeSpaceId: String = DEFAULT_SPACE_ID,
    val themePreference: ThemePreference = ThemePreference.SYSTEM,
    val mockLocationEnabled: Boolean = false,
    val normalModeEnabled: Boolean = false
) {
    companion object {
        const val DEFAULT_SPACE_ID = "space_1"
    }
}

interface UserPreferencesRepository {
    val userPreferences: Flow<UserPreferencesSnapshot>
    suspend fun setOnboardingCompleted(completed: Boolean)
    suspend fun setDefaultSpaceId(spaceId: String)
    suspend fun setActiveSpaceId(spaceId: String)
    suspend fun setThemePreference(preference: ThemePreference)
    suspend fun setMockLocationEnabled(enabled: Boolean)
    suspend fun setNormalModeEnabled(enabled: Boolean)
}

class DataStoreUserPreferencesRepository(
    private val dataStore: DataStore<Preferences>
) : UserPreferencesRepository {

    constructor(context: Context) : this(context.applicationContext.vcameraPreferencesDataStore)

    private object Keys {
        val ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
        val DEFAULT_SPACE_ID = stringPreferencesKey("default_space_id")
        val ACTIVE_SPACE_ID = stringPreferencesKey("active_space_id")
        val THEME_PREFERENCE = stringPreferencesKey("theme_preference")
        val MOCK_LOCATION_ENABLED = booleanPreferencesKey("mock_location_enabled")
        val NORMAL_MODE_ENABLED = booleanPreferencesKey("normal_mode_enabled")
    }

    override val userPreferences: Flow<UserPreferencesSnapshot> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { prefs ->
            val themeName = prefs[Keys.THEME_PREFERENCE] ?: ThemePreference.SYSTEM.name
            val theme = runCatching { ThemePreference.valueOf(themeName) }
                .getOrDefault(ThemePreference.SYSTEM)
            UserPreferencesSnapshot(
                onboardingCompleted = prefs[Keys.ONBOARDING_COMPLETED] ?: false,
                defaultSpaceId = prefs[Keys.DEFAULT_SPACE_ID] ?: UserPreferencesSnapshot.DEFAULT_SPACE_ID,
                activeSpaceId = prefs[Keys.ACTIVE_SPACE_ID] ?: UserPreferencesSnapshot.DEFAULT_SPACE_ID,
                themePreference = theme,
                mockLocationEnabled = prefs[Keys.MOCK_LOCATION_ENABLED] ?: false,
                normalModeEnabled = prefs[Keys.NORMAL_MODE_ENABLED] ?: false
            )
        }

    override suspend fun setOnboardingCompleted(completed: Boolean) {
        dataStore.edit { it[Keys.ONBOARDING_COMPLETED] = completed }
    }

    override suspend fun setDefaultSpaceId(spaceId: String) {
        dataStore.edit { it[Keys.DEFAULT_SPACE_ID] = spaceId }
    }

    override suspend fun setActiveSpaceId(spaceId: String) {
        dataStore.edit { it[Keys.ACTIVE_SPACE_ID] = spaceId }
    }

    override suspend fun setThemePreference(preference: ThemePreference) {
        dataStore.edit { it[Keys.THEME_PREFERENCE] = preference.name }
    }

    override suspend fun setMockLocationEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.MOCK_LOCATION_ENABLED] = enabled }
    }

    override suspend fun setNormalModeEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.NORMAL_MODE_ENABLED] = enabled }
    }
}
