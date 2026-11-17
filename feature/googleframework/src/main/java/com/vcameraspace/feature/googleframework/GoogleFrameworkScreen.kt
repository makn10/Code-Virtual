package com.vcameraspace.feature.googleframework

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.vcameraspace.core.designsystem.components.CustomToolbar
import com.vcameraspace.core.designsystem.components.SectionHeader
import com.vcameraspace.core.designsystem.components.StatusCard
import com.vcameraspace.core.designsystem.components.StatusSeverity

data class GmsEnvironmentReport(
    val gmsInstalled: Boolean,
    val gmsVersion: String?,
    val playStoreInstalled: Boolean,
    val playStoreVersion: String?
)

@Composable
fun GoogleFrameworkScreen(
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val report = remember(context) { inspectGmsEnvironment(context) }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("google_framework_screen"),
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            CustomToolbar(
                title = "Google Framework",
                subtitle = "GMS Compatibility Diagnostics",
                onBackClick = onBackClick
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .widthIn(max = 680.dp),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                StatusCard(
                    title = if (report.gmsInstalled) "Google Play Services Detected" else "GMS Not Detected on Device",
                    message = if (report.gmsInstalled) {
                        "com.google.android.gms (${report.gmsVersion ?: "Unknown version"}) is available from the host Android system."
                    } else {
                        "com.google.android.gms was not found. Apps requiring GMS may show unavailable service dialogs."
                    },
                    severity = if (report.gmsInstalled) StatusSeverity.SUCCESS else StatusSeverity.WARNING,
                    icon = if (report.gmsInstalled) Icons.Default.CheckCircle else Icons.Default.WarningAmber
                )
            }

            item {
                StatusCard(
                    title = "Google Play Store Visibility",
                    message = if (report.playStoreInstalled) {
                        "com.android.vending (${report.playStoreVersion ?: "Installed"}) is present."
                    } else {
                        "com.android.vending was not detected."
                    },
                    severity = if (report.playStoreInstalled) StatusSeverity.INFO else StatusSeverity.WARNING,
                    icon = Icons.Default.Shield
                )
            }

            item {
                SectionHeader(
                    title = "Compatibility & Policy Documentation",
                    subtitle = "Supported integration boundaries"
                )
            }

            item {
                StatusCard(
                    title = "No Unauthorized GMS Redistribution",
                    message = "VCamera Space does not bundle, install, or modify proprietary Google Mobile Services packages. Workspace launches rely exclusively on the host device's authorized GMS and Android runtime.",
                    severity = StatusSeverity.INFO,
                    icon = Icons.Default.Info
                )
            }
        }
    }
}

private fun inspectGmsEnvironment(context: Context): GmsEnvironmentReport {
    val pm = context.packageManager
    val gmsVersion = queryVersion(pm, "com.google.android.gms")
    val vendingVersion = queryVersion(pm, "com.android.vending")
    return GmsEnvironmentReport(
        gmsInstalled = gmsVersion != null,
        gmsVersion = gmsVersion,
        playStoreInstalled = vendingVersion != null,
        playStoreVersion = vendingVersion
    )
}

private fun queryVersion(pm: PackageManager, pkg: String): String? {
    return runCatching {
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(pkg, 0)
        }
        info.versionName ?: "Installed"
    }.getOrNull()
}
