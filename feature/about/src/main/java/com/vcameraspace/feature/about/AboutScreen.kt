package com.vcameraspace.feature.about

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.vcameraspace.core.designsystem.components.CustomToolbar
import com.vcameraspace.core.designsystem.components.PrimaryButton
import com.vcameraspace.core.designsystem.components.StatusCard
import com.vcameraspace.core.designsystem.components.StatusSeverity
import com.vcameraspace.core.designsystem.components.VCameraLogo

@Composable
fun AboutScreen(
    versionName: String,
    onBackClick: () -> Unit,
    onShareClick: () -> Unit,
    onOpenSettingsClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("about_screen"),
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            CustomToolbar(
                title = "About",
                onBackClick = onBackClick
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .widthIn(max = 680.dp),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            item {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    VCameraLogo(size = 80.dp)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "VCamera Space – Live Assist",
                        style = MaterialTheme.typography.headlineMedium
                    )
                    Text(
                        text = "Version $versionName • Your Space. Your Control.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            item {
                StatusCard(
                    title = "Central Architecture (Live Assist)",
                    message = "1. VCamera Space – Main Application\n2. Virtual Environment / User-Space Sandbox\n3. Virtual App Management & Camera1/2 Proxy Layer\n4. Virtual Media Processing Engine (YUV420 / NV21)\n5. MediaCodec + SurfaceTexture + OpenGL ES 2.0\n6. Virtual Preview & Supported Media Output",
                    severity = StatusSeverity.INFO,
                    icon = Icons.Default.Info
                )
            }

            item {
                StatusCard(
                    title = "Android Platform Constraints",
                    message = "• Android does not provide a universal public API for replacing the physical camera feed inside arbitrary third-party apps.\n• Compatibility with WhatsApp, Messenger, Instagram, Zoom, and other external apps cannot be guaranteed through app cloning alone.\n• Video audio playback does not inject into unrelated app microphones, and mock locations require Android Developer Options approval.",
                    severity = StatusSeverity.WARNING,
                    icon = Icons.Default.Shield
                )
            }

            item {
                PrimaryButton(
                    text = "Share VCamera Space",
                    icon = Icons.Default.Share,
                    onClick = onShareClick,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            item {
                OutlinedButton(
                    onClick = onOpenSettingsClick,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Open General Settings")
                }
            }
        }
    }
}
