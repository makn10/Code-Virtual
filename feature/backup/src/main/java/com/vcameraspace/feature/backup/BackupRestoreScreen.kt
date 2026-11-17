package com.vcameraspace.feature.backup

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.vcameraspace.core.common.coroutines.DispatcherProvider
import com.vcameraspace.core.common.model.ExecutionArchitecture
import com.vcameraspace.core.common.model.ThemePreference
import com.vcameraspace.core.common.model.VirtualAppStatus
import com.vcameraspace.core.database.VCameraDatabase
import com.vcameraspace.core.database.entity.CameraConfigEntity
import com.vcameraspace.core.database.entity.LocationConfigEntity
import com.vcameraspace.core.database.entity.SpaceEntity
import com.vcameraspace.core.database.entity.VirtualAppEntity
import com.vcameraspace.core.datastore.UserPreferencesRepository
import com.vcameraspace.core.designsystem.components.CustomToolbar
import com.vcameraspace.core.designsystem.components.PrimaryButton
import com.vcameraspace.core.designsystem.components.StatusCard
import com.vcameraspace.core.designsystem.components.StatusSeverity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class BackupSpaceDto(
    val id: String,
    val name: String,
    val iconKey: String,
    val isDefault: Boolean,
    val sortOrder: Int,
    val isolatedCameraByDefault: Boolean = true,
    val requireLaunchConfirm: Boolean = false
)

@Serializable
data class BackupAppDto(
    val id: String,
    val spaceId: String,
    val packageName: String,
    val label: String,
    val versionName: String,
    val architecture: String
)

@Serializable
data class BackupCameraConfigDto(
    val id: String,
    val spaceId: String,
    val packageName: String? = null,
    val mode: String,
    val sourceUri: String? = null,
    val enableAudio: Boolean = false,
    val width: Int = 0,
    val height: Int = 0,
    val orientationDegrees: Int = 0,
    val autoReconnect: Boolean = true
)

@Serializable
data class BackupLocationConfigDto(
    val id: String,
    val spaceId: String,
    val packageName: String,
    val latitude: Double,
    val longitude: Double,
    val locationLabel: String? = null,
    val enabled: Boolean = true
)

@Serializable
data class BackupPreferencesDto(
    val themePreference: String = ThemePreference.SYSTEM.name,
    val defaultSpaceId: String = "space_1",
    val mockLocationEnabled: Boolean = false
)

@Serializable
data class VCameraBackupDocument(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val exportedAtEpochMs: Long,
    val spaces: List<BackupSpaceDto>,
    val apps: List<BackupAppDto>,
    val cameraConfigs: List<BackupCameraConfigDto>,
    val locationConfigs: List<BackupLocationConfigDto>,
    val preferences: BackupPreferencesDto
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
    }
}

object BackupJsonCodec {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(doc: VCameraBackupDocument): String = json.encodeToString(doc)

    fun decodeAndValidate(rawJson: String): Result<VCameraBackupDocument> {
        return runCatching {
            val doc = json.decodeFromString<VCameraBackupDocument>(rawJson)
            require(doc.schemaVersion in 1..VCameraBackupDocument.CURRENT_SCHEMA_VERSION) {
                "Unsupported backup schema version: ${doc.schemaVersion}"
            }
            require(doc.spaces.isNotEmpty()) {
                "Backup file contains no workspaces."
            }
            doc
        }
    }
}

data class BackupUiState(
    val isBusy: Boolean = false,
    val lastSummary: String? = null,
    val feedbackMessage: String? = null
)

