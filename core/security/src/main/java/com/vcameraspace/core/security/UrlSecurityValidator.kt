package com.vcameraspace.core.security

import java.net.URI
import java.util.Locale

enum class StreamProtocol(val displayName: String) {
    MP4("MP4 / Progressive"),
    HLS("HLS (.m3u8)"),
    DASH("MPEG-DASH (.mpd)"),
    SMOOTH_STREAMING("Smooth Streaming"),
    RTSP("RTSP Stream"),
    HTTP_GENERIC("HTTP/HTTPS Stream")
}

sealed interface UrlValidationResult {
    data class Valid(
        val sanitizedDisplayUrl: String,
        val protocol: StreamProtocol,
        val hasEmbeddedCredentials: Boolean
    ) : UrlValidationResult

    data class Invalid(
        val reason: String,
        val recoverySuggestion: String
    ) : UrlValidationResult
}

object UrlSecurityValidator {
    private val allowedSchemes = setOf("http", "https", "rtsp", "rtmp")

    fun validateStreamUrl(rawUrl: String): UrlValidationResult {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) {
            return UrlValidationResult.Invalid(
                reason = "Stream URL cannot be empty.",
                recoverySuggestion = "Enter an http(s):// or rtsp:// video stream URL."
            )
        }
        if (trimmed.any { it.isWhitespace() }) {
            return UrlValidationResult.Invalid(
                reason = "Stream URL contains whitespace.",
                recoverySuggestion = "Remove spaces or line breaks from the URL."
            )
        }

        val parsed = runCatching { URI(trimmed) }.getOrNull()
            ?: return UrlValidationResult.Invalid(
                reason = "Malformed stream URL syntax.",
                recoverySuggestion = "Verify the URL scheme and host name."
            )

        val scheme = parsed.scheme?.lowercase(Locale.US)
        if (scheme == null || scheme !in allowedSchemes) {
            return UrlValidationResult.Invalid(
                reason = "Unsupported protocol '${scheme ?: "none"}'.",
                recoverySuggestion = "Use http://, https://, or rtsp://."
            )
        }

        val host = parsed.host
        if (host.isNullOrBlank()) {
            return UrlValidationResult.Invalid(
                reason = "Missing stream host address.",
                recoverySuggestion = "Provide a valid domain or IP address."
            )
        }

        val hasCredentials = !parsed.rawUserInfo.isNullOrBlank()
        val protocol = detectProtocol(scheme, parsed.path.orEmpty(), trimmed)
        return UrlValidationResult.Valid(
            sanitizedDisplayUrl = redactCredentials(trimmed),
            protocol = protocol,
            hasEmbeddedCredentials = hasCredentials
        )
    }

    fun redactCredentials(rawUrl: String): String {
        val parsed = runCatching { URI(rawUrl) }.getOrNull() ?: return rawUrl
        val userInfo = parsed.rawUserInfo
        return if (userInfo.isNullOrEmpty()) {
            rawUrl
        } else {
            rawUrl.replaceFirst("$userInfo@", "***:***@")
        }
    }

    fun sanitizeLogMessage(message: String): String {
        val urlCredentialRegex = Regex("(https?|rtsp|rtmp)://[^/@\\s]+:[^/@\\s]+@")
        return message.replace(urlCredentialRegex) { match ->
            "${match.groupValues[1]}://***:***@"
        }
    }

    private fun detectProtocol(scheme: String, path: String, fullUrl: String): StreamProtocol {
        val lowerPath = path.lowercase(Locale.US)
        val lowerUrl = fullUrl.lowercase(Locale.US)
        return when {
            scheme == "rtsp" -> StreamProtocol.RTSP
            lowerPath.endsWith(".m3u8") || lowerUrl.contains(".m3u8") -> StreamProtocol.HLS
            lowerPath.endsWith(".mpd") || lowerUrl.contains(".mpd") -> StreamProtocol.DASH
            lowerPath.contains(".ism") || lowerPath.endsWith("/manifest") -> StreamProtocol.SMOOTH_STREAMING
            lowerPath.endsWith(".mp4") || lowerPath.endsWith(".m4v") -> StreamProtocol.MP4
            else -> StreamProtocol.HTTP_GENERIC
        }
    }
}
