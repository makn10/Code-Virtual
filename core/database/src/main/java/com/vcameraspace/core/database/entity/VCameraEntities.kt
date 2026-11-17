package com.vcameraspace.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.vcameraspace.core.common.model.ExecutionArchitecture
import com.vcameraspace.core.common.model.VirtualAppStatus

@Entity(
    tableName = "spaces",
    indices = [
        Index(value = ["sort_order"]),
        Index(value = ["is_default"])
    ]
)
data class SpaceEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "name")
    val name: String,
    @ColumnInfo(name = "icon_key")
    val iconKey: String,
    @ColumnInfo(name = "is_default")
    val isDefault: Boolean,
    @ColumnInfo(name = "sort_order")
    val sortOrder: Int,
    @ColumnInfo(name = "isolated_camera_by_default")
    val isolatedCameraByDefault: Boolean = true,
    @ColumnInfo(name = "require_launch_confirm")
    val requireLaunchConfirm: Boolean = false,
    @ColumnInfo(name = "created_at_epoch_ms")
    val createdAtEpochMs: Long
)

@Entity(
    tableName = "virtual_apps",
    foreignKeys = [
        ForeignKey(
            entity = SpaceEntity::class,
            parentColumns = ["id"],
            childColumns = ["space_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["space_id"]),
        Index(value = ["space_id", "package_name"], unique = true)
    ]
)
data class VirtualAppEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "space_id")
    val spaceId: String,
    @ColumnInfo(name = "package_name")
    val packageName: String,
    @ColumnInfo(name = "label")
    val label: String,
    @ColumnInfo(name = "version_name")
    val versionName: String,
    @ColumnInfo(name = "architecture")
    val architecture: ExecutionArchitecture,
    @ColumnInfo(name = "status")
    val status: VirtualAppStatus,
    @ColumnInfo(name = "has_custom_camera_config")
    val hasCustomCameraConfig: Boolean,
    @ColumnInfo(name = "last_opened_epoch_ms")
    val lastOpenedEpochMs: Long?,
    @ColumnInfo(name = "added_at_epoch_ms")
    val addedAtEpochMs: Long
)

@Entity(
    tableName = "camera_configs",
    foreignKeys = [
        ForeignKey(
            entity = SpaceEntity::class,
            parentColumns = ["id"],
            childColumns = ["space_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["space_id"]),
        Index(value = ["space_id", "package_name"], unique = true)
    ]
)
data class CameraConfigEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "space_id")
    val spaceId: String,
    @ColumnInfo(name = "package_name")
    val packageName: String?,
    @ColumnInfo(name = "mode")
    val mode: String,
    @ColumnInfo(name = "source_uri")
    val sourceUri: String?,
    @ColumnInfo(name = "enable_audio")
    val enableAudio: Boolean,
    @ColumnInfo(name = "width")
    val width: Int,
    @ColumnInfo(name = "height")
    val height: Int,
    @ColumnInfo(name = "orientation_degrees")
    val orientationDegrees: Int,
    @ColumnInfo(name = "auto_reconnect")
    val autoReconnect: Boolean,
    @ColumnInfo(name = "updated_at_epoch_ms")
    val updatedAtEpochMs: Long
)

@Entity(
    tableName = "location_configs",
    foreignKeys = [
        ForeignKey(
            entity = SpaceEntity::class,
            parentColumns = ["id"],
            childColumns = ["space_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["space_id"]),
        Index(value = ["space_id", "package_name"], unique = true)
    ]
)
data class LocationConfigEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "space_id")
    val spaceId: String,
    @ColumnInfo(name = "package_name")
    val packageName: String,
    @ColumnInfo(name = "latitude")
    val latitude: Double,
    @ColumnInfo(name = "longitude")
    val longitude: Double,
    @ColumnInfo(name = "location_label")
    val locationLabel: String?,
    @ColumnInfo(name = "enabled")
    val enabled: Boolean,
    @ColumnInfo(name = "updated_at_epoch_ms")
    val updatedAtEpochMs: Long
)

@Entity(
    tableName = "installations",
    foreignKeys = [
        ForeignKey(
            entity = SpaceEntity::class,
            parentColumns = ["id"],
            childColumns = ["space_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["space_id"]),
        Index(value = ["started_at_epoch_ms"])
    ]
)
data class InstallationEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "space_id")
    val spaceId: String,
    @ColumnInfo(name = "package_name")
    val packageName: String,
    @ColumnInfo(name = "source_type")
    val sourceType: String,
    @ColumnInfo(name = "status")
    val status: String,
    @ColumnInfo(name = "error_message")
    val errorMessage: String?,
    @ColumnInfo(name = "started_at_epoch_ms")
    val startedAtEpochMs: Long,
    @ColumnInfo(name = "completed_at_epoch_ms")
    val completedAtEpochMs: Long?
)

@Entity(
    tableName = "app_usage",
    foreignKeys = [
        ForeignKey(
            entity = SpaceEntity::class,
            parentColumns = ["id"],
            childColumns = ["space_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["space_id"]),
        Index(value = ["space_id", "package_name"], unique = true)
    ]
)
data class AppUsageEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "space_id")
    val spaceId: String,
    @ColumnInfo(name = "package_name")
    val packageName: String,
    @ColumnInfo(name = "open_count")
    val openCount: Int,
    @ColumnInfo(name = "last_opened_epoch_ms")
    val lastOpenedEpochMs: Long?,
    @ColumnInfo(name = "total_foreground_ms")
    val totalForegroundMs: Long
)

@Entity(
    tableName = "imported_apks",
    indices = [
        Index(value = ["package_name"]),
        Index(value = ["imported_at_epoch_ms"])
    ]
)
data class ImportedApkEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "display_name")
    val displayName: String,
    @ColumnInfo(name = "package_name")
    val packageName: String,
    @ColumnInfo(name = "app_label")
    val appLabel: String,
    @ColumnInfo(name = "version_name")
    val versionName: String,
    @ColumnInfo(name = "version_code")
    val versionCode: Long,
    @ColumnInfo(name = "min_sdk")
    val minSdk: Int,
    @ColumnInfo(name = "target_sdk")
    val targetSdk: Int,
    @ColumnInfo(name = "signing_sha256")
    val signingSha256: String,
    @ColumnInfo(name = "abis_csv")
    val abisCsv: String,
    @ColumnInfo(name = "apk_file_path")
    val apkFilePath: String,
    @ColumnInfo(name = "icon_file_path")
    val iconFilePath: String?,
    @ColumnInfo(name = "file_size_bytes")
    val fileSizeBytes: Long,
    @ColumnInfo(name = "is_valid_archive")
    val isValidArchive: Boolean,
    @ColumnInfo(name = "is_compatible")
    val isCompatible: Boolean,
    @ColumnInfo(name = "compatibility_summary")
    val compatibilitySummary: String,
    @ColumnInfo(name = "imported_at_epoch_ms")
    val importedAtEpochMs: Long
)

