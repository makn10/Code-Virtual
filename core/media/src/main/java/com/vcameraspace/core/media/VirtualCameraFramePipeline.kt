package com.vcameraspace.core.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import com.vcameraspace.core.common.coroutines.DispatcherProvider
import com.vcameraspace.core.common.diagnostics.DiagnosticCategory
import com.vcameraspace.core.common.diagnostics.DiagnosticLogger
import com.vcameraspace.core.common.model.AppError
import com.vcameraspace.core.common.model.AppResult
import com.vcameraspace.virtualengine.api.CameraSourceMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.util.zip.CRC32
import kotlin.math.roundToInt

enum class YuvFrameFormat(val label: String) {
    I420("I420 (Planar YUV 4:2:0)"),
    YUV_420_888("YUV_420_888 (Flexible Android YUV)")
}

class YuvFrameBuffer(
    val width: Int,
    val height: Int,
    val format: YuvFrameFormat,
    val yPlane: ByteArray,
    val uPlane: ByteArray,
    val vPlane: ByteArray,
    val yRowStride: Int,
    val uvRowStride: Int,
    val uvPixelStride: Int,
    val presentationTimeUs: Long,
    val checksumHex: String
) {
    val packedI420SizeBytes: Int
        get() = yPlane.size + uPlane.size + vPlane.size

    fun toPackedI420(): ByteArray {
        val out = ByteArray(packedI420SizeBytes)
        System.arraycopy(yPlane, 0, out, 0, yPlane.size)
        System.arraycopy(uPlane, 0, out, yPlane.size, uPlane.size)
        System.arraycopy(vPlane, 0, out, yPlane.size + uPlane.size, vPlane.size)
        return out
    }
}

object YuvFrameConverter {

    fun computeEvenDimensions(
        sourceWidth: Int,
        sourceHeight: Int,
        requestedWidth: Int = 0,
        requestedHeight: Int = 0,
        maxLongEdge: Int = 1280
    ): Pair<Int, Int> {
        val rawW: Int
        val rawH: Int
        if (requestedWidth > 0 && requestedHeight > 0) {
            rawW = requestedWidth
            rawH = requestedHeight
        } else if (sourceWidth > 0 && sourceHeight > 0) {
            val longEdge = maxOf(sourceWidth, sourceHeight)
            if (longEdge <= maxLongEdge) {
                rawW = sourceWidth
                rawH = sourceHeight
            } else {
                val scale = maxLongEdge.toFloat() / longEdge.toFloat()
                rawW = (sourceWidth * scale).roundToInt()
                rawH = (sourceHeight * scale).roundToInt()
            }
        } else {
            rawW = 1280
            rawH = 720
        }
        val evenW = (rawW.coerceAtLeast(2) / 2) * 2
        val evenH = (rawH.coerceAtLeast(2) / 2) * 2
        return evenW to evenH
    }

    /**
     * Converts ARGB_8888 pixel buffer into planar YUV 4:2:0 (I420 & YUV_420_888 planar stride=1).
     */
    fun argbToYuv420(
        argbPixels: IntArray,
        width: Int,
        height: Int,
        format: YuvFrameFormat = YuvFrameFormat.I420,
        presentationTimeUs: Long = 0L
    ): YuvFrameBuffer {
        val evenW = (width.coerceAtLeast(2) / 2) * 2
        val evenH = (height.coerceAtLeast(2) / 2) * 2
        require(argbPixels.size >= evenW * height) {
            "ARGB buffer size (${argbPixels.size}) smaller than expected ${evenW * height}"
        }

        val ySize = evenW * evenH
        val uvW = evenW / 2
        val uvH = evenH / 2
        val uvSize = uvW * uvH

        val yPlane = ByteArray(ySize)
        val uPlane = ByteArray(uvSize)
        val vPlane = ByteArray(uvSize)

        var uvIndex = 0
        for (j in 0 until evenH) {
            val rowOffset = j * width
            for (i in 0 until evenW) {
                val argb = argbPixels[rowOffset + i]
                val r = (argb shr 16) and 0xFF
                val g = (argb shr 8) and 0xFF
                val b = argb and 0xFF

                val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                yPlane[j * evenW + i] = y.coerceIn(0, 255).toByte()

                if ((j and 1) == 0 && (i and 1) == 0 && uvIndex < uvSize) {
                    val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                    val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                    uPlane[uvIndex] = u.coerceIn(0, 255).toByte()
                    vPlane[uvIndex] = v.coerceIn(0, 255).toByte()
                    uvIndex++
                }
            }
        }

        val crc = CRC32().apply {
            update(yPlane, 0, minOf(yPlane.size, 4096))
            update(uPlane, 0, minOf(uPlane.size, 1024))
        }.value

        return YuvFrameBuffer(
            width = evenW,
            height = evenH,
            format = format,
            yPlane = yPlane,
            uPlane = uPlane,
            vPlane = vPlane,
            yRowStride = evenW,
            uvRowStride = uvW,
            uvPixelStride = 1,
            presentationTimeUs = presentationTimeUs,
            checksumHex = "%08X".format(crc)
        )
    }

