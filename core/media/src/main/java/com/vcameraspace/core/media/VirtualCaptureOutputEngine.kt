package com.vcameraspace.core.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import com.vcameraspace.core.common.coroutines.DispatcherProvider
import com.vcameraspace.core.common.diagnostics.DiagnosticCategory
import com.vcameraspace.core.common.diagnostics.DiagnosticLogger
import com.vcameraspace.core.common.model.AppError
import com.vcameraspace.core.common.model.AppResult
import com.vcameraspace.virtualengine.api.CameraSourceMode
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer

data class CapturedPhotoResult(
    val file: File,
    val contentUri: Uri?,
    val width: Int,
    val height: Int,
    val sizeBytes: Long,
    val summary: String
)

data class CapturedClipResult(
    val file: File,
    val contentUri: Uri?,
    val width: Int,
    val height: Int,
    val durationMs: Long,
    val sizeBytes: Long,
    val usedDirectStreamCopy: Boolean,
    val summary: String
)

/**
 * Captures snapshots (JPEG) and virtual video clips (MP4) from the active
 * OpenGL ES viewfinder or configured Media Section source, writing cleanly to
 * scoped internal directories and/or caller-supplied EXTRA_OUTPUT URIs.
 */
class VirtualCaptureExporter(
    private val context: Context,
    private val dispatchers: DispatcherProvider,
    private val logger: DiagnosticLogger? = null
) {
    fun photosDirectory(): File = File(File(context.filesDir, "captures"), "photos").apply { mkdirs() }

    fun clipsDirectory(): File = File(File(context.filesDir, "captures"), "clips").apply { mkdirs() }

    suspend fun captureSnapshotBitmap(
        mode: CameraSourceMode,
        sourceUri: String?,
        liveViewBitmap: Bitmap?,
        orientationDegrees: Int = 0,
        mirrorHorizontal: Boolean = false,
        targetWidth: Int = 0,
        targetHeight: Int = 0
    ): AppResult<Bitmap> = withContext(dispatchers.io) {
        runCatching {
            val baseBitmap: Bitmap = when {
                liveViewBitmap != null && !liveViewBitmap.isRecycled -> {
                    liveViewBitmap.copy(Bitmap.Config.ARGB_8888, false)
                }
                mode == CameraSourceMode.LOCAL_PICTURE && !sourceUri.isNullOrBlank() -> {
                    decodeAndTransformPicture(
                        uriString = sourceUri,
                        orientationDegrees = orientationDegrees,
                        mirrorHorizontal = mirrorHorizontal,
                        targetWidth = targetWidth,
                        targetHeight = targetHeight
                    )
                }
                (mode == CameraSourceMode.LOCAL_VIDEO || mode == CameraSourceMode.NETWORK_STREAM) && !sourceUri.isNullOrBlank() -> {
                    val retrieved = extractVideoFrameViaRetriever(sourceUri)
                    if (retrieved != null) {
                        applyBitmapTransform(retrieved, orientationDegrees, mirrorHorizontal)
                    } else {
                        createSyntheticFallbackBitmap(
                            label = if (mode == CameraSourceMode.LOCAL_VIDEO) "Virtual Video Frame" else "Virtual Stream Frame",
                            width = targetWidth.takeIf { it > 0 } ?: 1280,
                            height = targetHeight.takeIf { it > 0 } ?: 720,
                            orientationDegrees = orientationDegrees,
                            mirrorHorizontal = mirrorHorizontal
                        )
                    }
                }
                else -> {
                    createSyntheticFallbackBitmap(
                        label = "VCamera Live Assist Snapshot",
                        width = targetWidth.takeIf { it > 0 } ?: 1280,
                        height = targetHeight.takeIf { it > 0 } ?: 720,
                        orientationDegrees = orientationDegrees,
                        mirrorHorizontal = mirrorHorizontal
                    )
                }
            }
            baseBitmap
        }.fold(
            onSuccess = { AppResult.Success(it) },
            onFailure = { throwable ->
                logger?.logError(DiagnosticCategory.MEDIA_PIPELINE, "Snapshot capture failed", throwable.message)
                AppResult.Failure(
                    AppError.MediaPlaybackError(
                        message = throwable.message ?: "Unable to capture virtual snapshot."
                    )
                )
            }
        )
    }

    suspend fun writeSnapshotToTarget(
        bitmap: Bitmap,
        destinationUri: Uri? = null,
        contentUriResolver: ((File) -> Uri?)? = null
    ): AppResult<CapturedPhotoResult> = withContext(dispatchers.io) {
        runCatching {
            val internalFile = File(photosDirectory(), "vcam_photo_${System.currentTimeMillis()}.jpg")
            internalFile.outputStream().use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)) {
                    error("JPEG compression failed.")
                }
            }

            if (destinationUri != null) {
                context.contentResolver.openOutputStream(destinationUri, "w")?.use { targetOut ->
                    internalFile.inputStream().use { srcIn -> srcIn.copyTo(targetOut) }
                } ?: context.contentResolver.openOutputStream(destinationUri)?.use { targetOut ->
                    internalFile.inputStream().use { srcIn -> srcIn.copyTo(targetOut) }
                } ?: error("Unable to open caller output URI for snapshot write.")
            }

            val resolvedUri = destinationUri ?: contentUriResolver?.invoke(internalFile)
            val sizeBytes = internalFile.length()
            val summary = "Saved ${bitmap.width}x${bitmap.height} JPEG (${formatSizeKb(sizeBytes)})"
            logger?.logInfo(DiagnosticCategory.MEDIA_PIPELINE, "Virtual snapshot captured", summary)
            CapturedPhotoResult(
                file = internalFile,
                contentUri = resolvedUri,
                width = bitmap.width,
                height = bitmap.height,
                sizeBytes = sizeBytes,
                summary = summary
            )
        }.fold(
            onSuccess = { AppResult.Success(it) },
            onFailure = { throwable ->
                logger?.logError(DiagnosticCategory.MEDIA_PIPELINE, "Snapshot write failed", throwable.message)
                AppResult.Failure(
                    AppError.MediaPlaybackError(
                        message = throwable.message ?: "Unable to write captured photo."
                    )
                )
            }
        )
    }

    fun createCaptureThumbnail(bitmap: Bitmap, maxEdge: Int = 320): Bitmap {
        val longEdge = maxOf(bitmap.width, bitmap.height).coerceAtLeast(1)
        if (longEdge <= maxEdge) {
            return bitmap.copy(Bitmap.Config.ARGB_8888, false)
        }
        val scale = maxEdge.toFloat() / longEdge.toFloat()
        val w = (bitmap.width * scale).toInt().coerceAtLeast(2)
        val h = (bitmap.height * scale).toInt().coerceAtLeast(2)
        return Bitmap.createScaledBitmap(bitmap, w, h, true)
    }

    suspend fun exportVirtualClip(
        mode: CameraSourceMode,
        sourceUri: String?,
        liveViewBitmap: Bitmap? = null,
        orientationDegrees: Int = 0,
        mirrorHorizontal: Boolean = false,
        destinationUri: Uri? = null,
        contentUriResolver: ((File) -> Uri?)? = null,
        maxDurationMs: Long = 8_000L
    ): AppResult<CapturedClipResult> = withContext(dispatchers.io) {
        runCatching {
            val outFile = File(clipsDirectory(), "vcam_clip_${System.currentTimeMillis()}.mp4")
            var width = 1280
            var height = 720
            var durationMs = 3_000L
            var copiedDirect = false

            when (mode) {
                CameraSourceMode.LOCAL_VIDEO -> {
                    val uriStr = sourceUri?.takeIf { it.isNotBlank() }
                        ?: error("Select a local video in Media Section before capturing a clip.")
                    val uri = Uri.parse(uriStr)
                    val meta = inspectDimensionsAndDuration(uri)
                    width = meta.first
                    height = meta.second
                    val rawDuration = meta.third
                    val trimmed = runCatching {
                        trimOrRemuxVideoToMp4(uri, outFile, maxDurationMs * 1_000L)
                    }.getOrNull()
                    if (trimmed != null && outFile.length() > 0L) {
                        durationMs = trimmed.coerceAtLeast(500L)
                    } else {
                        copyStreamToFile(uri, outFile)
                        durationMs = rawDuration.takeIf { it > 0L }?.coerceAtMost(maxDurationMs) ?: 3_000L
                        copiedDirect = true
                    }
                }
                CameraSourceMode.LOCAL_PICTURE -> {
                    val bmp = when {
                        liveViewBitmap != null && !liveViewBitmap.isRecycled -> {
                            liveViewBitmap.copy(Bitmap.Config.ARGB_8888, false)
                        }
                        !sourceUri.isNullOrBlank() -> {
                            decodeAndTransformPicture(sourceUri, orientationDegrees, mirrorHorizontal, 640, 480)
                        }
                        else -> error("Select a local picture before capturing a virtual clip.")
                    }
                    val (evenW, evenH) = YuvFrameConverter.computeEvenDimensions(bmp.width, bmp.height, maxLongEdge = 720)
                    width = evenW
                    height = evenH
                    durationMs = 2_000L
                    encodeStillBitmapClip(bmp, evenW, evenH, durationMs, outFile)
                    if (bmp !== liveViewBitmap) {
                        bmp.recycle()
                    }
                }
                CameraSourceMode.NETWORK_STREAM,
                CameraSourceMode.REAL_DEVICE -> {
                    val bmp = liveViewBitmap?.takeIf { !it.isRecycled }?.copy(Bitmap.Config.ARGB_8888, false)
                        ?: createSyntheticFallbackBitmap(
                            label = if (mode == CameraSourceMode.NETWORK_STREAM) "Stream Capture Clip" else "VCamera Capture Clip",
                            width = 640,
                            height = 480,
                            orientationDegrees = orientationDegrees,
                            mirrorHorizontal = mirrorHorizontal
                        )
                    val (evenW, evenH) = YuvFrameConverter.computeEvenDimensions(bmp.width, bmp.height, maxLongEdge = 720)
                    width = evenW
                    height = evenH
                    durationMs = 2_000L
                    encodeStillBitmapClip(bmp, evenW, evenH, durationMs, outFile)
                    bmp.recycle()
                }
            }

            if (destinationUri != null) {
                context.contentResolver.openOutputStream(destinationUri, "w")?.use { out ->
                    outFile.inputStream().use { src -> src.copyTo(out) }
                } ?: context.contentResolver.openOutputStream(destinationUri)?.use { out ->
                    outFile.inputStream().use { src -> src.copyTo(out) }
                } ?: error("Unable to open caller output URI for video clip.")
            }

            val resolvedUri = destinationUri ?: contentUriResolver?.invoke(outFile)
            val sizeBytes = outFile.length()
            val summary = "Exported ${width}x${height} MP4 (${MediaInspector.formatDuration(durationMs)}, ${formatSizeKb(sizeBytes)})"
            logger?.logInfo(DiagnosticCategory.MEDIA_PIPELINE, "Virtual clip exported", summary)

            CapturedClipResult(
                file = outFile,
                contentUri = resolvedUri,
                width = width,
                height = height,
                durationMs = durationMs,
                sizeBytes = sizeBytes,
                usedDirectStreamCopy = copiedDirect,
                summary = summary
            )
        }.fold(
            onSuccess = { AppResult.Success(it) },
            onFailure = { throwable ->
                logger?.logError(DiagnosticCategory.MEDIA_PIPELINE, "Virtual clip export failed", throwable.message)
                AppResult.Failure(
                    AppError.MediaPlaybackError(
                        message = throwable.message ?: "Unable to export virtual video clip."
                    )
                )
            }
        )
    }

    private fun decodeAndTransformPicture(
        uriString: String,
        orientationDegrees: Int,
        mirrorHorizontal: Boolean,
        targetWidth: Int,
        targetHeight: Int
    ): Bitmap {
        val uri = Uri.parse(uriString)
        val raw = context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream)
        } ?: error("Unable to open local picture for capture.")
        val scaled = if (targetWidth > 0 && targetHeight > 0 && (raw.width != targetWidth || raw.height != targetHeight)) {
            Bitmap.createScaledBitmap(raw, targetWidth, targetHeight, true).also {
                if (it !== raw) raw.recycle()
            }
        } else {
            raw
        }
        return applyBitmapTransform(scaled, orientationDegrees, mirrorHorizontal)
    }

    private fun applyBitmapTransform(
        source: Bitmap,
        orientationDegrees: Int,
        mirrorHorizontal: Boolean
    ): Bitmap {
        val normalizedRotation = ((orientationDegrees % 360) + 360) % 360
        if (normalizedRotation == 0 && !mirrorHorizontal) {
            return source
        }
        val matrix = Matrix().apply {
            if (mirrorHorizontal) {
                postScale(-1f, 1f, source.width / 2f, source.height / 2f)
            }
            if (normalizedRotation != 0) {
                postRotate(normalizedRotation.toFloat())
            }
        }
        val transformed = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
        if (transformed !== source) {
            source.recycle()
        }
        return transformed
    }

    private fun extractVideoFrameViaRetriever(uriString: String): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            val uri = Uri.parse(uriString)
            if (uri.scheme == "http" || uri.scheme == "https" || uri.scheme == "rtsp") {
                retriever.setDataSource(uriString, emptyMap())
            } else {
                retriever.setDataSource(context, uri)
            }
            retriever.getFrameAtTime(-1L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun inspectDimensionsAndDuration(uri: Uri): Triple<Int, Int, Long> {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 1280
            val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 720
            val dur = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 3_000L
            Triple(w, h, dur)
        } catch (_: Throwable) {
            Triple(1280, 720, 3_000L)
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun trimOrRemuxVideoToMp4(uri: Uri, outFile: File, maxDurationUs: Long): Long {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            extractor.setDataSource(context, uri, null)
            muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val trackIndexMap = mutableMapOf<Int, Int>()
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                    val dstIdx = muxer.addTrack(format)
                    trackIndexMap[i] = dstIdx
                    extractor.selectTrack(i)
                }
            }
            if (trackIndexMap.isEmpty()) error("No audio/video tracks available for clip muxer.")
            muxer.start()

            val buffer = ByteBuffer.allocate(512 * 1024)
            val info = MediaCodec.BufferInfo()
            var firstPtsUs: Long? = null
            var lastPtsUs = 0L

            while (true) {
                buffer.clear()
                val sampleSize = extractor.readSampleData(buffer, 0)
                if (sampleSize < 0) break
                val sampleTimeUs = extractor.sampleTime
                if (firstPtsUs == null) firstPtsUs = sampleTimeUs
                val relativeUs = (sampleTimeUs - (firstPtsUs ?: 0L)).coerceAtLeast(0L)
                if (relativeUs > maxDurationUs) break

                val srcTrack = extractor.sampleTrackIndex
                val dstTrack = trackIndexMap[srcTrack]
                if (dstTrack != null) {
                    info.offset = 0
                    info.size = sampleSize
                    info.presentationTimeUs = sampleTimeUs
                    info.flags = if ((extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
                        MediaCodec.BUFFER_FLAG_KEY_FRAME
                    } else {
                        0
                    }
                    muxer.writeSampleData(dstTrack, buffer, info)
                    lastPtsUs = relativeUs
                }
                extractor.advance()
            }
            return (lastPtsUs / 1_000L).coerceAtLeast(500L)
        } finally {
            runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun copyStreamToFile(uri: Uri, outFile: File) {
        context.contentResolver.openInputStream(uri)?.use { input ->
            outFile.outputStream().use { output -> input.copyTo(output) }
        } ?: error("Unable to open source video stream for copy.")
    }

    private fun encodeStillBitmapClip(
        bitmap: Bitmap,
        width: Int,
        height: Int,
        durationMs: Long,
        outFile: File
    ) {
        val scaled = if (bitmap.width != width || bitmap.height != height) {
            Bitmap.createScaledBitmap(bitmap, width, height, true)
        } else {
            bitmap
        }
        val yuv = YuvFrameConverter.bitmapToYuvFrame(scaled, width, height, YuvFrameFormat.I420).toPackedI420()
        if (scaled !== bitmap) scaled.recycle()

        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        var trackIndex = -1
        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                setInteger(MediaFormat.KEY_BIT_RATE, 1_500_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, 15)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }
            muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            val totalFrames = ((durationMs / 1_000.0) * 15).toInt().coerceIn(8, 45)
            val bufferInfo = MediaCodec.BufferInfo()
            var submittedFrames = 0
            var inputDone = false
            var outputDone = false
            val deadlineMs = System.currentTimeMillis() + 6_000L

            while (!outputDone && System.currentTimeMillis() < deadlineMs) {
                if (!inputDone) {
                    val inIdx = encoder.dequeueInputBuffer(10_000L)
                    if (inIdx >= 0) {
                        val inBuf = encoder.getInputBuffer(inIdx)
                        if (inBuf != null) {
                            if (submittedFrames >= totalFrames) {
                                encoder.queueInputBuffer(inIdx, 0, 0, submittedFrames * 66_666L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                inBuf.clear()
                                val toCopy = minOf(inBuf.remaining(), yuv.size)
                                inBuf.put(yuv, 0, toCopy)
                                encoder.queueInputBuffer(inIdx, 0, toCopy, submittedFrames * 66_666L, 0)
                                submittedFrames++
                            }
                        }
                    }
                }

                val outIdx = encoder.dequeueOutputBuffer(bufferInfo, 10_000L)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (!muxerStarted) {
                            trackIndex = muxer.addTrack(encoder.outputFormat)
                            muxer.start()
                            muxerStarted = true
                        }
                    }
                    outIdx >= 0 -> {
                        val encoded = encoder.getOutputBuffer(outIdx)
                        if (encoded != null && (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && bufferInfo.size > 0 && muxerStarted) {
                            encoded.position(bufferInfo.offset)
                            encoded.limit(bufferInfo.offset + bufferInfo.size)
                            muxer.writeSampleData(trackIndex, encoded, bufferInfo)
                        }
                        encoder.releaseOutputBuffer(outIdx, false)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputDone = true
                        }
                    }
                }
            }
        } catch (_: Throwable) {
            // Fallback container header marker only if device/Robolectric lacks hardware AVC encoder
            if (!outFile.exists() || outFile.length() == 0L) {
                error("No H.264 encoder available on this environment to synthesize clip from still picture.")
            }
        } finally {
            if (muxerStarted) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { encoder?.stop() }
            runCatching { encoder?.release() }
        }
    }

    private fun createSyntheticFallbackBitmap(
        label: String,
        width: Int,
        height: Int,
        orientationDegrees: Int,
        mirrorHorizontal: Boolean
    ): Bitmap {
        val safeW = width.coerceIn(320, 1280)
        val safeH = height.coerceIn(240, 1280)
        val raw = Bitmap.createBitmap(safeW, safeH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(raw)
        canvas.drawColor(Color.rgb(16, 26, 28))
        val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(0, 150, 136)
            style = Paint.Style.STROKE
            strokeWidth = 6f
        }
        canvas.drawRect(24f, 24f, safeW - 24f, safeH - 24f, accentPaint)
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 36f
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(label, safeW / 2f, safeH / 2f, textPaint)
        return applyBitmapTransform(raw, orientationDegrees, mirrorHorizontal)
    }

    private fun formatSizeKb(bytes: Long): String {
        val kb = (bytes / 1024L).coerceAtLeast(1L)
        return "$kb KB"
    }
}
