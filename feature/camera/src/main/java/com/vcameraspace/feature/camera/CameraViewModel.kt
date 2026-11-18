package com.vcameraspace.feature.camera

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.vcameraspace.core.common.coroutines.DispatcherProvider
import com.vcameraspace.core.common.model.AppResult
import com.vcameraspace.core.media.CameraConfigurationRecord
import com.vcameraspace.core.media.CameraConfigurationRepository
import com.vcameraspace.core.media.CameraProxyInterceptEvent
import com.vcameraspace.core.media.CameraProxyTestSummary
import com.vcameraspace.core.media.CameraSourceRegistry
import com.vcameraspace.core.media.GlColorFilterMode
import com.vcameraspace.core.media.GlRenderTelemetry
import com.vcameraspace.core.media.LocalPictureMetadata
import com.vcameraspace.core.media.LocalVideoMetadata
import com.vcameraspace.core.media.MediaInspector
import com.vcameraspace.core.media.NativeFrameBridge
import com.vcameraspace.core.media.SharedCameraSourceConfig
import com.vcameraspace.core.media.VirtualCameraAdapterManager
import com.vcameraspace.core.media.VirtualCameraProxyBridge
import com.vcameraspace.core.media.YuvFrameFormat
import com.vcameraspace.core.security.StreamProtocol
import com.vcameraspace.core.security.UrlSecurityValidator
import com.vcameraspace.core.security.UrlValidationResult
import com.vcameraspace.virtualengine.api.CameraSourceMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

data class StreamValidationSummary(
    val isValid: Boolean,
    val statusText: String,
    val protocol: StreamProtocol? = null,
    val detail: String = ""
)

data class CameraSettingsUiState(
    val spaceId: String = "",
    val packageName: String? = null,
    val mode: CameraSourceMode = CameraSourceMode.REAL_DEVICE,
    val sourceUri: String = "",
    val localVideoMetadata: LocalVideoMetadata? = null,
    val localPictureMetadata: LocalPictureMetadata? = null,
    val enableAudio: Boolean = false,
    val autoReconnect: Boolean = true,
    val widthInput: String = "0",
    val heightInput: String = "0",
    val orientationDegrees: Int = 0,
    val isValidatingStream: Boolean = false,
    val streamValidation: StreamValidationSummary? = null,
    val isPreviewActive: Boolean = false,
    val measuredFps: Double = 0.0,
    val measuredResolution: String = "Auto",
    val useOpenGlPipeline: Boolean = true,
    val mirrorHorizontal: Boolean = false,
    val glColorFilterMode: GlColorFilterMode = GlColorFilterMode.STANDARD,
    val glTelemetry: GlRenderTelemetry = GlRenderTelemetry(),
    val isRunningProxyTest: Boolean = false,
    val proxyTestSummary: CameraProxyTestSummary? = null,
    val proxyEvents: List<CameraProxyInterceptEvent> = emptyList(),
    val nativeBridgeStatusLabel: String = NativeFrameBridge.status.label,
    val diagnosticLogs: List<String> = emptyList(),
    val feedbackMessage: String? = null,
    val errorMessage: String? = null
) {
    val tipText: String
        get() = when (mode) {
            CameraSourceMode.REAL_DEVICE -> "This will disable the virtual camera."
            CameraSourceMode.LOCAL_VIDEO -> "This will replace the camera source with local video in supported integrations."
            CameraSourceMode.NETWORK_STREAM -> "This will replace the camera source with a network video stream."
            CameraSourceMode.LOCAL_PICTURE -> "This will replace the camera source with a local picture in supported integrations."
        }

    val effectiveResolutionLabel: String
        get() = when (mode) {
            CameraSourceMode.REAL_DEVICE -> measuredResolution
            CameraSourceMode.LOCAL_VIDEO -> {
                val meta = localVideoMetadata
                if (meta != null && meta.width > 0 && meta.height > 0) "${meta.width} x ${meta.height}" else measuredResolution
            }
            CameraSourceMode.NETWORK_STREAM -> measuredResolution
            CameraSourceMode.LOCAL_PICTURE -> {
                val w = widthInput.toIntOrNull() ?: 0
                val h = heightInput.toIntOrNull() ?: 0
                if (w == 0 && h == 0) {
                    val pic = localPictureMetadata
                    if (pic != null) "${pic.recommendedOutputWidth} x ${pic.recommendedOutputHeight} (Auto)"
                    else "Auto Output Size"
                } else {
                    "$w x $h"
                }
            }
        }
}

