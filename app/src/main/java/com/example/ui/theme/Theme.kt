package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import com.vcameraspace.core.designsystem.theme.VCameraSpaceTheme

@Composable
fun VCameraAppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    VCameraSpaceTheme(
        darkTheme = darkTheme,
        typography = VCameraTypography,
        monoFontFamily = JetBrainsMonoFamily,
        content = content
    )
}
