package com.vcameraspace.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.vcameraspace.core.database.converter.VCameraTypeConverters
import com.vcameraspace.core.database.dao.AppUsageDao
import com.vcameraspace.core.database.dao.CameraConfigDao
import com.vcameraspace.core.database.dao.ImportedApkDao
import com.vcameraspace.core.database.dao.InstallationDao
import com.vcameraspace.core.database.dao.LocationConfigDao
import com.vcameraspace.core.database.dao.SpaceDao
import com.vcameraspace.core.database.dao.VirtualAppDao
import com.vcameraspace.core.database.entity.AppUsageEntity
import com.vcameraspace.core.database.entity.CameraConfigEntity
import com.vcameraspace.core.database.entity.ImportedApkEntity
import com.vcameraspace.core.database.entity.InstallationEntity
import com.vcameraspace.core.database.entity.LocationConfigEntity
import com.vcameraspace.core.database.entity.SpaceEntity
import com.vcameraspace.core.database.entity.VirtualAppEntity
import com.vcameraspace.core.datastore.UserPreferencesSnapshot

@Database(
    entities = [
        SpaceEntity::class,
        VirtualAppEntity::class,
        CameraConfigEntity::class,
        LocationConfigEntity::class,
        InstallationEntity::class,
        AppUsageEntity::class,
        ImportedApkEntity::class
    ],
    version = 2,
    exportSchema = false
)
@TypeConverters(VCameraTypeConverters::class)
abstract class VCameraDatabase : RoomDatabase() {
    abstract fun spaceDao(): SpaceDao
    abstract fun virtualAppDao(): VirtualAppDao
    abstract fun cameraConfigDao(): CameraConfigDao
    abstract fun locationConfigDao(): LocationConfigDao
    abstract fun installationDao(): InstallationDao
    abstract fun appUsageDao(): AppUsageDao
    abstract fun importedApkDao(): ImportedApkDao

    companion object {
        const val DATABASE_NAME = "vcamera_space.db"

        /**
         * Non-destructive migration adding imported_apks storage table for Phase 2 APK Import Manager.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `imported_apks` (
                        `id` TEXT NOT NULL,
                        `display_name` TEXT NOT NULL,
                        `package_name` TEXT NOT NULL,
                        `app_label` TEXT NOT NULL,
                        `version_name` TEXT NOT NULL,
                        `version_code` INTEGER NOT NULL,
                        `min_sdk` INTEGER NOT NULL,
                        `target_sdk` INTEGER NOT NULL,
                        `signing_sha256` TEXT NOT NULL,
                        `abis_csv` TEXT NOT NULL,
                        `apk_file_path` TEXT NOT NULL,
                        `icon_file_path` TEXT,
                        `file_size_bytes` INTEGER NOT NULL,
                        `is_valid_archive` INTEGER NOT NULL,
                        `is_compatible` INTEGER NOT NULL,
                        `compatibility_summary` TEXT NOT NULL,
                        `imported_at_epoch_ms` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_imported_apks_package_name` ON `imported_apks` (`package_name`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_imported_apks_imported_at_epoch_ms` ON `imported_apks` (`imported_at_epoch_ms`)"
                )
            }
        }

        fun create(context: Context): VCameraDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                VCameraDatabase::class.java,
                DATABASE_NAME
            )
                .addCallback(SeedInitialSpacesCallback())
                .addMigrations(MIGRATION_1_2)
                .build()
        }

        fun createInMemory(context: Context): VCameraDatabase {
            return Room.inMemoryDatabaseBuilder(
                context.applicationContext,
                VCameraDatabase::class.java
            )
                .allowMainThreadQueries()
                .addCallback(SeedInitialSpacesCallback())
                .build()
        }

        fun defaultSpaces(): List<SpaceEntity> {
            val now = System.currentTimeMillis()
            return listOf(
                SpaceEntity(
                    id = UserPreferencesSnapshot.DEFAULT_SPACE_ID,
                    name = "Space 1",
                    iconKey = "layers",
                    isDefault = true,
                    sortOrder = 0,
                    isolatedCameraByDefault = true,
                    requireLaunchConfirm = false,
                    createdAtEpochMs = now
                ),
                SpaceEntity(
                    id = "space_2",
                    name = "Space 2",
                    iconKey = "work",
                    isDefault = false,
                    sortOrder = 1,
                    isolatedCameraByDefault = true,
                    requireLaunchConfirm = false,
                    createdAtEpochMs = now + 1L
                )
            )
        }
    }

    private class SeedInitialSpacesCallback : Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) {
            super.onCreate(db)
            val now = System.currentTimeMillis()
            db.execSQL(
                """
                INSERT OR IGNORE INTO spaces (
                    id, name, icon_key, is_default, sort_order,
                    isolated_camera_by_default, require_launch_confirm, created_at_epoch_ms
                ) VALUES (
                    '${UserPreferencesSnapshot.DEFAULT_SPACE_ID}', 'Space 1', 'layers', 1, 0, 1, 0, $now
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT OR IGNORE INTO spaces (
                    id, name, icon_key, is_default, sort_order,
                    isolated_camera_by_default, require_launch_confirm, created_at_epoch_ms
                ) VALUES (
                    'space_2', 'Space 2', 'work', 0, 1, 1, 0, ${now + 1L}
                )
                """.trimIndent()
            )
        }
    }
}
