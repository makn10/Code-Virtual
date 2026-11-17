package com.vcameraspace.core.media

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.provider.OpenableColumns
import com.vcameraspace.core.common.coroutines.DispatcherProvider
import com.vcameraspace.core.common.model.AppError
import com.vcameraspace.core.common.model.AppResult
import com.vcameraspace.core.database.dao.CameraConfigDao
import com.vcameraspace.core.database.entity.CameraConfigEntity
import com.vcameraspace.virtualengine.api.CameraSourceMode
import com.vcameraspace.virtualengine.api.CameraSourceSpec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Core CameraSource abstraction inside VCamera Space.
 */
interface CameraSource {
    val mode: CameraSourceMode
    val label: String
    val requiresCameraPermission: Boolean
    val supportsAudioPlayback: Boolean
    val integrationLimitationNote: String
}

class RealCameraSource : CameraSource {
    override val mode = CameraSourceMode.REAL_DEVICE
    override val label = "Disable, Use Real Camera Device"
    override val requiresCameraPermission = true
    override val supportsAudioPlayback = false
    override val integrationLimitationNote =
        "Uses physical device CameraX preview. Virtual camera overlay is disabled."
}

class LocalVideoSource : CameraSource {
    override val mode = CameraSourceMode.LOCAL_VIDEO
    override val label = "Use Local Video"
    override val requiresCameraPermission = false
    override val supportsAudioPlayback = true
    override val integrationLimitationNote =
        "Plays local SAF video inside VCamera Space & supported integrations. Audio plays locally and cannot arbitrarily replace another app's microphone."
}

class NetworkVideoSource : CameraSource {
    override val mode = CameraSourceMode.NETWORK_STREAM
    override val label = "Use Network Video Stream"
    override val requiresCameraPermission = false
    override val supportsAudioPlayback = true
    override val integrationLimitationNote =
        "Streams MP4/HLS/MPEG-DASH/SmoothStreaming/RTSP inside VCamera Space & supported integrations. Requires network availability."
}

class LocalPictureSource : CameraSource {
    override val mode = CameraSourceMode.LOCAL_PICTURE
    override val label = "Use Local Picture"
    override val requiresCameraPermission = false
    override val supportsAudioPlayback = false
    override val integrationLimitationNote =
        "Renders selected local image inside VCamera Space & supported integrations. Auto-sizes when Width and Height are 0."
}

object CameraSourceRegistry {
    val allSources: List<CameraSource> = listOf(
        RealCameraSource(),
        LocalVideoSource(),
        NetworkVideoSource(),
        LocalPictureSource()
    )

    fun sourceFor(mode: CameraSourceMode): CameraSource =
        allSources.first { it.mode == mode }
}

data class LocalVideoMetadata(
    val displayName: String,
    val width: Int,
    val height: Int,
    val durationMs: Long,
    val sizeBytes: Long?
)

data class LocalPictureMetadata(
    val displayName: String,
    val intrinsicWidth: Int,
    val intrinsicHeight: Int,
    val recommendedOutputWidth: Int,
    val recommendedOutputHeight: Int
)

class MediaInspector(
    private val context: Context,
    private val dispatchers: DispatcherProvider
) {
    fun isNetworkAvailable(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    suspend fun inspectLocalVideo(uriString: String): AppResult<LocalVideoMetadata> =
        withContext(dispatchers.io) {
            runCatching {
                val uri = Uri.parse(uriString)
                val (name, sizeBytes) = queryDisplayNameAndSize(uri)
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(context, uri)
                    val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                    val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                    val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                    LocalVideoMetadata(
                        displayName = name,
                        width = w,
                        height = h,
                        durationMs = duration,
                        sizeBytes = sizeBytes
                    )
                } finally {
                    retriever.release()
                }
            }.fold(
                onSuccess = { AppResult.Success(it) },
                onFailure = {
                    AppResult.Failure(
                        AppError.MediaPlaybackError(
                            message = it.message ?: "Unable to read local video metadata.",
                            recoveryHint = "Select a valid MP4 or MKV video via Storage Access Framework."
                        )
                    )
                }
            )
        }

    suspend fun inspectLocalPicture(uriString: String): AppResult<LocalPictureMetadata> =
        withContext(dispatchers.io) {
            runCatching {
                val uri = Uri.parse(uriString)
                val (name, _) = queryDisplayNameAndSize(uri)
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    BitmapFactory.decodeStream(stream, null, options)
                } ?: error("Unable to open image stream")

                val iw = options.outWidth.coerceAtLeast(0)
                val ih = options.outHeight.coerceAtLeast(0)
                val (recW, recH) = computeAutoDimensions(iw, ih)
                LocalPictureMetadata(
                    displayName = name,
                    intrinsicWidth = iw,
                    intrinsicHeight = ih,
                    recommendedOutputWidth = recW,
                    recommendedOutputHeight = recH
                )
            }.fold(
                onSuccess = { AppResult.Success(it) },
                onFailure = {
                    AppResult.Failure(
                        AppError.MediaPlaybackError(
                            message = it.message ?: "Unable to decode selected picture.",
                            recoveryHint = "Select a valid PNG, JPEG, or WebP picture."
                        )
                    )
                }
            )
        }

    private fun queryDisplayNameAndSize(uri: Uri): Pair<String, Long?> {
        var displayName = uri.lastPathSegment ?: "Selected file"
        var size: Long? = null
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameIndex >= 0) {
                    displayName = cursor.getString(nameIndex) ?: displayName
                }
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                    size = cursor.getLong(sizeIndex)
                }
            }
        }
        return displayName to size
    }

    companion object {
        fun computeAutoDimensions(intrinsicWidth: Int, intrinsicHeight: Int): Pair<Int, Int> {
            if (intrinsicWidth <= 0 || intrinsicHeight <= 0) return 1280 to 720
            val maxLongEdge = 1280
            val longEdge = maxOf(intrinsicWidth, intrinsicHeight)
            return if (longEdge <= maxLongEdge) {
                intrinsicWidth to intrinsicHeight
            } else {
                val ratio = maxLongEdge.toFloat() / longEdge.toFloat()
                (intrinsicWidth * ratio).toInt().coerceAtLeast(1) to
                    (intrinsicHeight * ratio).toInt().coerceAtLeast(1)
            }
        }

        fun formatDuration(ms: Long): String {
            if (ms <= 0L) return "00:00"
            val totalSeconds = ms / 1000L
            val minutes = totalSeconds / 60L
            val seconds = totalSeconds % 60L
            return String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }
    }
}

