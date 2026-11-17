package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.navigation.VCameraNavHost
import com.example.ui.theme.VCameraAppTheme
import com.vcameraspace.core.common.model.ThemePreference
import com.vcameraspace.core.datastore.UserPreferencesSnapshot

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val appContainer = (application as VCameraApplication).appContainer
        setContent {
            val prefs by appContainer.userPreferencesRepository.userPreferences
                .collectAsStateWithLifecycle(initialValue = UserPreferencesSnapshot())

            val useDarkTheme = when (prefs.themePreference) {
                ThemePreference.SYSTEM -> isSystemInDarkTheme()
                ThemePreference.LIGHT -> false
                ThemePreference.DARK -> true
            }

            VCameraAppTheme(darkTheme = useDarkTheme) {
                VCameraNavHost(
                    appContainer = appContainer,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}