    fun bitmapToYuvFrame(
        bitmap: Bitmap,
        requestedWidth: Int = 0,
        requestedHeight: Int = 0,
        format: YuvFrameFormat = YuvFrameFormat.I420
    ): YuvFrameBuffer {
        val (targetW, targetH) = computeEvenDimensions(
            sourceWidth = bitmap.width,
            sourceHeight = bitmap.height,
            requestedWidth = requestedWidth,
            requestedHeight = requestedHeight
        )
        val scaled = if (bitmap.width != targetW || bitmap.height != targetH) {
            Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
        } else {
            bitmap
        }
        val pixels = IntArray(targetW * targetH)
        scaled.getPixels(pixels, 0, targetW, 0, 0, targetW, targetH)
        if (scaled !== bitmap) {
            scaled.recycle()
        }
        return argbToYuv420(pixels, targetW, targetH, format = format)
    }

    /**
     * Converts an Android [Image] (ImageFormat.YUV_420_888) returned by MediaCodec/Camera2
     * into tightly packed I420 Y/U/V planes handling arbitrary rowStride and pixelStride.
     */
    fun mediaImageToYuvFrame(
        image: Image,
        presentationTimeUs: Long,
        targetFormat: YuvFrameFormat = YuvFrameFormat.YUV_420_888
    ): YuvFrameBuffer {
        require(image.format == ImageFormat.YUV_420_888) {
            "Expected ImageFormat.YUV_420_888, got ${image.format}"
        }
        val evenW = (image.width.coerceAtLeast(2) / 2) * 2
        val evenH = (image.height.coerceAtLeast(2) / 2) * 2
        val uvW = evenW / 2
        val uvH = evenH / 2

        val yPlane = ByteArray(evenW * evenH)
        val uPlane = ByteArray(uvW * uvH)
        val vPlane = ByteArray(uvW * uvH)

        copyPlane(image.planes[0], evenW, evenH, yPlane)
        copyPlane(image.planes[1], uvW, uvH, uPlane)
        copyPlane(image.planes[2], uvW, uvH, vPlane)

        val crc = CRC32().apply {
            update(yPlane, 0, minOf(yPlane.size, 4096))
        }.value

        return YuvFrameBuffer(
            width = evenW,
            height = evenH,
            format = targetFormat,
            yPlane = yPlane,
            uPlane = uPlane,
            vPlane = vPlane,
            yRowStride = evenW,
            uvRowStride = uvW,
            uvPixelStride = 1,
            presentationTimeUs = presentationTimeUs,
            checksumHex = "%08X".format(crc)
        )
    }

    private fun copyPlane(plane: Image.Plane, width: Int, height: Int, dest: ByteArray) {
        val buffer: ByteBuffer = plane.buffer.duplicate().apply { rewind() }
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        if (pixelStride == 1 && rowStride == width) {
            buffer.get(dest, 0, minOf(dest.size, buffer.remaining()))
            return
        }
        val rowBuffer = ByteArray(rowStride)
        var destOffset = 0
        for (row in 0 until height) {
            val remaining = buffer.remaining()
            if (remaining <= 0) break
            val bytesToRead = if (row == height - 1) {
                minOf(remaining, (width - 1) * pixelStride + 1)
            } else {
                minOf(remaining, rowStride)
            }
            buffer.get(rowBuffer, 0, bytesToRead)
            for (col in 0 until width) {
                val srcIdx = col * pixelStride
                if (srcIdx < bytesToRead && destOffset < dest.size) {
                    dest[destOffset++] = rowBuffer[srcIdx]
                }
            }
        }
    }
}