data class CameraConfigurationRecord(
    val spaceId: String,
    val packageName: String?,
    val mode: CameraSourceMode,
    val sourceUri: String?,
    val enableAudio: Boolean,
    val width: Int,
    val height: Int,
    val orientationDegrees: Int,
    val autoReconnect: Boolean,
    val updatedAtEpochMs: Long
) {
    fun toCameraSourceSpec(): CameraSourceSpec = CameraSourceSpec(
        spaceId = spaceId,
        packageName = packageName,
        mode = mode,
        sourceUri = sourceUri,
        enableAudio = enableAudio,
        width = width,
        height = height,
        autoReconnect = autoReconnect
    )
}

interface CameraConfigurationRepository {
    fun observeConfig(spaceId: String, packageName: String?): Flow<CameraConfigurationRecord>
    fun observeSpaceConfigs(spaceId: String): Flow<List<CameraConfigurationRecord>>
    suspend fun saveConfig(record: CameraConfigurationRecord): AppResult<Unit>
}

class RoomCameraConfigurationRepository(
    private val cameraConfigDao: CameraConfigDao,
    private val dispatchers: DispatcherProvider
) : CameraConfigurationRepository {

    override fun observeConfig(spaceId: String, packageName: String?): Flow<CameraConfigurationRecord> {
        return cameraConfigDao.observeConfigsForSpace(spaceId).map { list ->
            val match = list.firstOrNull { it.packageName == packageName }
                ?: list.firstOrNull { it.packageName == null }
            match?.toRecord() ?: defaultConfig(spaceId, packageName)
        }
    }

    override fun observeSpaceConfigs(spaceId: String): Flow<List<CameraConfigurationRecord>> {
        return cameraConfigDao.observeConfigsForSpace(spaceId).map { list ->
            list.map { it.toRecord() }
        }
    }

    override suspend fun saveConfig(record: CameraConfigurationRecord): AppResult<Unit> =
        withContext(dispatchers.io) {
            runCatching {
                val id = "${record.spaceId}::${record.packageName ?: "__space_default__"}"
                cameraConfigDao.upsertConfig(
                    CameraConfigEntity(
                        id = id,
                        spaceId = record.spaceId,
                        packageName = record.packageName,
                        mode = record.mode.name,
                        sourceUri = record.sourceUri,
                        enableAudio = record.enableAudio,
                        width = record.width,
                        height = record.height,
                        orientationDegrees = record.orientationDegrees,
                        autoReconnect = record.autoReconnect,
                        updatedAtEpochMs = System.currentTimeMillis()
                    )
                )
            }.fold(
                onSuccess = { AppResult.Success(Unit) },
                onFailure = {
                    AppResult.Failure(
                        AppError.DatabaseFailure(it.message ?: "Unable to save camera configuration.")
                    )
                }
            )
        }

    companion object {
        fun defaultConfig(spaceId: String, packageName: String?): CameraConfigurationRecord =
            CameraConfigurationRecord(
                spaceId = spaceId,
                packageName = packageName,
                mode = CameraSourceMode.REAL_DEVICE,
                sourceUri = null,
                enableAudio = false,
                width = 0,
                height = 0,
                orientationDegrees = 0,
                autoReconnect = true,
                updatedAtEpochMs = 0L
            )
    }
}

private fun CameraConfigEntity.toRecord(): CameraConfigurationRecord {
    val parsedMode = runCatching { CameraSourceMode.valueOf(mode) }
        .getOrDefault(CameraSourceMode.REAL_DEVICE)
    return CameraConfigurationRecord(
        spaceId = spaceId,
        packageName = packageName,
        mode = parsedMode,
        sourceUri = sourceUri,
        enableAudio = enableAudio,
        width = width,
        height = height,
        orientationDegrees = orientationDegrees,
        autoReconnect = autoReconnect,
        updatedAtEpochMs = updatedAtEpochMs
    )
}
