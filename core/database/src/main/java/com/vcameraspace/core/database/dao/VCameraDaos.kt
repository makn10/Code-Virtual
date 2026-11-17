package com.vcameraspace.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.vcameraspace.core.database.entity.AppUsageEntity
import com.vcameraspace.core.database.entity.CameraConfigEntity
import com.vcameraspace.core.database.entity.InstallationEntity
import com.vcameraspace.core.database.entity.LocationConfigEntity
import com.vcameraspace.core.database.entity.SpaceEntity
import com.vcameraspace.core.database.entity.VirtualAppEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SpaceDao {
    @Query("SELECT * FROM spaces ORDER BY sort_order ASC, created_at_epoch_ms ASC")
    fun observeAllSpaces(): Flow<List<SpaceEntity>>

    @Query("SELECT * FROM spaces ORDER BY sort_order ASC, created_at_epoch_ms ASC")
    suspend fun getAllSpacesOnce(): List<SpaceEntity>

    @Query("SELECT * FROM spaces WHERE id = :spaceId LIMIT 1")
    suspend fun getSpaceById(spaceId: String): SpaceEntity?

    @Query("SELECT COUNT(*) FROM spaces")
    suspend fun getSpaceCount(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSpace(space: SpaceEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSpacesIfMissing(spaces: List<SpaceEntity>)

    @Update
    suspend fun updateSpace(space: SpaceEntity)

    @Query("UPDATE spaces SET name = :name WHERE id = :spaceId")
    suspend fun renameSpace(spaceId: String, name: String)

    @Query("UPDATE spaces SET icon_key = :iconKey WHERE id = :spaceId")
    suspend fun updateSpaceIcon(spaceId: String, iconKey: String)

    @Query("UPDATE spaces SET sort_order = :sortOrder WHERE id = :spaceId")
    suspend fun updateSortOrder(spaceId: String, sortOrder: Int)

    @Query(
        "UPDATE spaces SET isolated_camera_by_default = :isolatedCamera, " +
            "require_launch_confirm = :requireConfirm WHERE id = :spaceId"
    )
    suspend fun updateSpaceSettings(
        spaceId: String,
        isolatedCamera: Boolean,
        requireConfirm: Boolean
    )

    @Query("UPDATE spaces SET is_default = 0")
    suspend fun clearDefaultFlags()

    @Query("UPDATE spaces SET is_default = 1 WHERE id = :spaceId")
    suspend fun markAsDefault(spaceId: String)

    @Query("DELETE FROM spaces WHERE id = :spaceId")
    suspend fun deleteSpaceById(spaceId: String)

    @Transaction
    suspend fun setDefaultSpaceTransaction(spaceId: String) {
        clearDefaultFlags()
        markAsDefault(spaceId)
    }

    @Transaction
    suspend fun reorderSpacesTransaction(orderedIds: List<String>) {
        orderedIds.forEachIndexed { index, id ->
            updateSortOrder(id, index)
        }
    }
}

@Dao
interface VirtualAppDao {
    @Query("SELECT * FROM virtual_apps ORDER BY added_at_epoch_ms ASC")
    fun observeAllApps(): Flow<List<VirtualAppEntity>>

    @Query("SELECT * FROM virtual_apps WHERE space_id = :spaceId ORDER BY added_at_epoch_ms ASC")
    fun observeAppsForSpace(spaceId: String): Flow<List<VirtualAppEntity>>

    @Query("SELECT COUNT(*) FROM virtual_apps WHERE space_id = :spaceId")
    suspend fun countAppsInSpace(spaceId: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertApp(app: VirtualAppEntity)

    @Query("DELETE FROM virtual_apps WHERE space_id = :spaceId AND package_name = :packageName")
    suspend fun deleteApp(spaceId: String, packageName: String)

    @Query("DELETE FROM virtual_apps WHERE space_id = :spaceId")
    suspend fun deleteAppsForSpace(spaceId: String)
}

@Dao
interface CameraConfigDao {
    @Query("SELECT * FROM camera_configs WHERE space_id = :spaceId")
    fun observeConfigsForSpace(spaceId: String): Flow<List<CameraConfigEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertConfig(config: CameraConfigEntity)
}

@Dao
interface LocationConfigDao {
    @Query("SELECT * FROM location_configs WHERE space_id = :spaceId")
    fun observeConfigsForSpace(spaceId: String): Flow<List<LocationConfigEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertConfig(config: LocationConfigEntity)
}

@Dao
interface InstallationDao {
    @Query("SELECT * FROM installations WHERE space_id = :spaceId ORDER BY started_at_epoch_ms DESC")
    fun observeInstallations(spaceId: String): Flow<List<InstallationEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertInstallation(record: InstallationEntity)
}

@Dao
interface AppUsageDao {
    @Query("SELECT * FROM app_usage WHERE space_id = :spaceId")
    fun observeUsageForSpace(spaceId: String): Flow<List<AppUsageEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertUsage(usage: AppUsageEntity)
}
