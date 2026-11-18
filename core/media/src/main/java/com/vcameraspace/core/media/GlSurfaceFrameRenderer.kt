package com.vcameraspace.core.media

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLUtils
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import com.vcameraspace.core.common.diagnostics.DiagnosticCategory
import com.vcameraspace.core.common.diagnostics.DiagnosticLogger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt

enum class GlColorFilterMode(val id: Int, val label: String) {
    STANDARD(0, "Standard RGB/YUV"),
    LUMA_ASSIST(1, "BT.709 Luma Assist"),
    WARM_STUDIO(2, "Warm Studio Grade")
}

data class GlRenderTelemetry(
    val isGlActive: Boolean = false,
    val renderedFrames: Long = 0L,
    val measuredFps: Double = 0.0,
    val glRendererName: String = "OpenGL ES 2.0",
    val rotationDegrees: Int = 0,
    val mirrorHorizontal: Boolean = false,
    val filterMode: GlColorFilterMode = GlColorFilterMode.STANDARD,
    val errorDetail: String? = null
)

/**
 * Pure-JVM testable 4x4 matrix math for OpenGL ES quad rotation, horizontal mirror flip,
 * and aspect-preserving viewport scaling.
 */
object GlTransformMatrixMath {

    fun buildVertexTransformMatrix(
        rotationDegrees: Int,
        mirrorHorizontal: Boolean,
        sourceWidth: Int,
        sourceHeight: Int,
        viewportWidth: Int,
        viewportHeight: Int
    ): FloatArray {
        val matrix = FloatArray(16)
        // Identity initialization without android.opengl.Matrix so pure JVM unit tests run natively.
        matrix[0] = 1f; matrix[5] = 1f; matrix[10] = 1f; matrix[15] = 1f

        val normalizedRot = ((rotationDegrees % 360) + 360) % 360
        val effectiveSrcW = if (normalizedRot == 90 || normalizedRot == 270) sourceHeight else sourceWidth
        val effectiveSrcH = if (normalizedRot == 90 || normalizedRot == 270) sourceWidth else sourceHeight

        var fitScaleX = 1f
        var fitScaleY = 1f
        if (effectiveSrcW > 0 && effectiveSrcH > 0 && viewportWidth > 0 && viewportHeight > 0) {
            val srcAspect = effectiveSrcW.toFloat() / effectiveSrcH.toFloat()
            val viewAspect = viewportWidth.toFloat() / viewportHeight.toFloat()
            if (srcAspect > viewAspect) {
                fitScaleY = viewAspect / srcAspect
            } else {
                fitScaleX = srcAspect / viewAspect
            }
        }

        val rad = Math.toRadians(-normalizedRot.toDouble())
        val cos = kotlin.math.cos(rad).toFloat()
        val sin = kotlin.math.sin(rad).toFloat()
        val mirror = if (mirrorHorizontal) -1f else 1f

        // Column-major matrix = Scale(fitScaleX, fitScaleY) * RotateZ(-rot) * Scale(mirror, 1)
        matrix[0] = fitScaleX * cos * mirror
        matrix[1] = fitScaleY * sin * mirror
        matrix[4] = -fitScaleX * sin
        matrix[5] = fitScaleY * cos
        matrix[10] = 1f
        matrix[15] = 1f
        return matrix
    }
}

/**
 * Dedicated GL thread managing EGL14 context, SurfaceTexture external OES input (from MediaCodec / ExoPlayer)
 * or 2D Bitmap texture input, GLSL shader rotation/mirror/color conversion, and window Surface output.
 */
