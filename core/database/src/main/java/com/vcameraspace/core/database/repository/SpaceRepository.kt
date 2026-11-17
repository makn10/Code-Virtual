package com.vcameraspace.core.database.repository

import android.content.Context
import com.vcameraspace.core.common.coroutines.DispatcherProvider
import com.vcameraspace.core.common.model.AppError
import com.vcameraspace.core.common.model.AppResult
import com.vcameraspace.core.common.model.Space
import com.vcameraspace.core.common.model.SpaceApp
import com.vcameraspace.core.common.model.runCatchingAppResult
import com.vcameraspace.core.database.VCameraDatabase
import com.vcameraspace.core.database.dao.SpaceDao
import com.vcameraspace.core.database.dao.VirtualAppDao
import com.vcameraspace.core.database.entity.SpaceEntity
import com.vcameraspace.core.database.entity.VirtualAppEntity
import com.vcameraspace.core.datastore.UserPreferencesRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

interface SpaceRepository {
    fun observeSpaces(): Flow<List<Space>>
    fun observeAppsBySpace(): Flow<Map<String, List<SpaceApp>>>
    suspend fun ensureDefaultSpacesSeeded()
    suspend fun createSpace(
        name: String,
        iconKey: String = "layers",
        setAsDefault: Boolean = false
    ): AppResult<Space>
    suspend fun renameSpace(spaceId: String, newName: String): AppResult<Unit>
    suspend fun updateSpaceIcon(spaceId: String, iconKey: String): AppResult<Unit>
    suspend fun setDefaultSpace(spaceId: String): AppResult<Unit>
    suspend fun reorderSpaces(orderedSpaceIds: List<String>): AppResult<Unit>
    suspend fun moveSpace(spaceId: String, delta: Int): AppResult<Unit>
    suspend fun updateSpaceSettings(
        spaceId: String,
        isolatedCameraByDefault: Boolean,
        requireLaunchConfirm: Boolean
    ): AppResult<Unit>
    suspend fun deleteSpace(spaceId: String): AppResult<Unit>
    suspend fun removeAppFromSpace(spaceId: String, packageName: String): AppResult<Unit>
}