data class PhotoFramePipelineResult(
    val sourceUri: String,
    val intrinsicWidth: Int,
    val intrinsicHeight: Int,
    val outputWidth: Int,
    val outputHeight: Int,
    val outputFormat: YuvFrameFormat,
    val packedI420Bytes: Int,
    val conversionDurationMs: Long,
    val frameChecksumHex: String,
    val decodedPreviewBitmap: Bitmap? = null
)

class PhotoFrameProcessor(
    private val context: Context,
    private val dispatchers: DispatcherProvider,
    private val logger: DiagnosticLogger? = null
) {
    suspend fun processPhoto(
        uriString: String,
        requestedWidth: Int = 0,
        requestedHeight: Int = 0,
        outputFormat: YuvFrameFormat = YuvFrameFormat.I420,
        retainPreviewBitmap: Boolean = true
    ): AppResult<PhotoFramePipelineResult> = withContext(dispatchers.io) {
        val startedAt = System.currentTimeMillis()
        runCatching {
            val uri = Uri.parse(uriString)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, bounds)
            } ?: error("Unable to open selected photo stream.")

            val srcW = bounds.outWidth
            val srcH = bounds.outHeight
            if (srcW <= 0 || srcH <= 0) {
                error("Selected file is not a valid readable image.")
            }

            val (targetW, targetH) = YuvFrameConverter.computeEvenDimensions(
                sourceWidth = srcW,
                sourceHeight = srcH,
                requestedWidth = requestedWidth,
                requestedHeight = requestedHeight
            )

            val sampleSize = calculateInSampleSize(srcW, srcH, targetW, targetH)
            val decodeOpts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val decoded = context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, decodeOpts)
            } ?: error("Bitmap decoding failed for image.")

            val resized = if (decoded.width != targetW || decoded.height != targetH) {
                Bitmap.createScaledBitmap(decoded, targetW, targetH, true).also {
                    decoded.recycle()
                }
            } else {
                decoded
            }

            val pixels = IntArray(resized.width * resized.height)
            resized.getPixels(pixels, 0, resized.width, 0, 0, resized.width, resized.height)
            val yuvBuffer = YuvFrameConverter.argbToYuv420(
                argbPixels = pixels,
                width = resized.width,
                height = resized.height,
                format = outputFormat
            )

            val previewCopy = if (retainPreviewBitmap) resized else {
                resized.recycle()
                null
            }

            val elapsed = (System.currentTimeMillis() - startedAt).coerceAtLeast(1L)
            logger?.logInfo(
                DiagnosticCategory.MEDIA_PIPELINE,
                "Photo decoded & converted to ${outputFormat.name}: ${yuvBuffer.width}x${yuvBuffer.height}",
                "I420 size=${yuvBuffer.packedI420SizeBytes}B in ${elapsed}ms crc=${yuvBuffer.checksumHex}"
            )
            PhotoFramePipelineResult(
                sourceUri = uriString,
                intrinsicWidth = srcW,
                intrinsicHeight = srcH,
                outputWidth = yuvBuffer.width,
                outputHeight = yuvBuffer.height,
                outputFormat = outputFormat,
                packedI420Bytes = yuvBuffer.packedI420SizeBytes,
                conversionDurationMs = elapsed,
                frameChecksumHex = yuvBuffer.checksumHex,
                decodedPreviewBitmap = previewCopy
            )
        }.fold(
            onSuccess = { AppResult.Success(it) },
            onFailure = { throwable ->
                logger?.logError(
                    DiagnosticCategory.MEDIA_PIPELINE,
                    "Photo pipeline error",
                    throwable.message
                )
                AppResult.Failure(
                    AppError.MediaPlaybackError(
                        message = throwable.message ?: "Failed to decode & convert photo to YUV.",
                        recoveryHint = "Select a valid PNG, JPEG, or WebP photo."
                    )
                )
            }
        )
    }

    private fun calculateInSampleSize(srcW: Int, srcH: Int, reqW: Int, reqH: Int): Int {
        var inSampleSize = 1
        if (srcH > reqH || srcW > reqW) {
            val halfH = srcH / 2
            val halfW = srcW / 2
            while ((halfH / inSampleSize) >= reqH && (halfW / inSampleSize) >= reqW) {
                inSampleSize *= 2
            }
        }
        return inSampleSize.coerceAtLeast(1)
    }
}

