package com.vcameraspace.virtualengine.impl

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import com.vcameraspace.core.common.coroutines.DispatcherProvider
import com.vcameraspace.core.common.diagnostics.DiagnosticCategory
import com.vcameraspace.core.common.diagnostics.DiagnosticLogger
import com.vcameraspace.core.common.model.AppError
import com.vcameraspace.core.common.model.AppResult
import com.vcameraspace.core.database.dao.ImportedApkDao
import com.vcameraspace.core.database.entity.ImportedApkEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipFile

data class ImportedApkRecord(
    val id: String,
    val displayName: String,
    val packageName: String,
    val appLabel: String,
    val versionName: String,
    val versionCode: Long,
    val minSdk: Int,
    val targetSdk: Int,
    val signingSha256: String,
    val abis: List<String>,
    val apkFilePath: String,
    val iconFilePath: String?,
    val fileSizeBytes: Long,
    val isValidArchive: Boolean,
    val isCompatible: Boolean,
    val compatibilitySummary: String,
    val importedAtEpochMs: Long
) {
    fun fileExists(): Boolean = File(apkFilePath).exists()
}

class ApkImportManager(
    private val context: Context,
    private val importedApkDao: ImportedApkDao,
    private val compatibilityChecker: AndroidCompatibilityChecker,
    private val dispatchers: DispatcherProvider,
    private val logger: DiagnosticLogger? = null
) {
    private val apkStorageDir: File
        get() = File(context.filesDir, "imported_apks").apply { mkdirs() }

    private val iconStorageDir: File
        get() = File(context.filesDir, "imported_icons").apply { mkdirs() }

    fun observeImportedApks(): Flow<List<ImportedApkRecord>> =
        importedApkDao.observeAllImportedApks().map { entities ->
            entities.map { it.toDomain() }
        }

    /**
     * Imports an .apk via Storage Access Framework into private application storage,
     * parses manifest metadata, extracts the application icon, validates archive/ABI/SDK
     * compatibility, and stores the catalog entry separately from Virtual Space installation.
     */
    suspend fun importApkFromUri(uri: Uri): AppResult<ImportedApkRecord> = withContext(dispatchers.io) {
        val importId = UUID.randomUUID().toString().take(12)
        var targetApkFile: File? = null
        var targetIconFile: File? = null

        runCatching {
            val resolver = context.contentResolver
            val displayName = querySafDisplayName(uri) ?: "imported_$importId.apk"
            val stagingFile = File(apkStorageDir, "staging_$importId.apk")

            resolver.openInputStream(uri)?.use { input ->
                stagingFile.outputStream().use { output -> input.copyTo(output) }
            } ?: error("Cannot read selected APK stream from Storage Access Framework.")

            if (stagingFile.length() < 22L) {
                stagingFile.delete()
                error("Selected APK file is empty or truncated.")
            }

            // Validate ZIP/APK magic bytes ("PK")
            val magic = ByteArray(2)
            stagingFile.inputStream().use { it.read(magic) }
            if (magic[0] != 0x50.toByte() || magic[1] != 0x4B.toByte()) {
                stagingFile.delete()
                error("File is not a valid ZIP-based Android APK archive.")
            }

            // Verify central ZIP entries and AndroidManifest.xml presence
            val abis = linkedSetOf<String>()
            var hasManifest = false
            ZipFile(stagingFile).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val name = entry.name
                    if (name == "AndroidManifest.xml") hasManifest = true
                    if (name.startsWith("lib/") && name.count { it == '/' } >= 2) {
                        abis += name.substringAfter("lib/").substringBefore('/')
                    }
                }
            }
            if (!hasManifest) {
                stagingFile.delete()
                error("Archive does not contain a compiled AndroidManifest.xml.")
            }

            val pm = context.packageManager
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                PackageManager.GET_SIGNING_CERTIFICATES
            } else {
                @Suppress("DEPRECATION")
                PackageManager.GET_SIGNATURES
            }
            val pkgInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageArchiveInfo(stagingFile.absolutePath, PackageManager.PackageInfoFlags.of(flags.toLong()))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageArchiveInfo(stagingFile.absolutePath, flags)
            } ?: run {
                stagingFile.delete()
                error("PackageManager failed to parse APK manifest or package signature.")
            }

            val pkgName = pkgInfo.packageName ?: error("Missing package name in APK manifest.")
            val appInfo = pkgInfo.applicationInfo?.apply {
                sourceDir = stagingFile.absolutePath
                publicSourceDir = stagingFile.absolutePath
            }
            val label = runCatching { appInfo?.loadLabel(pm)?.toString() }.getOrNull()
                ?.takeIf { it.isNotBlank() } ?: pkgName
            val versionName = pkgInfo.versionName ?: "1.0"
            val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pkgInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                pkgInfo.versionCode.toLong()
            }
            val minSdk = appInfo?.minSdkVersion ?: 21
            val targetSdk = appInfo?.targetSdkVersion ?: Build.VERSION.SDK_INT
            val signingDigest = extractSignatureSha256(pkgInfo)

            val safeFileBase = "${pkgName.replace(Regex("[^A-Za-z0-9._-]"), "_")}_v${versionCode}_$importId"
            targetApkFile = File(apkStorageDir, "$safeFileBase.apk")
            if (!stagingFile.renameTo(targetApkFile!!)) {
                stagingFile.copyTo(targetApkFile!!, overwrite = true)
                stagingFile.delete()
            }
            appInfo?.sourceDir = targetApkFile!!.absolutePath
            appInfo?.publicSourceDir = targetApkFile!!.absolutePath

            // Extract and persist app icon PNG cleanly
            val iconDrawable = runCatching { appInfo?.loadIcon(pm) }.getOrNull()
            if (iconDrawable != null) {
                targetIconFile = File(iconStorageDir, "$safeFileBase.png")
                saveDrawableAsPng(iconDrawable, targetIconFile!!)
            }

            // Compatibility check against runtime OS + device ABIs
            val blockers = mutableListOf<String>()
            val warnings = mutableListOf<String>()
            if (minSdk > Build.VERSION.SDK_INT) {
                blockers += "Requires API $minSdk (device is API ${Build.VERSION.SDK_INT})"
            }
            val abiList = abis.toList()
            if (abiList.isNotEmpty() && abiList.none { it in Build.SUPPORTED_ABIS }) {
                blockers += "ABI mismatch (${abiList.joinToString()} vs ${Build.SUPPORTED_ABIS.joinToString()})"
            }
            if (targetApkFile!!.length() * 2 > compatibilityChecker.availableStorageBytes()) {
                blockers += "Low available device storage"
            }
            if (targetSdk < 28) {
                warnings += "Legacy targetSdk $targetSdk may trigger Android compatibility warnings"
            }

            val compatible = blockers.isEmpty()
            val summary = when {
                blockers.isNotEmpty() -> "Incompatible: ${blockers.joinToString("; ")}"
                warnings.isNotEmpty() -> "Compatible with notes: ${warnings.joinToString("; ")}"
                else -> "Valid APK archive & runtime checks passed"
            }

            val entity = ImportedApkEntity(
                id = importId,
                displayName = displayName,
                packageName = pkgName,
                appLabel = label,
                versionName = versionName,
                versionCode = versionCode,
                minSdk = minSdk,
                targetSdk = targetSdk,
                signingSha256 = signingDigest,
                abisCsv = abiList.joinToString(","),
                apkFilePath = targetApkFile!!.absolutePath,
                iconFilePath = targetIconFile?.takeIf { it.exists() }?.absolutePath,
                fileSizeBytes = targetApkFile!!.length(),
                isValidArchive = true,
                isCompatible = compatible,
                compatibilitySummary = summary,
                importedAtEpochMs = System.currentTimeMillis()
            )
            importedApkDao.upsertImportedApk(entity)
            logger?.logInfo(
                DiagnosticCategory.APK_IMPORT,
                "Imported APK: $label ($pkgName v$versionName)",
                summary
            )
            entity.toDomain()
        }.fold(
            onSuccess = { AppResult.Success(it) },
            onFailure = { err ->
                runCatching { targetApkFile?.delete() }
                runCatching { targetIconFile?.delete() }
                val msg = err.message ?: "Unable to import selected APK."
                logger?.logError(DiagnosticCategory.APK_IMPORT, "APK import failed", msg)
                AppResult.Failure(AppError.UnsupportedApk(msg))
            }
        )
    }

    suspend fun revalidateImportedApk(id: String): AppResult<ImportedApkRecord> = withContext(dispatchers.io) {
        val existing = importedApkDao.getImportedApkById(id)
            ?: return@withContext AppResult.Failure(AppError.InvalidPackage("Imported APK entry not found."))

        val file = File(existing.apkFilePath)
        if (!file.exists() || file.length() < 22L) {
            val updated = existing.copy(
                isValidArchive = false,
                isCompatible = false,
                compatibilitySummary = "Local APK copy missing from private storage."
            )
            importedApkDao.upsertImportedApk(updated)
            logger?.logWarning(DiagnosticCategory.APK_IMPORT, "Missing private APK copy for ${existing.packageName}")
            return@withContext AppResult.Success(updated.toDomain())
        }

        val abis = existing.abisCsv.split(',').filter { it.isNotBlank() }
        val blockers = mutableListOf<String>()
        if (existing.minSdk > Build.VERSION.SDK_INT) {
            blockers += "Requires API ${existing.minSdk} (device is API ${Build.VERSION.SDK_INT})"
        }
        if (abis.isNotEmpty() && abis.none { it in Build.SUPPORTED_ABIS }) {
            blockers += "ABI mismatch (${abis.joinToString()})"
        }
        val isCompat = blockers.isEmpty()
        val summary = if (isCompat) {
            "Runtime check passed (${Build.SUPPORTED_ABIS.firstOrNull() ?: "universal"}, API ${Build.VERSION.SDK_INT})"
        } else {
            "Incompatible: ${blockers.joinToString("; ")}"
        }
        val updated = existing.copy(
            isValidArchive = true,
            isCompatible = isCompat,
            compatibilitySummary = summary
        )
        importedApkDao.upsertImportedApk(updated)
        logger?.logInfo(DiagnosticCategory.APK_IMPORT, "Revalidated ${existing.packageName}", summary)
        AppResult.Success(updated.toDomain())
    }

    suspend fun removeImportedApk(id: String): AppResult<Unit> = withContext(dispatchers.io) {
        runCatching {
            val existing = importedApkDao.getImportedApkById(id)
            if (existing != null) {
                runCatching { File(existing.apkFilePath).delete() }
                existing.iconFilePath?.let { path -> runCatching { File(path).delete() } }
                importedApkDao.deleteImportedApk(id)
                logger?.logInfo(
                    DiagnosticCategory.APK_IMPORT,
                    "Removed imported APK: ${existing.appLabel} (${existing.packageName})"
                )
            }
        }.fold(
            onSuccess = { AppResult.Success(Unit) },
            onFailure = {
                logger?.logError(DiagnosticCategory.APK_IMPORT, "Failed to delete imported APK", it.message)
                AppResult.Failure(AppError.DatabaseFailure(it.message ?: "Failed to remove imported APK."))
            }
        )
    }

    private fun querySafDisplayName(uri: Uri): String? {
        return runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) cursor.getString(idx) else null
                } else null
            }
        }.getOrNull()
    }

    private fun extractSignatureSha256(pkgInfo: PackageInfo): String {
        val bytes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pkgInfo.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()
        } else {
            @Suppress("DEPRECATION")
            pkgInfo.signatures?.firstOrNull()?.toByteArray()
        } ?: return "Unsigned / Not Present"
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.take(12).joinToString(":") { "%02X".format(it) }
    }

    private fun saveDrawableAsPng(drawable: Drawable, outFile: File) {
        val bmp = if (drawable is BitmapDrawable && drawable.bitmap != null) {
            drawable.bitmap
        } else {
            val w = drawable.intrinsicWidth.takeIf { it > 0 } ?: 128
            val h = drawable.intrinsicHeight.takeIf { it > 0 } ?: 128
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { canvasBmp ->
                val canvas = Canvas(canvasBmp)
                drawable.setBounds(0, 0, canvas.width, canvas.height)
                drawable.draw(canvas)
            }
        }
        outFile.outputStream().use { out ->
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
    }

    private fun ImportedApkEntity.toDomain(): ImportedApkRecord = ImportedApkRecord(
        id = id,
        displayName = displayName,
        packageName = packageName,
        appLabel = appLabel,
        versionName = versionName,
        versionCode = versionCode,
        minSdk = minSdk,
        targetSdk = targetSdk,
        signingSha256 = signingSha256,
        abis = abisCsv.split(',').filter { it.isNotBlank() },
        apkFilePath = apkFilePath,
        iconFilePath = iconFilePath,
        fileSizeBytes = fileSizeBytes,
        isValidArchive = isValidArchive,
        isCompatible = isCompatible,
        compatibilitySummary = compatibilitySummary,
        importedAtEpochMs = importedAtEpochMs
    )
}