class EglVirtualSurfacePipeline(
    private val outputSurfaceTexture: SurfaceTexture,
    private var viewportWidth: Int,
    private var viewportHeight: Int,
    private val logger: DiagnosticLogger? = null,
    private val onInputSurfaceReady: (Surface) -> Unit = {},
    private val onTelemetry: (GlRenderTelemetry) -> Unit = {}
) {
    private val released = AtomicBoolean(false)
    private val frameAvailable = AtomicBoolean(false)
    private val frameCounter = AtomicLong(0L)

    @Volatile
    private var rotationDegrees: Int = 0
    @Volatile
    private var mirrorHorizontal: Boolean = false
    @Volatile
    private var filterMode: GlColorFilterMode = GlColorFilterMode.STANDARD
    @Volatile
    private var sourceWidth: Int = 1280
    @Volatile
    private var sourceHeight: Int = 720
    @Volatile
    private var pendingBitmap: Bitmap? = null

    private var handlerThread: HandlerThread? = null
    private var glHandler: Handler? = null

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    private var oesProgramId: Int = 0
    private var tex2dProgramId: Int = 0
    private var oesTextureId: Int = 0
    private var photoTextureId: Int = 0
    private var hasPhotoUploaded: Boolean = false

    private var inputSurfaceTexture: SurfaceTexture? = null
    private var inputSurface: Surface? = null
    private var glRendererName: String = "OpenGL ES 2.0"

    private val texMatrix = FloatArray(16).apply { Matrix.setIdentityM(this, 0) }
    private var fpsWindowStartMs = 0L
    private var fpsWindowFrames = 0L
    private var lastMeasuredFps = 0.0

    private val vertexCoords = floatArrayOf(
        -1f, -1f, 0f, 0f,
         1f, -1f, 1f, 0f,
        -1f,  1f, 0f, 1f,
         1f,  1f, 1f, 1f
    )
    private val vertexBuffer: FloatBuffer = ByteBuffer
        .allocateDirect(vertexCoords.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(vertexCoords)
            position(0)
        }

    fun start() {
        val thread = HandlerThread("VCameraGlPipeline").apply { start() }
        handlerThread = thread
        val handler = Handler(thread.looper)
        glHandler = handler
        handler.post { initEglAndShaders() }
    }

    fun updateTransform(
        rotationDegrees: Int,
        mirrorHorizontal: Boolean,
        filterMode: GlColorFilterMode
    ) {
        this.rotationDegrees = ((rotationDegrees % 360) + 360) % 360
        this.mirrorHorizontal = mirrorHorizontal
        this.filterMode = filterMode
        glHandler?.post { renderFrameIfReady(forceRedraw = true) }
    }

    fun updateViewport(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        viewportWidth = width
        viewportHeight = height
        glHandler?.post {
            if (!released.get() && eglSurface != EGL14.EGL_NO_SURFACE) {
                GLES20.glViewport(0, 0, viewportWidth, viewportHeight)
                renderFrameIfReady(forceRedraw = true)
            }
        }
    }

    fun updateSourceDimensions(width: Int, height: Int) {
        if (width > 0 && height > 0) {
            sourceWidth = width
            sourceHeight = height
        }
    }

    fun submitBitmapFrame(bitmap: Bitmap) {
        sourceWidth = bitmap.width
        sourceHeight = bitmap.height
        pendingBitmap = bitmap
        glHandler?.post { renderFrameIfReady(forceRedraw = true) }
    }

    fun release() {
        if (!released.compareAndSet(false, true)) return
        val handler = glHandler
        if (handler != null) {
            handler.post {
                releaseInternal()
                handlerThread?.quitSafely()
            }
        } else {
            handlerThread?.quitSafely()
        }
    }

    private fun initEglAndShaders() {
        runCatching {
            eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            check(eglDisplay != EGL14.EGL_NO_DISPLAY) { "eglGetDisplay failed" }

            val version = IntArray(2)
            check(EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) { "eglInitialize failed" }

            val attribList = intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            check(EGL14.eglChooseConfig(eglDisplay, attribList, 0, configs, 0, 1, numConfigs, 0)) {
                "eglChooseConfig failed"
            }
            val config = configs[0] ?: error("No EGLConfig matched OpenGL ES 2.0")

            val contextAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
            eglContext = EGL14.eglCreateContext(eglDisplay, config, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
            check(eglContext != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }

            val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
            eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, config, outputSurfaceTexture, surfaceAttribs, 0)
            check(eglSurface != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface failed" }

            check(EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
                "eglMakeCurrent failed"
            }

            glRendererName = GLES20.glGetString(GLES20.GL_RENDERER) ?: "OpenGL ES 2.0"
            oesProgramId = compileProgram(VERTEX_SHADER, OES_FRAGMENT_SHADER)
            tex2dProgramId = compileProgram(VERTEX_SHADER, TEX2D_FRAGMENT_SHADER)

            val textures = IntArray(2)
            GLES20.glGenTextures(2, textures, 0)
            oesTextureId = textures[0]
            photoTextureId = textures[1]

            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, photoTextureId)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

            val st = SurfaceTexture(oesTextureId).apply {
                setOnFrameAvailableListener({
                    frameAvailable.set(true)
                    glHandler?.post { renderFrameIfReady(forceRedraw = false) }
                }, glHandler)
            }
            inputSurfaceTexture = st
            val surface = Surface(st)
            inputSurface = surface

            GLES20.glViewport(0, 0, viewportWidth.coerceAtLeast(1), viewportHeight.coerceAtLeast(1))
            fpsWindowStartMs = System.currentTimeMillis()

            logger?.logInfo(
                DiagnosticCategory.MEDIA_PIPELINE,
                "OpenGL ES SurfaceTexture pipeline ready ($glRendererName)"
            )
            onInputSurfaceReady(surface)
            emitTelemetry(isGlActive = true)
        }.onFailure { err ->
            logger?.logError(DiagnosticCategory.MEDIA_PIPELINE, "OpenGL ES init failed", err.message)
            emitTelemetry(isGlActive = false, errorDetail = err.message ?: "EGL initialization failed")
        }
    }

    private fun renderFrameIfReady(forceRedraw: Boolean) {
        if (released.get() || eglDisplay == EGL14.EGL_NO_DISPLAY || eglSurface == EGL14.EGL_NO_SURFACE) return

        val pendingBmp = pendingBitmap
        if (pendingBmp != null) {
            pendingBitmap = null
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, photoTextureId)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, pendingBmp, 0)
            hasPhotoUploaded = true
        }

        val consumedVideoFrame = frameAvailable.compareAndSet(true, false)
        if (consumedVideoFrame) {
            inputSurfaceTexture?.updateTexImage()
            inputSurfaceTexture?.getTransformMatrix(texMatrix)
        } else if (!forceRedraw && !hasPhotoUploaded) {
            return
        }

        GLES20.glClearColor(0.06f, 0.08f, 0.11f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        val usePhotoProgram = hasPhotoUploaded && !consumedVideoFrame && inputSurfaceTexture == null || (hasPhotoUploaded && pendingBmp == null && frameCounter.get() == 0L && !consumedVideoFrame)
        val program = if (usePhotoProgram && hasPhotoUploaded) tex2dProgramId else oesProgramId
        if (program == 0) return

        GLES20.glUseProgram(program)

        val aPos = GLES20.glGetAttribLocation(program, "aPosition")
        val aTex = GLES20.glGetAttribLocation(program, "aTexCoord")
        val uMvp = GLES20.glGetUniformLocation(program, "uMvpMatrix")
        val uTexMat = GLES20.glGetUniformLocation(program, "uTexMatrix")
        val uFilter = GLES20.glGetUniformLocation(program, "uFilterMode")

        vertexBuffer.position(0)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, vertexBuffer)

        vertexBuffer.position(2)
        GLES20.glEnableVertexAttribArray(aTex)
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 16, vertexBuffer)

        val mvp = GlTransformMatrixMath.buildVertexTransformMatrix(
            rotationDegrees = rotationDegrees,
            mirrorHorizontal = mirrorHorizontal,
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
            viewportWidth = viewportWidth,
            viewportHeight = viewportHeight
        )
        GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)

        val activeTexMatrix = if (usePhotoProgram && hasPhotoUploaded) {
            FloatArray(16).apply {
                Matrix.setIdentityM(this, 0)
                // Flip Y for Bitmap GL_TEXTURE_2D coordinates
                this[5] = -1f
                this[13] = 1f
            }
        } else {
            texMatrix
        }
        GLES20.glUniformMatrix4fv(uTexMat, 1, false, activeTexMatrix, 0)
        GLES20.glUniform1i(uFilter, filterMode.id)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        if (usePhotoProgram && hasPhotoUploaded) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, photoTextureId)
        } else {
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
        }

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        EGL14.eglSwapBuffers(eglDisplay, eglSurface)

        val total = frameCounter.incrementAndGet()
        fpsWindowFrames++
        val now = System.currentTimeMillis()
        val elapsed = now - fpsWindowStartMs
        if (elapsed >= 1000L) {
            lastMeasuredFps = ((fpsWindowFrames * 1000.0 / elapsed) * 10.0).roundToInt() / 10.0
            fpsWindowFrames = 0L
            fpsWindowStartMs = now
            emitTelemetry(isGlActive = true)
        } else if (total <= 2L || forceRedraw) {
            emitTelemetry(isGlActive = true)
        }
    }

    private fun emitTelemetry(isGlActive: Boolean, errorDetail: String? = null) {
        onTelemetry(
            GlRenderTelemetry(
                isGlActive = isGlActive,
                renderedFrames = frameCounter.get(),
                measuredFps = lastMeasuredFps,
                glRendererName = glRendererName,
                rotationDegrees = rotationDegrees,
                mirrorHorizontal = mirrorHorizontal,
                filterMode = filterMode,
                errorDetail = errorDetail
            )
        )
    }

    private fun releaseInternal() {
        runCatching { inputSurface?.release() }
        runCatching { inputSurfaceTexture?.release() }
        if (oesProgramId != 0) GLES20.glDeleteProgram(oesProgramId)
        if (tex2dProgramId != 0) GLES20.glDeleteProgram(tex2dProgramId)
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface)
            if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
            EGL14.eglTerminate(eglDisplay)
        }
        eglDisplay = EGL14.EGL_NO_DISPLAY
        eglContext = EGL14.EGL_NO_CONTEXT
        eglSurface = EGL14.EGL_NO_SURFACE
    }

    private fun compileProgram(vertexSrc: String, fragmentSrc: String): Int {
        val vs = compileShader(GLES20.GL_VERTEX_SHADER, vertexSrc)
        val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSrc)
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vs)
        GLES20.glAttachShader(program, fs)
        GLES20.glLinkProgram(program)
        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            val info = GLES20.glGetProgramInfoLog(program)
            GLES20.glDeleteProgram(program)
            error("GL program link failed: $info")
        }
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
        return program
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val info = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            error("GL shader compile failed: $info")
        }
        return shader
    }

    private companion object {
        const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            uniform mat4 uMvpMatrix;
            uniform mat4 uTexMatrix;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = uMvpMatrix * aPosition;
                vTexCoord = (uTexMatrix * aTexCoord).xy;
            }
        """

        const val OES_FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES uTexture;
            uniform int uFilterMode;
            void main() {
                vec4 c = texture2D(uTexture, vTexCoord);
                if (uFilterMode == 1) {
                    float luma = dot(c.rgb, vec3(0.2126, 0.7152, 0.0722));
                    gl_FragColor = vec4(vec3(luma), c.a);
                } else if (uFilterMode == 2) {
                    gl_FragColor = vec4(min(c.r * 1.08, 1.0), c.g * 1.02, c.b * 0.92, c.a);
                } else {
                    gl_FragColor = c;
                }
            }
        """

        const val TEX2D_FRAGMENT_SHADER = """
            precision mediump float;
            varying vec2 vTexCoord;
            uniform sampler2D uTexture;
            uniform int uFilterMode;
            void main() {
                vec4 c = texture2D(uTexture, vTexCoord);
                if (uFilterMode == 1) {
                    float luma = dot(c.rgb, vec3(0.2126, 0.7152, 0.0722));
                    gl_FragColor = vec4(vec3(luma), c.a);
                } else if (uFilterMode == 2) {
                    gl_FragColor = vec4(min(c.r * 1.08, 1.0), c.g * 1.02, c.b * 0.92, c.a);
                } else {
                    gl_FragColor = c;
                }
            }
        """
    }
}
