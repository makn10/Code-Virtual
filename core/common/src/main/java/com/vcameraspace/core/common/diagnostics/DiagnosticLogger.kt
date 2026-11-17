package com.vcameraspace.core.common.diagnostics

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

enum class DiagnosticSeverity {
    INFO,
    WARNING,
    ERROR,
    CRASH
}

enum class DiagnosticCategory {
    ENGINE,
    APK_IMPORT,
    VIRTUAL_APPS,
    COMPATIBILITY,
    MEDIA_PIPELINE,
    CAMERA_ADAPTER,
    PERMISSIONS,
    CRASH
}

data class DiagnosticLogEntry(
    val id: String = UUID.randomUUID().toString(),
    val timestampEpochMs: Long = System.currentTimeMillis(),
    val severity: DiagnosticSeverity,
    val category: DiagnosticCategory,
    val message: String,
    val detail: String? = null
) {
    fun formattedTimestamp(): String {
        val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        return fmt.format(Date(timestampEpochMs))
    }

    fun toExportLine(): String = buildString {
        append("[${formattedTimestamp()}] [${severity.name}] [${category.name}] $message")
        if (!detail.isNullOrBlank()) {
            append(" — $detail")
        }
    }
}

class DiagnosticLogger(
    private val maxEntries: Int = 250
) {
    private val _logs = MutableStateFlow<List<DiagnosticLogEntry>>(emptyList())
    val logs: StateFlow<List<DiagnosticLogEntry>> = _logs.asStateFlow()

    private val _lastPersistedCrashReport = MutableStateFlow<String?>(null)
    val lastPersistedCrashReport: StateFlow<String?> = _lastPersistedCrashReport.asStateFlow()

    private var crashFile: File? = null
    @Volatile
    private var crashReporterInstalled = false

    fun installCrashReporter(filesDir: File) {
        val target = File(filesDir, "vcamera_last_crash.txt")
        crashFile = target
        if (target.exists()) {
            val previous = runCatching { target.readText() }.getOrNull()?.trim()
            if (!previous.isNullOrBlank()) {
                _lastPersistedCrashReport.value = previous
                log(
                    severity = DiagnosticSeverity.CRASH,
                    category = DiagnosticCategory.CRASH,
                    message = "Loaded persisted crash report from previous session.",
                    detail = previous.lineSequence().firstOrNull()
                )
            }
        }
        if (crashReporterInstalled) return
        crashReporterInstalled = true
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val sanitized = sanitize("${throwable::class.java.simpleName}: ${throwable.message.orEmpty()}")
            val stackPreview = throwable.stackTraceToString().lineSequence().take(12).joinToString("\n")
            val report = buildString {
                appendLine("Timestamp: ${Date()}")
                appendLine("Thread: ${thread.name}")
                appendLine("Error: $sanitized")
                appendLine(sanitize(stackPreview))
            }
            runCatching { target.writeText(report) }
            logCrash("Uncaught:${thread.name}", throwable)
            previousHandler?.uncaughtException(thread, throwable)
        }
    }

    fun clearPersistedCrashReport() {
        runCatching { crashFile?.delete() }
        _lastPersistedCrashReport.value = null
    }

    fun logInfo(category: DiagnosticCategory, message: String, detail: String? = null) =
        log(DiagnosticSeverity.INFO, category, message, detail)

    fun logWarning(category: DiagnosticCategory, message: String, detail: String? = null) =
        log(DiagnosticSeverity.WARNING, category, message, detail)

    fun logError(category: DiagnosticCategory, message: String, detail: String? = null) =
        log(DiagnosticSeverity.ERROR, category, message, detail)

    fun logCrash(source: String, throwable: Throwable) {
        val summary = "${throwable::class.java.simpleName}: ${throwable.message ?: "Unexpected failure"}"
        val traceLine = throwable.stackTraceToString().lineSequence().take(4).joinToString(" | ")
        log(
            severity = DiagnosticSeverity.CRASH,
            category = DiagnosticCategory.CRASH,
            message = "[$source] $summary",
            detail = traceLine
        )
    }

    fun log(
        severity: DiagnosticSeverity,
        category: DiagnosticCategory,
        message: String,
        detail: String? = null
    ) {
        val entry = DiagnosticLogEntry(
            severity = severity,
            category = category,
            message = sanitize(message),
            detail = detail?.let(::sanitize)
        )
        _logs.update { current ->
            (listOf(entry) + current).take(maxEntries)
        }
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }

    fun exportText(): String {
        val crash = _lastPersistedCrashReport.value
        val logLines = _logs.value.joinToString("\n") { it.toExportLine() }
        return buildString {
            appendLine("=== VCamera Space Diagnostic Report ===")
            if (!crash.isNullOrBlank()) {
                appendLine("--- Last Uncaught Crash ---")
                appendLine(crash)
            }
            appendLine("--- Event Log (${_logs.value.size} entries) ---")
            appendLine(logLines.ifBlank { "(empty)" })
        }
    }

    companion object {
        private val credentialRegex = Regex("(https?|rtsp|rtmp)://[^/@\\s]+:[^/@\\s]+@")
        private val bearerRegex = Regex("(?i)(bearer\\s+|token=|apikey=|password=)[^\\s,&]+")

        fun sanitize(raw: String): String {
            return raw
                .replace(credentialRegex) { "${it.groupValues[1]}://***:***@" }
                .replace(bearerRegex) { "${it.groupValues[1]}***" }
        }
    }
}
