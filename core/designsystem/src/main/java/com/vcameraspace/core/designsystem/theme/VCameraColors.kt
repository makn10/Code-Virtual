package com.vcameraspace.core.designsystem.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

object VCameraPalette {
    val PrimaryTeal = Color(0xFF009688)
    val DarkTeal = Color(0xFF00796B)
    val LightTeal = Color(0xFFE0F2F1)
    val BackgroundWhite = Color(0xFFFFFFFF)
    val SecondaryBackground = Color(0xFFF5F7F7)
    val PrimaryText = Color(0xFF212121)
    val SecondaryText = Color(0xFF757575)
    val SaveRed = Color(0xFFCC0000)
    val CloneOrange = Color(0xFFFF9800)
    val SuccessGreen = Color(0xFF2E7D32)
    val ErrorRed = Color(0xFFD32F2F)

    // Dark theme harmonious tokens
    val DarkPrimaryTeal = Color(0xFF4DB6AC)
    val DarkOnPrimary = Color(0xFF003731)
    val DarkPrimaryContainer = Color(0xFF005048)
    val DarkOnPrimaryContainer = Color(0xFFB2DFDB)
    val DarkBackground = Color(0xFF0E1414)
    val DarkSurface = Color(0xFF141C1C)
    val DarkSurfaceVariant = Color(0xFF1E2929)
    val DarkOnSurface = Color(0xFFE4ECEB)
    val DarkOnSurfaceVariant = Color(0xFF9FB0AE)
    val DarkOutline = Color(0xFF394947)
    val LightOutline = Color(0xFFD5E0DF)
}

@Immutable
data class VCameraExtendedColors(
    val saveAction: Color,
    val onSaveAction: Color,
    val cloneBadge: Color,
    val onCloneBadge: Color,
    val success: Color,
    val successContainer: Color,
    val warning: Color,
    val warningContainer: Color,
    val darkBrand: Color,
    val lightBrandTint: Color,
    val secondarySurface: Color,
    val monoCodeBackground: Color
)

val LightExtendedColors = VCameraExtendedColors(
    saveAction = VCameraPalette.SaveRed,
    onSaveAction = Color.White,
    cloneBadge = VCameraPalette.CloneOrange,
    onCloneBadge = Color.White,
    success = VCameraPalette.SuccessGreen,
    successContainer = Color(0xFFE8F5E9),
    warning = VCameraPalette.CloneOrange,
    warningContainer = Color(0xFFFFF3E0),
    darkBrand = VCameraPalette.DarkTeal,
    lightBrandTint = VCameraPalette.LightTeal,
    secondarySurface = VCameraPalette.SecondaryBackground,
    monoCodeBackground = Color(0xFFEDF2F2)
)

val DarkExtendedColors = VCameraExtendedColors(
    saveAction = Color(0xFFE53935),
    onSaveAction = Color.White,
    cloneBadge = Color(0xFFFFB74D),
    onCloneBadge = Color(0xFF331B00),
    success = Color(0xFF66BB6A),
    successContainer = Color(0xFF1B3A1E),
    warning = Color(0xFFFFB74D),
    warningContainer = Color(0xFF3B280D),
    darkBrand = VCameraPalette.DarkTeal,
    lightBrandTint = Color(0xFF143532),
    secondarySurface = VCameraPalette.DarkSurfaceVariant,
    monoCodeBackground = Color(0xFF192424)
)

val LocalVCameraExtendedColors = staticCompositionLocalOf { LightExtendedColors }

val VCameraLightColorScheme = lightColorScheme(
    primary = VCameraPalette.PrimaryTeal,
    onPrimary = Color.White,
    primaryContainer = VCameraPalette.LightTeal,
    onPrimaryContainer = VCameraPalette.DarkTeal,
    secondary = VCameraPalette.DarkTeal,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCDE9E6),
    onSecondaryContainer = Color(0xFF003B34),
    tertiary = VCameraPalette.CloneOrange,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFE0B2),
    onTertiaryContainer = Color(0xFF4A2500),
    background = VCameraPalette.BackgroundWhite,
    onBackground = VCameraPalette.PrimaryText,
    surface = VCameraPalette.BackgroundWhite,
    onSurface = VCameraPalette.PrimaryText,
    surfaceVariant = VCameraPalette.SecondaryBackground,
    onSurfaceVariant = VCameraPalette.SecondaryText,
    outline = VCameraPalette.LightOutline,
    outlineVariant = Color(0xFFE5ECEB),
    error = VCameraPalette.ErrorRed,
    onError = Color.White,
    errorContainer = Color(0xFFFFEBEE),
    onErrorContainer = Color(0xFF8C1D18)
)

val VCameraDarkColorScheme = darkColorScheme(
    primary = VCameraPalette.DarkPrimaryTeal,
    onPrimary = VCameraPalette.DarkOnPrimary,
    primaryContainer = VCameraPalette.DarkPrimaryContainer,
    onPrimaryContainer = VCameraPalette.DarkOnPrimaryContainer,
    secondary = Color(0xFF80CBC4),
    onSecondary = Color(0xFF003631),
    secondaryContainer = Color(0xFF1B4440),
    onSecondaryContainer = Color(0xFFB2DFDB),
    tertiary = Color(0xFFFFB74D),
    onTertiary = Color(0xFF3E2200),
    tertiaryContainer = Color(0xFF563200),
    onTertiaryContainer = Color(0xFFFFE0B2),
    background = VCameraPalette.DarkBackground,
    onBackground = VCameraPalette.DarkOnSurface,
    surface = VCameraPalette.DarkSurface,
    onSurface = VCameraPalette.DarkOnSurface,
    surfaceVariant = VCameraPalette.DarkSurfaceVariant,
    onSurfaceVariant = VCameraPalette.DarkOnSurfaceVariant,
    outline = VCameraPalette.DarkOutline,
    outlineVariant = Color(0xFF283534),
    error = Color(0xFFEF5350),
    onError = Color(0xFF410002),
    errorContainer = Color(0xFF5F1412),
    onErrorContainer = Color(0xFFFFDAD6)
)
