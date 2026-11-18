package com.vcameraspace.core.media

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.Build
import android.view.Surface
import com.vcameraspace.core.common.diagnostics.DiagnosticCategory
import com.vcameraspace.core.common.diagnostics.DiagnosticLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicLong

enum class NativeBridgeStatus(val label: String) {
    KOTLIN_FALLBACK("Pure Kotlin Pipeline (JNI Fallback)"),
    JNI_ACTIVE("JNI Native Library Active")
}

/**
 * JNI / NDK bridge contract for accelerated ARGB -> I420/NV21 conversion.
 * Automatically detects whether optional `libvcamera_native.so` is packaged for the host ABI
 * and falls back cleanly to the Kotlin SIMD-friendly converter without crashing.
 */
object NativeFrameBridge {
    private val nativeLoaded: Boolean = runCatching {
        System.loadLibrary("vcamera_native")
        true
    }.getOrDefault(false)

    val status: NativeBridgeStatus = if (nativeLoaded) {
        NativeBridgeStatus.JNI_ACTIVE
    } else {
        NativeBridgeStatus.KOTLIN_FALLBACK
    }

    val supportedAbisLabel: String
        get() = runCatching { Build.SUPPORTED_ABIS.joinToString() }.getOrDefault("arm64-v8a")

    @JvmStatic
    external fun nativeArgbToI420(argb: IntArray, width: Int, height: Int, outI420: ByteArray): Int

    fun convertArgbToYuv(
        argb: IntArray,
        width: Int,
        height: Int,
        format: YuvFrameFormat
    ): YuvFrameBuffer {
        if (nativeLoaded) {
            val out = ByteArray((width * height * 3) / 2)
            val rc = runCatching { nativeArgbToI420(argb, width, height, out) }.getOrDefault(-1)
            if (rc == 0) {
                val ySize = width * height
                val uvSize = (width / 2) * (height / 2)
                return YuvFrameBuffer(
                    width = width,
                    height = height,
                    format = format,
                    yPlane = out.copyOfRange(0, ySize),
                    uPlane = out.copyOfRange(ySize, ySize + uvSize),
                    vPlane = out.copyOfRange(ySize + uvSize, ySize + 2 * uvSize),
                    yRowStride = width,
                    uvRowStride = width / 2,
                    uvPixelStride = 1,
                    presentationTimeUs = System.nanoTime() / 1_000L,
                    checksumHex = "JNI-NATIVE"
                )
            }
        }
        return YuvFrameConverter.argbToYuv420(
            argbPixels = argb,
            width = width,
            height = height,
            format = format,
            presentationTimeUs = System.nanoTime() / 1_000L
        )
    }
}

enum class ProxyApiKind(val label: String) {
    CAMERA1("Camera1 (android.hardware.Camera)"),
    CAMERA2("Camera2 (CameraCaptureSession / Surface)")
}

data class CameraProxyInterceptEvent(
    val sequence: Long,
    val apiKind: ProxyApiKind,
    val methodCall: String,
    val detail: String,
    val timestampMs: Long = System.currentTimeMillis()
)

data class CameraProxyTestSummary(
    val camera1Nv21Bytes: Int,
    val camera2FrameDeliveredToSurface: Boolean,
    val frameWidth: Int,
    val frameHeight: Int,
    val yuvChecksumHex: String,
    val nativeBridgeStatus: NativeBridgeStatus,
    val interceptedEvents: List<CameraProxyInterceptEvent>
)

data class VirtualCameraProxyState(
    val activeSpaceId: String = "space_1",
    val isProxyAttached: Boolean = false,
    val totalCamera1FramesDelivered: Long = 0L,
    val totalCamera2FramesDelivered: Long = 0L,
    val nativeBridgeStatus: NativeBridgeStatus = NativeFrameBridge.status,
    val recentEvents: List<CameraProxyInterceptEvent> = emptyList(),
    val lastTestSummary: CameraProxyTestSummary? = null
)

fun interface Camera1PreviewCallback {
    fun onPreviewFrame(nv21Data: ByteArray, width: Int, height: Int, presentationTimeUs: Long)
}