class BackupRestoreViewModel(
    private val context: Context,
    private val database: VCameraDatabase,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val dispatchers: DispatcherProvider
) : ViewModel() {

    private val _uiState = MutableStateFlow(BackupUiState())
    val uiState: StateFlow<BackupUiState> = _uiState.asStateFlow()

    fun exportToUri(uri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true) }
            val result = withContext(dispatchers.io) {
                runCatching {
                    val spaceDao = database.spaceDao()
                    val appDao = database.virtualAppDao()
                    val camDao = database.cameraConfigDao()
                    val locDao = database.locationConfigDao()

                    val spaces = spaceDao.getAllSpacesOnce()
                    val apps = appDao.observeAllApps().first()
                    val camConfigs = spaces.flatMap { camDao.observeConfigsForSpace(it.id).first() }
                    val locConfigs = spaces.flatMap { locDao.observeConfigsForSpace(it.id).first() }
                    val prefs = userPreferencesRepository.userPreferences.first()

                    val doc = VCameraBackupDocument(
                        exportedAtEpochMs = System.currentTimeMillis(),
                        spaces = spaces.map {
                            BackupSpaceDto(
                                id = it.id,
                                name = it.name,
                                iconKey = it.iconKey,
                                isDefault = it.isDefault,
                                sortOrder = it.sortOrder,
                                isolatedCameraByDefault = it.isolatedCameraByDefault,
                                requireLaunchConfirm = it.requireLaunchConfirm
                            )
                        },
                        apps = apps.map {
                            BackupAppDto(
                                id = it.id,
                                spaceId = it.spaceId,
                                packageName = it.packageName,
                                label = it.label,
                                versionName = it.versionName,
                                architecture = it.architecture.name
                            )
                        },
                        cameraConfigs = camConfigs.map {
                            BackupCameraConfigDto(
                                id = it.id,
                                spaceId = it.spaceId,
                                packageName = it.packageName,
                                mode = it.mode,
                                sourceUri = it.sourceUri,
                                enableAudio = it.enableAudio,
                                width = it.width,
                                height = it.height,
                                orientationDegrees = it.orientationDegrees,
                                autoReconnect = it.autoReconnect
                            )
                        },
                        locationConfigs = locConfigs.map {
                            BackupLocationConfigDto(
                                id = it.id,
                                spaceId = it.spaceId,
                                packageName = it.packageName,
                                latitude = it.latitude,
                                longitude = it.longitude,
                                locationLabel = it.locationLabel,
                                enabled = it.enabled
                            )
                        },
                        preferences = BackupPreferencesDto(
                            themePreference = prefs.themePreference.name,
                            defaultSpaceId = prefs.defaultSpaceId,
                            mockLocationEnabled = prefs.mockLocationEnabled
                        )
                    )

                    val encoded = BackupJsonCodec.encode(doc)
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(encoded.toByteArray(Charsets.UTF_8))
                    } ?: error("Unable to open destination file for backup.")

                    "Exported ${spaces.size} spaces, ${apps.size} app records, and ${camConfigs.size} camera settings."
                }
            }

            result.fold(
                onSuccess = { summary ->
                    _uiState.update {
                        it.copy(isBusy = false, lastSummary = summary, feedbackMessage = "Backup exported successfully.")
                    }
                },
                onFailure = { err ->
                    _uiState.update {
                        it.copy(isBusy = false, feedbackMessage = "Export failed: ${err.message}")
                    }
                }
            )
        }
    }

    fun importFromUri(uri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true) }
            val result = withContext(dispatchers.io) {
                runCatching {
                    val raw = context.contentResolver.openInputStream(uri)?.use { input ->
                        input.bufferedReader().readText()
                    } ?: error("Unable to read selected backup file.")

                    val doc = BackupJsonCodec.decodeAndValidate(raw).getOrThrow()
                    val now = System.currentTimeMillis()
                    val spaceDao = database.spaceDao()
                    val appDao = database.virtualAppDao()
                    val camDao = database.cameraConfigDao()
                    val locDao = database.locationConfigDao()

                    doc.spaces.forEach { s ->
                        spaceDao.insertSpace(
                            SpaceEntity(
                                id = s.id,
                                name = s.name,
                                iconKey = s.iconKey,
                                isDefault = s.isDefault,
                                sortOrder = s.sortOrder,
                                isolatedCameraByDefault = s.isolatedCameraByDefault,
                                requireLaunchConfirm = s.requireLaunchConfirm,
                                createdAtEpochMs = now
                            )
                        )
                    }
                    doc.apps.forEach { a ->
                        appDao.upsertApp(
                            VirtualAppEntity(
                                id = a.id,
                                spaceId = a.spaceId,
                                packageName = a.packageName,
                                label = a.label,
                                versionName = a.versionName,
                                architecture = runCatching { ExecutionArchitecture.valueOf(a.architecture) }
                                    .getOrDefault(ExecutionArchitecture.SUPPORTED_SANDBOX),
                                status = VirtualAppStatus.READY,
                                hasCustomCameraConfig = false,
                                lastOpenedEpochMs = null,
                                addedAtEpochMs = now
                            )
                        )
                    }
                    doc.cameraConfigs.forEach { c ->
                        camDao.upsertConfig(
                            CameraConfigEntity(
                                id = c.id,
                                spaceId = c.spaceId,
                                packageName = c.packageName,
                                mode = c.mode,
                                sourceUri = c.sourceUri,
                                enableAudio = c.enableAudio,
                                width = c.width,
                                height = c.height,
                                orientationDegrees = c.orientationDegrees,
                                autoReconnect = c.autoReconnect,
                                updatedAtEpochMs = now
                            )
                        )
                    }
                    doc.locationConfigs.forEach { l ->
                        locDao.upsertConfig(
                            LocationConfigEntity(
                                id = l.id,
                                spaceId = l.spaceId,
                                packageName = l.packageName,
                                latitude = l.latitude,
                                longitude = l.longitude,
                                locationLabel = l.locationLabel,
                                enabled = l.enabled,
                                updatedAtEpochMs = now
                            )
                        )
                    }

                    val theme = runCatching { ThemePreference.valueOf(doc.preferences.themePreference) }
                        .getOrDefault(ThemePreference.SYSTEM)
                    userPreferencesRepository.setThemePreference(theme)
                    userPreferencesRepository.setDefaultSpaceId(doc.preferences.defaultSpaceId)
                    userPreferencesRepository.setMockLocationEnabled(doc.preferences.mockLocationEnabled)

                    "Restored ${doc.spaces.size} spaces and ${doc.apps.size} app metadata records (schema v${doc.schemaVersion})."
                }
            }

            result.fold(
                onSuccess = { summary ->
                    _uiState.update {
                        it.copy(isBusy = false, lastSummary = summary, feedbackMessage = "Backup restored successfully.")
                    }
                },
                onFailure = { err ->
                    _uiState.update {
                        it.copy(isBusy = false, feedbackMessage = "Restore failed: ${err.message}")
                    }
                }
            )
        }
    }

    fun clearFeedback() {
        _uiState.update { it.copy(feedbackMessage = null) }
    }

    companion object {
        fun factory(
            context: Context,
            database: VCameraDatabase,
            userPreferencesRepository: UserPreferencesRepository,
            dispatchers: DispatcherProvider
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return BackupRestoreViewModel(
                    context = context.applicationContext,
                    database = database,
                    userPreferencesRepository = userPreferencesRepository,
                    dispatchers = dispatchers
                ) as T
            }
        }
    }
}