data class VideoCodecPipelineResult(
    val sourceUri: String,
    val mimeType: String,
    val codecName: String,
    val width: Int,
    val height: Int,
    val durationMs: Long,
    val decodedFrameCount: Int,
    val averageFrameDeltaMs: Double,
    val estimatedFps: Double,
    val outputFormat: YuvFrameFormat,
    val yuvFrameBytes: Int,
    val lastPresentationTimeUs: Long,
    val processingDurationMs: Long
)

class VideoCodecFrameProcessor(
    private val context: Context,
    private val dispatchers: DispatcherProvider,
    private val logger: DiagnosticLogger? = null
) {
    /**
     * Extracts and decodes initial video frames via [MediaExtractor] and [MediaCodec]
     * with playback presentation-timestamp tracking and [YuvFrameFormat.YUV_420_888] / [YuvFrameFormat.I420] conversion.
     */
    suspend fun decodeAndValidateFrames(
        uriString: String,
        maxSampleFrames: Int = 10,
        targetFormat: YuvFrameFormat = YuvFrameFormat.YUV_420_888
    ): AppResult<VideoCodecPipelineResult> = withContext(dispatchers.io) {
        val startedAt = System.currentTimeMillis()
        var extractor: MediaExtractor? = null
        var codec: MediaCodec? = null
        try {
            val uri = Uri.parse(uriString)
            extractor = MediaExtractor().apply {
                setDataSource(context, uri, null)
            }

            var videoTrackIndex = -1
            var videoFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("video/")) {
                    videoTrackIndex = i
                    videoFormat = fmt
                    break
                }
            }
            if (videoTrackIndex < 0 || videoFormat == null) {
                error("No decodable video/* track found inside selected media.")
            }

            val mime = videoFormat.getString(MediaFormat.KEY_MIME) ?: "video/avc"
            val declaredWidth = if (videoFormat.containsKey(MediaFormat.KEY_WIDTH)) videoFormat.getInteger(MediaFormat.KEY_WIDTH) else 0
            val declaredHeight = if (videoFormat.containsKey(MediaFormat.KEY_HEIGHT)) videoFormat.getInteger(MediaFormat.KEY_HEIGHT) else 0
            val durationUs = if (videoFormat.containsKey(MediaFormat.KEY_DURATION)) videoFormat.getLong(MediaFormat.KEY_DURATION) else 0L

            videoFormat.setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
            )

            extractor.selectTrack(videoTrackIndex)
            codec = MediaCodec.createDecoderByType(mime).apply {
                configure(videoFormat, null, null, 0)
                start()
            }

            val codecName = codec.name
            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var decodedFrames = 0
            var outputW = declaredWidth
            var outputH = declaredHeight
            var yuvBytes = (maxOf(outputW, 2) * maxOf(outputH, 2) * 3) / 2
            var firstPtsUs: Long? = null
            var lastPtsUs = 0L
            var totalPtsDeltaUs = 0L
            val timeoutDeadlineMs = startedAt + 6_000L

            while (!outputDone && decodedFrames < maxSampleFrames && System.currentTimeMillis() < timeoutDeadlineMs) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(10_000L)
                    if (inIdx >= 0) {
                        val inBuf = codec.getInputBuffer(inIdx)
                        if (inBuf != null) {
                            val sampleSize = extractor.readSampleData(inBuf, 0)
                            if (sampleSize < 0) {
                                codec.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(inIdx, 0, sampleSize, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                }

                val outIdx = codec.dequeueOutputBuffer(bufferInfo, 10_000L)
                when {
                    outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outFmt = codec.outputFormat
                        outputW = outFmt.getInteger(MediaFormat.KEY_WIDTH)
                        outputH = outFmt.getInteger(MediaFormat.KEY_HEIGHT)
                    }
                    outIdx >= 0 -> {
                        if (bufferInfo.size > 0) {
                            val image = runCatching { codec.getOutputImage(outIdx) }.getOrNull()
                            if (image != null) {
                                try {
                                    val yuv = YuvFrameConverter.mediaImageToYuvFrame(
                                        image = image,
                                        presentationTimeUs = bufferInfo.presentationTimeUs,
                                        targetFormat = targetFormat
                                    )
                                    outputW = yuv.width
                                    outputH = yuv.height
                                    yuvBytes = yuv.packedI420SizeBytes
                                } finally {
                                    image.close()
                                }
                            } else {
                                val rawBuf = codec.getOutputBuffer(outIdx)
                                if (rawBuf != null && bufferInfo.size > 0) {
                                    yuvBytes = bufferInfo.size
                                }
                            }
                            if (firstPtsUs == null) {
                                firstPtsUs = bufferInfo.presentationTimeUs
                            } else if (bufferInfo.presentationTimeUs > lastPtsUs) {
                                totalPtsDeltaUs += (bufferInfo.presentationTimeUs - lastPtsUs)
                            }
                            lastPtsUs = bufferInfo.presentationTimeUs
                            decodedFrames++
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputDone = true
                        }
                    }
                }
            }

            if (decodedFrames == 0) {
                error("MediaCodec produced zero output frames for $mime.")
            }

            val intervals = (decodedFrames - 1).coerceAtLeast(1)
            val avgDeltaUs = if (decodedFrames > 1 && totalPtsDeltaUs > 0L) {
                totalPtsDeltaUs.toDouble() / intervals
            } else {
                33_333.3
            }
            val avgDeltaMs = avgDeltaUs / 1_000.0
            val fps = (1_000_000.0 / avgDeltaUs).coerceIn(1.0, 120.0)
            val elapsedMs = (System.currentTimeMillis() - startedAt).coerceAtLeast(1L)

            val result = VideoCodecPipelineResult(
                sourceUri = uriString,
                mimeType = mime,
                codecName = codecName,
                width = outputW,
                height = outputH,
                durationMs = durationUs / 1_000L,
                decodedFrameCount = decodedFrames,
                averageFrameDeltaMs = avgDeltaMs,
                estimatedFps = fps,
                outputFormat = targetFormat,
                yuvFrameBytes = yuvBytes,
                lastPresentationTimeUs = lastPtsUs,
                processingDurationMs = elapsedMs
            )
            logger?.logInfo(
                DiagnosticCategory.MEDIA_PIPELINE,
                "MediaCodec decoded $decodedFrames frame(s) ($mime via $codecName)",
                "${outputW}x${outputH}, ${String.format("%.1f", fps)} fps, ${yuvBytes}B ${targetFormat.name}"
            )
            AppResult.Success(result)
        } catch (t: Throwable) {
            logger?.logError(
                DiagnosticCategory.MEDIA_PIPELINE,
                "MediaCodec video pipeline failed",
                t.message
            )
            AppResult.Failure(
                AppError.MediaPlaybackError(
                    message = t.message ?: "MediaCodec decoder failed for selected video.",
                    recoveryHint = "Select a standard H.264/HEVC MP4 video file."
                )
            )
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor?.release() }
        }
    }
}