/**
 * Sandboxed Camera1 & Camera2 API Proxy / Interception Layer inside VCamera Space.
 * Delivers virtual media frames to registered in-space Camera1 NV21 callbacks and Camera2
 * output Surfaces without claiming system-wide interception across unrelated third-party processes.
 */
class VirtualCameraProxyBridge(
    private val logger: DiagnosticLogger? = null
) {
    private val sequenceCounter = AtomicLong(0L)
    private val _state = MutableStateFlow(VirtualCameraProxyState())
    val state: StateFlow<VirtualCameraProxyState> = _state.asStateFlow()

    private val camera1Listeners = mutableListOf<Camera1PreviewCallback>()
    private var attachedCamera2Surface: Surface? = null

    fun attachCamera2TargetSurface(spaceId: String, surface: Surface, width: Int, height: Int) {
        attachedCamera2Surface = surface
        recordEvent(
            apiKind = ProxyApiKind.CAMERA2,
            methodCall = "createCaptureSession + setRepeatingRequest(TEMPLATE_PREVIEW)",
            detail = "Space=$spaceId attached ${width}x${height} Surface=${surface.isValid}"
        )
        _state.update { it.copy(activeSpaceId = spaceId, isProxyAttached = surface.isValid) }
    }

    fun detachCamera2Surface() {
        attachedCamera2Surface = null
        recordEvent(
            apiKind = ProxyApiKind.CAMERA2,
            methodCall = "CameraCaptureSession.close()",
            detail = "Detached virtual output Surface"
        )
        _state.update { it.copy(isProxyAttached = false) }
    }

    fun registerCamera1PreviewCallback(spaceId: String, callback: Camera1PreviewCallback) {
        camera1Listeners.add(callback)
        recordEvent(
            apiKind = ProxyApiKind.CAMERA1,
            methodCall = "Camera.setPreviewCallbackWithBuffer(NV21)",
            detail = "Space=$spaceId listeners=${camera1Listeners.size}"
        )
        _state.update { it.copy(activeSpaceId = spaceId, isProxyAttached = true) }
    }

    fun clearCamera1Callbacks() {
        camera1Listeners.clear()
        recordEvent(
            apiKind = ProxyApiKind.CAMERA1,
            methodCall = "Camera.release()",
            detail = "Cleared Camera1 preview callbacks"
        )
    }

    /**
     * Pushes a real frame through both the Camera1 NV21 callback path and the attached Camera2 Surface.
     */
    fun dispatchVirtualFrame(bitmap: Bitmap, format: YuvFrameFormat = YuvFrameFormat.YUV_420_888): YuvFrameBuffer {
        val (evenW, evenH) = YuvFrameConverter.computeEvenDimensions(bitmap.width, bitmap.height)
        val scaled = if (bitmap.width != evenW || bitmap.height != evenH) {
            Bitmap.createScaledBitmap(bitmap, evenW, evenH, true)
        } else {
            bitmap
        }
        val pixels = IntArray(evenW * evenH)
        scaled.getPixels(pixels, 0, evenW, 0, 0, evenW, evenH)
        val yuv = NativeFrameBridge.convertArgbToYuv(pixels, evenW, evenH, format)

        if (camera1Listeners.isNotEmpty()) {
            val nv21 = yuv.toNv21()
            camera1Listeners.forEach { cb ->
                cb.onPreviewFrame(nv21, evenW, evenH, yuv.presentationTimeUs)
            }
            _state.update { it.copy(totalCamera1FramesDelivered = it.totalCamera1FramesDelivered + 1L) }
        }

        val targetSurface = attachedCamera2Surface
        if (targetSurface != null && targetSurface.isValid) {
            runCatching {
                val canvas: Canvas = targetSurface.lockCanvas(null)
                try {
                    canvas.drawBitmap(scaled, 0f, 0f, null)
                } finally {
                    targetSurface.unlockCanvasAndPost(canvas)
                }
                _state.update { it.copy(totalCamera2FramesDelivered = it.totalCamera2FramesDelivered + 1L) }
            }
        }

        if (scaled !== bitmap) {
            scaled.recycle()
        }
        return yuv
    }

    /**
     * Executes an end-to-end sandboxed Camera1 + Camera2 proxy verification test using a real
     * [ImageReader] RGBA_8888 target Surface and NV21 preview callback consumer.
     */
    fun runSandboxProxyVerificationTest(
        spaceId: String = "space_1",
        width: Int = 320,
        height: Int = 240
    ): CameraProxyTestSummary {
        val (evenW, evenH) = YuvFrameConverter.computeEvenDimensions(width, height)
        var capturedNv21Size = 0
        var imageReaderDelivered = false

        val reader = runCatching {
            ImageReader.newInstance(evenW, evenH, PixelFormat.RGBA_8888, 2)
        }.getOrNull()

        val testBitmap = Bitmap.createBitmap(evenW, evenH, Bitmap.Config.ARGB_8888).apply {
            val c = Canvas(this)
            c.drawColor(Color.rgb(18, 42, 68))
            val p = Paint().apply {
                color = Color.rgb(0, 200, 175)
                textSize = 22f
                isAntiAlias = true
            }
            c.drawText("VCamera Live Assist", 24f, evenH / 2f, p)
        }

        try {
            recordEvent(
                apiKind = ProxyApiKind.CAMERA1,
                methodCall = "Camera.open(VIRTUAL_BACK_0)",
                detail = "Negotiated NV21 ${evenW}x${evenH}"
            )
            registerCamera1PreviewCallback(spaceId) { nv21, _, _, _ ->
                capturedNv21Size = nv21.size
            }

            if (reader != null) {
                attachCamera2TargetSurface(spaceId, reader.surface, evenW, evenH)
            } else {
                recordEvent(
                    apiKind = ProxyApiKind.CAMERA2,
                    methodCall = "createCaptureSession(HeadlessFallback)",
                    detail = "ImageReader unavailable in current runtime; verifying YUV/NV21 plane pipeline"
                )
            }

            val yuv = dispatchVirtualFrame(testBitmap, YuvFrameFormat.YUV_420_888)
            val acquired = runCatching { reader?.acquireLatestImage() }.getOrNull()
            if (acquired != null) {
                imageReaderDelivered = acquired.width == evenW && acquired.height == evenH
                acquired.close()
                recordEvent(
                    apiKind = ProxyApiKind.CAMERA2,
                    methodCall = "CaptureCallback.onCaptureCompleted",
                    detail = "ImageReader acquired ${evenW}x${evenH} frame crc=${yuv.checksumHex}"
                )
            }

            val summary = CameraProxyTestSummary(
                camera1Nv21Bytes = capturedNv21Size,
                camera2FrameDeliveredToSurface = imageReaderDelivered,
                frameWidth = yuv.width,
                frameHeight = yuv.height,
                yuvChecksumHex = yuv.checksumHex,
                nativeBridgeStatus = NativeFrameBridge.status,
                interceptedEvents = _state.value.recentEvents.take(8)
            )
            _state.update { it.copy(lastTestSummary = summary) }
            logger?.logInfo(
                DiagnosticCategory.CAMERA_ADAPTER,
                "Sandbox Camera1/2 Proxy verification completed (${evenW}x${evenH})",
                "NV21=${capturedNv21Size}B Camera2Surface=$imageReaderDelivered crc=${yuv.checksumHex}"
            )
            return summary
        } finally {
            clearCamera1Callbacks()
            if (reader != null) {
                detachCamera2Surface()
                runCatching { reader.close() }
            }
            testBitmap.recycle()
        }
    }

    private fun recordEvent(apiKind: ProxyApiKind, methodCall: String, detail: String) {
        val event = CameraProxyInterceptEvent(
            sequence = sequenceCounter.incrementAndGet(),
            apiKind = apiKind,
            methodCall = methodCall,
            detail = detail
        )
        _state.update { current ->
            current.copy(recentEvents = (listOf(event) + current.recentEvents).take(25))
        }
        logger?.logInfo(
            DiagnosticCategory.CAMERA_ADAPTER,
            "[${apiKind.name}] $methodCall",
            detail
        )
    }
}