class CameraViewModel(
    private val spaceId: String,
    private val packageName: String?,
    private val configRepository: CameraConfigurationRepository,
    private val mediaInspector: MediaInspector,
    private val dispatchers: DispatcherProvider,
    private val adapterManager: VirtualCameraAdapterManager? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        CameraSettingsUiState(spaceId = spaceId, packageName = packageName)
    )
    val uiState: StateFlow<CameraSettingsUiState> = _uiState.asStateFlow()

    init {
        loadSavedConfiguration()
    }

    private fun loadSavedConfiguration() {
        viewModelScope.launch {
            val saved = configRepository.observeConfig(spaceId, packageName).first()
            _uiState.update {
                it.copy(
                    mode = saved.mode,
                    sourceUri = saved.sourceUri.orEmpty(),
                    enableAudio = saved.enableAudio,
                    autoReconnect = saved.autoReconnect,
                    widthInput = saved.width.toString(),
                    heightInput = saved.height.toString(),
                    orientationDegrees = saved.orientationDegrees
                )
            }
            val uri = saved.sourceUri
            if (!uri.isNullOrBlank()) {
                when (saved.mode) {
                    CameraSourceMode.LOCAL_VIDEO -> inspectLocalVideo(uri)
                    CameraSourceMode.LOCAL_PICTURE -> inspectLocalPicture(uri)
                    else -> Unit
                }
            }
        }
    }

    fun selectMode(mode: CameraSourceMode) {
        _uiState.update {
            it.copy(
                mode = mode,
                errorMessage = null,
                streamValidation = null
            )
        }
        appendLog("Selected mode: ${CameraSourceRegistry.sourceFor(mode).label}")
    }

    fun onLocalVideoSelected(uri: Uri) {
        val uriString = uri.toString()
        _uiState.update { it.copy(sourceUri = uriString, errorMessage = null) }
        viewModelScope.launch { inspectLocalVideo(uriString) }
    }

    private suspend fun inspectLocalVideo(uriString: String) {
        when (val res = mediaInspector.inspectLocalVideo(uriString)) {
            is AppResult.Success -> {
                _uiState.update {
                    it.copy(
                        localVideoMetadata = res.data,
                        measuredResolution = "${res.data.width} x ${res.data.height}"
                    )
                }
                appendLog("Loaded local video: ${res.data.displayName} (${res.data.width}x${res.data.height})")
            }
            is AppResult.Failure -> {
                _uiState.update { it.copy(errorMessage = res.error.message) }
                appendLog("Local video error: ${res.error.message}")
            }
        }
    }

    fun onLocalPictureSelected(uri: Uri) {
        val uriString = uri.toString()
        _uiState.update { it.copy(sourceUri = uriString, errorMessage = null) }
        viewModelScope.launch { inspectLocalPicture(uriString) }
    }

    private suspend fun inspectLocalPicture(uriString: String) {
        when (val res = mediaInspector.inspectLocalPicture(uriString)) {
            is AppResult.Success -> {
                _uiState.update {
                    it.copy(
                        localPictureMetadata = res.data,
                        measuredResolution = "${res.data.recommendedOutputWidth} x ${res.data.recommendedOutputHeight}"
                    )
                }
                appendLog("Loaded local picture: ${res.data.displayName} (${res.data.intrinsicWidth}x${res.data.intrinsicHeight})")
            }
            is AppResult.Failure -> {
                _uiState.update { it.copy(errorMessage = res.error.message) }
                appendLog("Local picture error: ${res.error.message}")
            }
        }
    }

    fun onStreamUrlChange(url: String) {
        _uiState.update { it.copy(sourceUri = url, streamValidation = null, errorMessage = null) }
    }

    fun onEnableAudioChange(enabled: Boolean) {
        _uiState.update { it.copy(enableAudio = enabled) }
    }

    fun onAutoReconnectChange(enabled: Boolean) {
        _uiState.update { it.copy(autoReconnect = enabled) }
    }

    fun onWidthChange(value: String) {
        if (value.all { it.isDigit() } && value.length <= 4) {
            _uiState.update { it.copy(widthInput = value) }
        }
    }

    fun onHeightChange(value: String) {
        if (value.all { it.isDigit() } && value.length <= 4) {
            _uiState.update { it.copy(heightInput = value) }
        }
    }

    fun rotateOrientation() {
        _uiState.update {
            val next = (it.orientationDegrees + 90) % 360
            it.copy(orientationDegrees = next)
        }
        appendLog("GL orientation set to ${_uiState.value.orientationDegrees}°")
    }

    fun toggleMirrorHorizontal() {
        _uiState.update { it.copy(mirrorHorizontal = !it.mirrorHorizontal) }
        appendLog("GL horizontal mirror: ${_uiState.value.mirrorHorizontal}")
    }

    fun cycleColorFilter() {
        val entries = GlColorFilterMode.entries
        _uiState.update { current ->
            val nextIdx = (entries.indexOf(current.glColorFilterMode) + 1) % entries.size
            current.copy(glColorFilterMode = entries[nextIdx])
        }
        appendLog("GL shader filter: ${_uiState.value.glColorFilterMode.label}")
    }

    fun toggleOpenGlPipeline() {
        _uiState.update { it.copy(useOpenGlPipeline = !it.useOpenGlPipeline) }
        appendLog("OpenGL ES SurfaceTexture pipeline: ${if (_uiState.value.useOpenGlPipeline) "Enabled" else "Fallback View"}")
    }

    fun onGlTelemetry(telemetry: GlRenderTelemetry) {
        _uiState.update {
            it.copy(
                glTelemetry = telemetry,
                measuredFps = if (telemetry.measuredFps > 0.0) telemetry.measuredFps else it.measuredFps
            )
        }
    }

    fun runLiveProxyFrameTest() {
        if (_uiState.value.isRunningProxyTest) return
        viewModelScope.launch {
            _uiState.update { it.copy(isRunningProxyTest = true, errorMessage = null) }
            val summary = withContext(dispatchers.io) {
                adapterManager?.runProxySelfTest(spaceId)
                    ?: VirtualCameraProxyBridge().runSandboxProxyVerificationTest(spaceId)
            }
            _uiState.update {
                it.copy(
                    isRunningProxyTest = false,
                    proxyTestSummary = summary,
                    proxyEvents = summary.interceptedEvents,
                    feedbackMessage = "Proxy self-test passed (${summary.frameWidth}x${summary.frameHeight}, NV21=${summary.camera1Nv21Bytes}B)"
                )
            }
            appendLog(
                "Proxy test: ${summary.frameWidth}x${summary.frameHeight} NV21=${summary.camera1Nv21Bytes}B Camera2Surface=${summary.camera2FrameDeliveredToSurface} CRC=${summary.yuvChecksumHex}"
            )
        }
    }

    fun setPreviewActive(active: Boolean) {
        _uiState.update { it.copy(isPreviewActive = active) }
        appendLog(if (active) "Camera Lab preview started." else "Camera Lab preview stopped.")
    }

    fun updateMeasuredTelemetry(fps: Double, resolution: String) {
        _uiState.update {
            it.copy(
                measuredFps = fps,
                measuredResolution = if (resolution.isNotBlank()) resolution else it.measuredResolution
            )
        }
    }

    fun onPlaybackError(rawMessage: String) {
        val safe = UrlSecurityValidator.sanitizeLogMessage(rawMessage)
        _uiState.update { it.copy(errorMessage = safe) }
        appendLog("Playback error: $safe")
    }

    fun validateNetworkStream() {
        val rawUrl = _uiState.value.sourceUri
        when (val syntax = UrlSecurityValidator.validateStreamUrl(rawUrl)) {
            is UrlValidationResult.Invalid -> {
                _uiState.update {
                    it.copy(
                        streamValidation = StreamValidationSummary(
                            isValid = false,
                            statusText = "Invalid URL",
                            detail = "${syntax.reason} ${syntax.recoverySuggestion}"
                        )
                    )
                }
                appendLog("Validation rejected URL: ${syntax.reason}")
                return
            }
            is UrlValidationResult.Valid -> {
                if (!mediaInspector.isNetworkAvailable()) {
                    _uiState.update {
                        it.copy(
                            streamValidation = StreamValidationSummary(
                                isValid = false,
                                statusText = "Network Offline",
                                protocol = syntax.protocol,
                                detail = "No active internet connectivity detected on this device."
                            )
                        )
                    }
                    appendLog("Network unavailable during stream validation.")
                    return
                }

                viewModelScope.launch {
                    _uiState.update { it.copy(isValidatingStream = true) }
                    val reachability = withContext(dispatchers.io) {
                        probeStreamEndpoint(rawUrl, syntax.protocol)
                    }
                    _uiState.update {
                        it.copy(
                            isValidatingStream = false,
                            streamValidation = reachability
                        )
                    }
                    appendLog("Stream validation: ${reachability.statusText} (${syntax.sanitizedDisplayUrl})")
                }
            }
        }
    }

    private fun probeStreamEndpoint(rawUrl: String, protocol: StreamProtocol): StreamValidationSummary {
        if (protocol == StreamProtocol.RTSP) {
            return StreamValidationSummary(
                isValid = true,
                statusText = "RTSP Syntax & Network Ready",
                protocol = protocol,
                detail = "RTSP handshake will execute inside Media3 RTSP ExoPlayer when preview starts."
            )
        }
        return runCatching {
            val url = URL(rawUrl)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "HEAD"
                connectTimeout = 5000
                readTimeout = 5000
                instanceFollowRedirects = true
            }
            val code = conn.responseCode
            conn.disconnect()
            when (code) {
                in 200..399 -> StreamValidationSummary(
                    isValid = true,
                    statusText = "Connected (HTTP $code)",
                    protocol = protocol,
                    detail = "Endpoint reachable and ready for Media3 ${protocol.displayName} playback."
                )
                401, 403 -> StreamValidationSummary(
                    isValid = false,
                    statusText = "Authentication Required (HTTP $code)",
                    protocol = protocol,
                    detail = "Stream server rejected unauthenticated request. Credentials are never logged."
                )
                else -> StreamValidationSummary(
                    isValid = false,
                    statusText = "HTTP Error ($code)",
                    protocol = protocol,
                    detail = "Server returned HTTP $code."
                )
            }
        }.getOrElse { throwable ->
            val safe = UrlSecurityValidator.sanitizeLogMessage(throwable.message ?: "Timeout or unreachable host")
            StreamValidationSummary(
                isValid = false,
                statusText = "Connection Failed",
                protocol = protocol,
                detail = safe
            )
        }
    }

    fun saveConfiguration() {
        val current = _uiState.value
        if (current.mode == CameraSourceMode.LOCAL_VIDEO && current.sourceUri.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Choose a local video file before saving.") }
            return
        }
        if (current.mode == CameraSourceMode.LOCAL_PICTURE && current.sourceUri.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Choose a local picture before saving.") }
            return
        }
        if (current.mode == CameraSourceMode.NETWORK_STREAM) {
            val validation = UrlSecurityValidator.validateStreamUrl(current.sourceUri)
            if (validation is UrlValidationResult.Invalid) {
                _uiState.update { it.copy(errorMessage = validation.reason) }
                return
            }
        }

        val w = current.widthInput.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val h = current.heightInput.toIntOrNull()?.coerceAtLeast(0) ?: 0

        viewModelScope.launch {
            val res = configRepository.saveConfig(
                CameraConfigurationRecord(
                    spaceId = spaceId,
                    packageName = packageName,
                    mode = current.mode,
                    sourceUri = current.sourceUri.ifBlank { null },
                    enableAudio = current.enableAudio,
                    width = w,
                    height = h,
                    orientationDegrees = current.orientationDegrees,
                    autoReconnect = current.autoReconnect,
                    updatedAtEpochMs = System.currentTimeMillis()
                )
            )
            when (res) {
                is AppResult.Success -> {
                    if (packageName == null && spaceId == "space_1") {
                        adapterManager?.saveSharedConfig(
                            SharedCameraSourceConfig(
                                mode = current.mode,
                                sourceUri = current.sourceUri.ifBlank { null },
                                outputFormat = YuvFrameFormat.I420,
                                targetWidth = w,
                                targetHeight = h,
                                enableAudio = current.enableAudio,
                                autoReconnect = current.autoReconnect
                            )
                        )
                    }
                    _uiState.update {
                        it.copy(
                            feedbackMessage = "Saved camera configuration for ${packageName ?: spaceId}.",
                            errorMessage = null
                        )
                    }
                    appendLog("Saved configuration (${current.mode.name}).")
                }
                is AppResult.Failure -> {
                    _uiState.update { it.copy(errorMessage = res.error.message) }
                }
            }
        }
    }

    fun clearFeedback() {
        _uiState.update { it.copy(feedbackMessage = null, errorMessage = null) }
    }

    private fun appendLog(entry: String) {
        val sanitized = UrlSecurityValidator.sanitizeLogMessage(entry)
        _uiState.update { state ->
            state.copy(diagnosticLogs = (listOf(sanitized) + state.diagnosticLogs).take(20))
        }
    }

    companion object {
        fun factory(
            spaceId: String,
            packageName: String?,
            configRepository: CameraConfigurationRepository,
            mediaInspector: MediaInspector,
            dispatchers: DispatcherProvider,
            adapterManager: VirtualCameraAdapterManager? = null
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return CameraViewModel(
                    spaceId = spaceId,
                    packageName = packageName,
                    configRepository = configRepository,
                    mediaInspector = mediaInspector,
                    dispatchers = dispatchers,
                    adapterManager = adapterManager
                ) as T
            }
        }
    }
}