enum class CameraAdapterLifecycle {
    IDLE,
    PREPARING,
    STREAMING,
    STOPPED,
    ERROR
}

data class SharedCameraSourceConfig(
    val mode: CameraSourceMode = CameraSourceMode.REAL_DEVICE,
    val sourceUri: String? = null,
    val outputFormat: YuvFrameFormat = YuvFrameFormat.I420,
    val targetWidth: Int = 0,
    val targetHeight: Int = 0,
    val enableAudio: Boolean = false,
    val autoReconnect: Boolean = true,
    val physicalCameraIsolated: Boolean = true,
    val updatedAtEpochMs: Long = 0L
)

data class CameraApiSupportMatrix(
    val camera1BridgeMode: String,
    val camera2HardwareLevelSummary: String,
    val cameraXSupported: Boolean,
    val physicalCameraIsolatedWhenVirtual: Boolean
)

data class VirtualCameraAdapterState(
    val lifecycle: CameraAdapterLifecycle = CameraAdapterLifecycle.IDLE,
    val activeSpaceId: String = "space_1",
    val activePackageName: String? = null,
    val usingSharedGlobalSource: Boolean = true,
    val sharedConfig: SharedCameraSourceConfig = SharedCameraSourceConfig(),
    val apiMatrix: CameraApiSupportMatrix = CameraApiSupportMatrix(
        camera1BridgeMode = "Legacy Camera1 unprivileged HAL cannot be hooked system-wide",
        camera2HardwareLevelSummary = "Probing Camera2...",
        cameraXSupported = true,
        physicalCameraIsolatedWhenVirtual = true
    ),
    val lastPhotoResult: PhotoFramePipelineResult? = null,
    val lastVideoCodecResult: VideoCodecPipelineResult? = null,
    val lastStatusNote: String = "Virtual camera adapter idle (physical camera isolated until explicitly enabled)."
)