@Composable
fun BackupRestoreScreen(
    viewModel: BackupRestoreViewModel,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) viewModel.exportToUri(uri)
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) viewModel.importFromUri(uri)
    }

    LaunchedEffect(uiState.feedbackMessage) {
        val msg = uiState.feedbackMessage
        if (!msg.isNullOrBlank()) {
            snackbarHostState.showSnackbar(msg)
            viewModel.clearFeedback()
        }
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("backup_restore_screen"),
        contentWindowInsets = WindowInsets.safeDrawing,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            CustomToolbar(
                title = "Backup and Restore",
                subtitle = "Versioned JSON Configuration Archive (v1)",
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
                StatusCard(
                    title = "Backup Scope & Privacy",
                    message = "Exports and imports space names, icons, app metadata, camera settings, location configuration, and general preferences to a user-selected JSON file. Private third-party application data (/data/data/) is never accessed or backed up.",
                    severity = StatusSeverity.INFO,
                    icon = Icons.Default.Info
                )
            }

            item {
                PrimaryButton(
                    text = "Export Backup JSON",
                    icon = Icons.Default.Upload,
                    enabled = !uiState.isBusy,
                    onClick = { exportLauncher.launch("vcamera_space_backup_v1.json") },
                    modifier = Modifier.fillMaxWidth(),
                    testTag = "export_backup_button"
                )
            }

            item {
                OutlinedButton(
                    enabled = !uiState.isBusy,
                    onClick = { importLauncher.launch(arrayOf("application/json", "text/plain")) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("import_backup_button")
                ) {
                    Icon(Icons.Default.Download, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Import & Validate Backup JSON")
                }
            }

            uiState.lastSummary?.let { summary ->
                item {
                    StatusCard(
                        title = "Last Operation",
                        message = summary,
                        severity = StatusSeverity.SUCCESS
                    )
                }
            }
        }
    }
}