class RoomSpaceRepository(
    private val context: Context,
    private val spaceDao: SpaceDao,
    private val virtualAppDao: VirtualAppDao,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val dispatchers: DispatcherProvider
) : SpaceRepository {

    override fun observeSpaces(): Flow<List<Space>> = combine(
        spaceDao.observeAllSpaces(),
        virtualAppDao.observeAllApps()
    ) { entities, apps ->
        val appsBySpace = apps.groupBy { it.spaceId }
        entities.map { entity ->
            val spaceApps = appsBySpace[entity.id].orEmpty()
            val storageBytes = calculateDirectorySize(spaceRootDirectory(entity.id))
            entity.toDomain(
                appCount = spaceApps.size,
                storageBytes = storageBytes
            )
        }
    }

    override fun observeAppsBySpace(): Flow<Map<String, List<SpaceApp>>> =
        virtualAppDao.observeAllApps().let { flow ->
            combine(flow, spaceDao.observeAllSpaces()) { apps, _ ->
                apps.map { it.toDomain() }.groupBy { it.spaceId }
            }
        }

    override suspend fun ensureDefaultSpacesSeeded() {
        withContext(dispatchers.io) {
            if (spaceDao.getSpaceCount() == 0) {
                val defaults = VCameraDatabase.defaultSpaces()
                spaceDao.insertSpacesIfMissing(defaults)
                defaults.forEach { ensureSpaceDirectoryTree(it.id) }
            } else {
                spaceDao.getAllSpacesOnce().forEach { ensureSpaceDirectoryTree(it.id) }
            }
        }
    }

    override suspend fun createSpace(
        name: String,
        iconKey: String,
        setAsDefault: Boolean
    ): AppResult<Space> = withContext(dispatchers.io) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            return@withContext AppResult.Failure(
                AppError.DatabaseFailure("Space name cannot be empty.")
            )
        }
        runCatchingAppResult(
            errorMapper = { AppError.DatabaseFailure(it.message ?: "Unable to create space.") }
        ) {
            val existing = spaceDao.getAllSpacesOnce()
            val nextOrder = (existing.maxOfOrNull { it.sortOrder } ?: -1) + 1
            val spaceId = "space_${UUID.randomUUID().toString().take(8)}"
            val entity = SpaceEntity(
                id = spaceId,
                name = trimmed,
                iconKey = iconKey,
                isDefault = setAsDefault,
                sortOrder = nextOrder,
                isolatedCameraByDefault = true,
                requireLaunchConfirm = false,
                createdAtEpochMs = System.currentTimeMillis()
            )
            spaceDao.insertSpace(entity)
            if (setAsDefault) {
                spaceDao.setDefaultSpaceTransaction(spaceId)
                userPreferencesRepository.setDefaultSpaceId(spaceId)
            }
            ensureSpaceDirectoryTree(spaceId)
            val storageBytes = calculateDirectorySize(spaceRootDirectory(spaceId))
            entity.toDomain(appCount = 0, storageBytes = storageBytes)
        }
    }

    override suspend fun renameSpace(spaceId: String, newName: String): AppResult<Unit> =
        withContext(dispatchers.io) {
            val trimmed = newName.trim()
            if (trimmed.isEmpty()) {
                return@withContext AppResult.Failure(
                    AppError.DatabaseFailure("Space name cannot be blank.")
                )
            }
            runCatchingAppResult(
                errorMapper = { AppError.DatabaseFailure(it.message ?: "Unable to rename space.") }
            ) {
                spaceDao.renameSpace(spaceId, trimmed)
            }
        }

    override suspend fun updateSpaceIcon(spaceId: String, iconKey: String): AppResult<Unit> =
        withContext(dispatchers.io) {
            runCatchingAppResult(
                errorMapper = { AppError.DatabaseFailure(it.message ?: "Unable to update space icon.") }
            ) {
                spaceDao.updateSpaceIcon(spaceId, iconKey)
            }
        }

    override suspend fun setDefaultSpace(spaceId: String): AppResult<Unit> =
        withContext(dispatchers.io) {
            runCatchingAppResult(
                errorMapper = { AppError.DatabaseFailure(it.message ?: "Unable to set default space.") }
            ) {
                spaceDao.setDefaultSpaceTransaction(spaceId)
                userPreferencesRepository.setDefaultSpaceId(spaceId)
            }
        }

    override suspend fun reorderSpaces(orderedSpaceIds: List<String>): AppResult<Unit> =
        withContext(dispatchers.io) {
            runCatchingAppResult(
                errorMapper = { AppError.DatabaseFailure(it.message ?: "Unable to reorder spaces.") }
            ) {
                spaceDao.reorderSpacesTransaction(orderedSpaceIds)
            }
        }

    override suspend fun moveSpace(spaceId: String, delta: Int): AppResult<Unit> =
        withContext(dispatchers.io) {
            runCatchingAppResult(
                errorMapper = { AppError.DatabaseFailure(it.message ?: "Unable to reorder spaces.") }
            ) {
                val ordered = spaceDao.getAllSpacesOnce().toMutableList()
                val currentIndex = ordered.indexOfFirst { it.id == spaceId }
                if (currentIndex == -1) return@runCatchingAppResult
                val targetIndex = (currentIndex + delta).coerceIn(0, ordered.lastIndex)
                if (targetIndex == currentIndex) return@runCatchingAppResult
                val item = ordered.removeAt(currentIndex)
                ordered.add(targetIndex, item)
                spaceDao.reorderSpacesTransaction(ordered.map { it.id })
            }
        }

    override suspend fun updateSpaceSettings(
        spaceId: String,
        isolatedCameraByDefault: Boolean,
        requireLaunchConfirm: Boolean
    ): AppResult<Unit> = withContext(dispatchers.io) {
        runCatchingAppResult(
            errorMapper = { AppError.DatabaseFailure(it.message ?: "Unable to update space settings.") }
        ) {
            spaceDao.updateSpaceSettings(spaceId, isolatedCameraByDefault, requireLaunchConfirm)
        }
    }

    override suspend fun deleteSpace(spaceId: String): AppResult<Unit> =
        withContext(dispatchers.io) {
            val spaces = spaceDao.getAllSpacesOnce()
            if (spaces.size <= 1) {
                return@withContext AppResult.Failure(
                    AppError.DatabaseFailure("At least one workspace must remain active.")
                )
            }
            val target = spaces.firstOrNull { it.id == spaceId }
                ?: return@withContext AppResult.Failure(
                    AppError.DatabaseFailure("Workspace not found.")
                )

            runCatchingAppResult(
                errorMapper = { AppError.DatabaseFailure(it.message ?: "Unable to delete space.") }
            ) {
                spaceDao.deleteSpaceById(spaceId)
                val remaining = spaceDao.getAllSpacesOnce()
                val fallback = remaining.first()
                if (target.isDefault) {
                    spaceDao.setDefaultSpaceTransaction(fallback.id)
                    userPreferencesRepository.setDefaultSpaceId(fallback.id)
                }
                val prefs = userPreferencesRepository.userPreferences.first()
                if (prefs.activeSpaceId == spaceId) {
                    userPreferencesRepository.setActiveSpaceId(fallback.id)
                }
                spaceRootDirectory(spaceId).deleteRecursively()
                Unit
            }
        }

    override suspend fun removeAppFromSpace(spaceId: String, packageName: String): AppResult<Unit> =
        withContext(dispatchers.io) {
            runCatchingAppResult(
                errorMapper = { AppError.DatabaseFailure(it.message ?: "Unable to remove app from space.") }
            ) {
                virtualAppDao.deleteApp(spaceId, packageName)
            }
        }

    private fun spaceRootDirectory(spaceId: String): File =
        File(File(context.filesDir, "spaces"), spaceId)

    private fun ensureSpaceDirectoryTree(spaceId: String) {
        val root = spaceRootDirectory(spaceId)
        listOf("config", "camera", "media").forEach { child ->
            File(root, child).mkdirs()
        }
    }

    private fun calculateDirectorySize(dir: File): Long {
        if (!dir.exists()) return 0L
        if (dir.isFile) return dir.length()
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }
}

private fun SpaceEntity.toDomain(appCount: Int, storageBytes: Long): Space = Space(
    id = id,
    name = name,
    iconKey = iconKey,
    isDefault = isDefault,
    sortOrder = sortOrder,
    appCount = appCount,
    storageBytes = storageBytes,
    isolatedCameraByDefault = isolatedCameraByDefault,
    requireLaunchConfirm = requireLaunchConfirm,
    createdAtEpochMs = createdAtEpochMs
)

private fun VirtualAppEntity.toDomain(): SpaceApp = SpaceApp(
    id = id,
    spaceId = spaceId,
    packageName = packageName,
    label = label,
    versionName = versionName,
    architecture = architecture,
    status = status,
    hasCustomCameraConfig = hasCustomCameraConfig,
    lastOpenedEpochMs = lastOpenedEpochMs
)
