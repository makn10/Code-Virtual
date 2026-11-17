package com.vcameraspace.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vcameraspace.core.common.model.ThemePreference
import com.vcameraspace.core.database.repository.SpaceRepository
import com.vcameraspace.core.datastore.UserPreferencesRepository
import com.vcameraspace.core.datastore.UserPreferencesSnapshot
import com.vcameraspace.core.designsystem.components.CustomDropdown
import com.vcameraspace.core.designsystem.components.CustomToolbar
import com.vcameraspace.core.designsystem.components.SectionHeader
import com.vcameraspace.core.designsystem.components.SettingSwitch
import kotlinx.coroutines.launch

@Composable
fun GeneralSettingsScreen(
    userPreferencesRepository: UserPreferencesRepository,
    spaceRepository: SpaceRepository,
    onBackClick: () -> Unit,
    onOpenSpaceManager: () -> Unit,
    onOpenPrivacyDashboard: () -> Unit,
    onOpenPerformanceMonitor: () -> Unit,
    onOpenBackupRestore: () -> Unit,
    onOpenAbout: () -> Unit,
    modifier: Modifier = Modifier
) {
    val prefs by userPreferencesRepository.userPreferences
        .collectAsStateWithLifecycle(initialValue = UserPreferencesSnapshot())
    val spaces by spaceRepository.observeSpaces()
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val coroutineScope = rememberCoroutineScope()

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("general_settings_screen"),
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            CustomToolbar(
                title = "General Settings",
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
            item {
                SectionHeader(
                    title = "Appearance & Workspace",
                    subtitle = "Persisted via DataStore"
                )
            }

            item {
                CustomDropdown(
                    selectedItem = prefs.themePreference,
                    items = ThemePreference.entries,
                    itemLabel = {
                        when (it) {
                            ThemePreference.SYSTEM -> "Follow System Theme"
                            ThemePreference.LIGHT -> "Light Theme"
                            ThemePreference.DARK -> "Dark Theme"
                        }
                    },
                    onItemSelected = { selected ->
                        coroutineScope.launch { userPreferencesRepository.setThemePreference(selected) }
                    },
                    label = "Theme Mode",
                    testTag = "settings_theme_dropdown"
                )
            }

            if (spaces.isNotEmpty()) {
                item {
                    val currentDefault = spaces.firstOrNull { it.id == prefs.defaultSpaceId } ?: spaces.first()
                    CustomDropdown(
                        selectedItem = currentDefault,
                        items = spaces,
                        itemLabel = { it.name },
                        onItemSelected = { selected ->
                            coroutineScope.launch { spaceRepository.setDefaultSpace(selected.id) }
                        },
                        label = "Default Startup Space",
                        testTag = "settings_default_space_dropdown"
                    )
                }
            }

            item {
                SettingSwitch(
                    title = "Normal Mode",
                    subtitle = "Pause virtual workspace shortcuts while keeping configurations saved",
                    checked = prefs.normalModeEnabled,
                    onCheckedChange = { enabled ->
                        coroutineScope.launch { userPreferencesRepository.setNormalModeEnabled(enabled) }
                    }
                )
            }

            item {
                SectionHeader(
                    title = "System Modules",
                    subtitle = "Direct management tools"
                )
            }

            item {
                OutlinedButton(onClick = onOpenSpaceManager, modifier = Modifier.fillMaxWidth()) {
                    Text("Space Manager")
                }
            }
            item {
                OutlinedButton(onClick = onOpenPrivacyDashboard, modifier = Modifier.fillMaxWidth()) {
                    Text("Privacy Dashboard")
                }
            }
            item {
                OutlinedButton(onClick = onOpenPerformanceMonitor, modifier = Modifier.fillMaxWidth()) {
                    Text("Performance Monitor")
                }
            }
            item {
                OutlinedButton(onClick = onOpenBackupRestore, modifier = Modifier.fillMaxWidth()) {
                    Text("Backup and Restore")
                }
            }
            item {
                OutlinedButton(onClick = onOpenAbout, modifier = Modifier.fillMaxWidth()) {
                    Text("About VCamera Space")
                }
            }
        }
    }
}