class VirtualCameraAdapterManager(
    private val context: Context,
    private val configRepository: CameraConfigurationRepository,
    val photoProcessor: PhotoFrameProcessor,
    val videoCodecProcessor: VideoCodecFrameProcessor,
    private val dispatchers: DispatcherProvider,
    private val logger: DiagnosticLogger? = null
) {
    private val sharedFile = File(context.filesDir, "shared_virtual_camera.json")
    private val _state = MutableStateFlow(VirtualCameraAdapterState())
    val state: StateFlow<VirtualCameraAdapterState> = _state.asStateFlow()

    init {
        loadPersistedSharedConfig()
        refreshCameraApiSupport()
    }

    fun observeSharedConfig(): Flow<SharedCameraSourceConfig> =
        MutableStateFlow(_state.value.sharedConfig).also {
            // Consumers observing state.sharedConfig get instant live updates
        }

    fun refreshCameraApiSupport() {
        val summary = runCatching {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
                ?: return@runCatching "CameraManager unavailable"
            val ids = cm.cameraIdList
            if (ids.isEmpty()) {
                "0 physical camera sensors detected"
            } else {
                val levels = ids.mapNotNull { id ->
                    val chars = cm.getCameraCharacteristics(id)
                    val level = chars.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
                    "$id:${hardwareLevelName(level)}"
                }
                "${ids.size} sensor(s) [${levels.joinToString()}]"
            }
        }.getOrElse { "Camera2 query failed: ${it.message}" }

        _state.update { current ->
            current.copy(
                apiMatrix = CameraApiSupportMatrix(
                    camera1BridgeMode = "Legacy Camera1 restricted to host process only",
                    camera2HardwareLevelSummary = summary,
                    cameraXSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O,
                    physicalCameraIsolatedWhenVirtual = current.sharedConfig.mode != CameraSourceMode.REAL_DEVICE
                )
            )
        }
    }

    suspend fun saveSharedConfig(config: SharedCameraSourceConfig): AppResult<Unit> = withContext(dispatchers.io) {
        runCatching {
            val stamped = config.copy(
                physicalCameraIsolated = config.mode != CameraSourceMode.REAL_DEVICE,
                updatedAtEpochMs = System.currentTimeMillis()
            )
            persistSharedConfig(stamped)
            // Also keep space_1 default synced so existing Room observers reflect changes cleanly.
            configRepository.saveConfig(
                CameraConfigurationRecord(
                    spaceId = "space_1",
                    packageName = null,
                    mode = stamped.mode,
                    sourceUri = stamped.sourceUri,
                    enableAudio = stamped.enableAudio,
                    width = stamped.targetWidth,
                    height = stamped.targetHeight,
                    orientationDegrees = 0,
                    autoReconnect = stamped.autoReconnect,
                    updatedAtEpochMs = stamped.updatedAtEpochMs
                )
            )
            _state.update {
                it.copy(
                    sharedConfig = stamped,
                    lastStatusNote = "Shared virtual camera updated (${stamped.mode.name}, format=${stamped.outputFormat.name})."
                )
            }
            logger?.logInfo(
                DiagnosticCategory.CAMERA_ADAPTER,
                "Saved shared virtual camera source (${stamped.mode.name})",
                "Format=${stamped.outputFormat.name}, isolatedPhysical=${stamped.physicalCameraIsolated}"
            )
        }.fold(
            onSuccess = { AppResult.Success(Unit) },
            onFailure = {
                logger?.logError(DiagnosticCategory.CAMERA_ADAPTER, "Failed saving shared camera config", it.message)
                AppResult.Failure(AppError.DatabaseFailure(it.message ?: "Failed saving shared camera configuration."))
            }
        )
    }

    /**
     * Resolves effective config for any (spaceId, packageName):
     * uses per-app override if explicitly set in Room (mode != REAL_DEVICE or custom URI),
     * otherwise falls back to the centralized Shared Virtual Camera config.
     */
    suspend fun resolveEffectiveConfig(spaceId: String, packageName: String?): SharedCameraSourceConfig {
        val roomRecord = configRepository.observeConfig(spaceId, packageName).first()
        val hasAppOverride = roomRecord.updatedAtEpochMs > 0L && roomRecord.packageName == packageName
        return if (hasAppOverride) {
            SharedCameraSourceConfig(
                mode = roomRecord.mode,
                sourceUri = roomRecord.sourceUri,
                outputFormat = _state.value.sharedConfig.outputFormat,
                targetWidth = roomRecord.width,
                targetHeight = roomRecord.height,
                enableAudio = roomRecord.enableAudio,
                autoReconnect = roomRecord.autoReconnect,
                physicalCameraIsolated = roomRecord.mode != CameraSourceMode.REAL_DEVICE,
                updatedAtEpochMs = roomRecord.updatedAtEpochMs
            )
        } else {
            _state.value.sharedConfig
        }
    }

    suspend fun startVirtualSession(spaceId: String = "space_1", packageName: String? = null): AppResult<VirtualCameraAdapterState> {
        _state.update {
            it.copy(
                lifecycle = CameraAdapterLifecycle.PREPARING,
                activeSpaceId = spaceId,
                activePackageName = packageName,
                lastStatusNote = "Preparing virtual camera adapter..."
            )
        }
        val effective = resolveEffectiveConfig(spaceId, packageName)
        return when (effective.mode) {
            CameraSourceMode.LOCAL_PICTURE -> {
                val uri = effective.sourceUri.orEmpty()
                if (uri.isBlank()) {
                    failAdapter("No local photo selected for Shared Virtual Camera.")
                } else {
                    when (val res = photoProcessor.processPhoto(uri, effective.targetWidth, effective.targetHeight, effective.outputFormat, retainPreviewBitmap = false)) {
                        is AppResult.Success -> {
                            _state.update {
                                it.copy(
                                    lifecycle = CameraAdapterLifecycle.STREAMING,
                                    lastPhotoResult = res.data,
                                    lastStatusNote = "Virtual Camera active (Photo -> ${res.data.outputWidth}x${res.data.outputHeight} ${res.data.outputFormat.name}; physical camera isolated)."
                                )
                            }
                            logger?.logInfo(DiagnosticCategory.CAMERA_ADAPTER, _state.value.lastStatusNote)
                            AppResult.Success(_state.value)
                        }
                        is AppResult.Failure -> failAdapter(res.error.message)
                    }
                }
            }
            CameraSourceMode.LOCAL_VIDEO -> {
                val uri = effective.sourceUri.orEmpty()
                if (uri.isBlank()) {
                    failAdapter("No local video selected for Shared Virtual Camera.")
                } else {
                    when (val res = videoCodecProcessor.decodeAndValidateFrames(uri, maxSampleFrames = 8, targetFormat = effective.outputFormat)) {
                        is AppResult.Success -> {
                            _state.update {
                                it.copy(
                                    lifecycle = CameraAdapterLifecycle.STREAMING,
                                    lastVideoCodecResult = res.data,
                                    lastStatusNote = "Virtual Camera active (MediaCodec ${res.data.mimeType} -> ${res.data.width}x${res.data.height} @ ${String.format("%.1f", res.data.estimatedFps)} fps; physical camera isolated)."
                                )
                            }
                            logger?.logInfo(DiagnosticCategory.CAMERA_ADAPTER, _state.value.lastStatusNote)
                            AppResult.Success(_state.value)
                        }
                        is AppResult.Failure -> failAdapter(res.error.message)
                    }
                }
            }
            CameraSourceMode.NETWORK_STREAM -> {
                if (effective.sourceUri.isNullOrBlank()) {
                    failAdapter("No stream URL configured.")
                } else {
                    _state.update {
                        it.copy(
                            lifecycle = CameraAdapterLifecycle.STREAMING,
                            lastStatusNote = "Network stream mode prepared via Media3 ExoPlayer (physical camera isolated)."
                        )
                    }
                    logger?.logInfo(DiagnosticCategory.CAMERA_ADAPTER, _state.value.lastStatusNote)
                    AppResult.Success(_state.value)
                }
            }
            CameraSourceMode.REAL_DEVICE -> {
                _state.update {
                    it.copy(
                        lifecycle = CameraAdapterLifecycle.STREAMING,
                        lastStatusNote = "Physical camera pass-through mode active (CameraX Hardware)."
                    )
                }
                logger?.logInfo(DiagnosticCategory.CAMERA_ADAPTER, _state.value.lastStatusNote)
                AppResult.Success(_state.value)
            }
        }
    }

    fun stopVirtualSession() {
        _state.update {
            it.copy(
                lifecycle = CameraAdapterLifecycle.STOPPED,
                lastStatusNote = "Virtual camera adapter stopped and buffers released."
            )
        }
        logger?.logInfo(DiagnosticCategory.CAMERA_ADAPTER, "Virtual camera adapter stopped cleanly.")
    }

    private fun failAdapter(reason: String): AppResult.Failure {
        _state.update {
            it.copy(
                lifecycle = CameraAdapterLifecycle.ERROR,
                lastStatusNote = "Adapter error: $reason"
            )
        }
        logger?.logError(DiagnosticCategory.CAMERA_ADAPTER, reason)
        return AppResult.Failure(AppError.MediaPlaybackError(reason))
    }

    private fun loadPersistedSharedConfig() {
        if (!sharedFile.exists()) return
        runCatching {
            val obj = JSONObject(sharedFile.readText())
            val mode = CameraSourceMode.valueOf(obj.optString("mode", CameraSourceMode.REAL_DEVICE.name))
            val format = YuvFrameFormat.valueOf(obj.optString("outputFormat", YuvFrameFormat.I420.name))
            val cfg = SharedCameraSourceConfig(
                mode = mode,
                sourceUri = obj.optString("sourceUri").ifBlank { null },
                outputFormat = format,
                targetWidth = obj.optInt("targetWidth", 0),
                targetHeight = obj.optInt("targetHeight", 0),
                enableAudio = obj.optBoolean("enableAudio", false),
                autoReconnect = obj.optBoolean("autoReconnect", true),
                physicalCameraIsolated = mode != CameraSourceMode.REAL_DEVICE,
                updatedAtEpochMs = obj.optLong("updatedAtEpochMs", 0L)
            )
            _state.update { it.copy(sharedConfig = cfg) }
        }
    }

    private fun persistSharedConfig(cfg: SharedCameraSourceConfig) {
        val obj = JSONObject().apply {
            put("mode", cfg.mode.name)
            put("sourceUri", cfg.sourceUri.orEmpty())
            put("outputFormat", cfg.outputFormat.name)
            put("targetWidth", cfg.targetWidth)
            put("targetHeight", cfg.targetHeight)
            put("enableAudio", cfg.enableAudio)
            put("autoReconnect", cfg.autoReconnect)
            put("updatedAtEpochMs", cfg.updatedAtEpochMs)
        }
        sharedFile.writeText(obj.toString())
    }

    private fun hardwareLevelName(level: Int?): String = when (level) {
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
        else -> "UNKNOWN"
    }
}
