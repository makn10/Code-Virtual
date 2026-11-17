package com.example.ui.theme

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.example.R
import com.vcameraspace.core.designsystem.theme.buildVCameraTypography

val PlusJakartaSansFamily = FontFamily(
    Font(R.font.plus_jakarta_sans, FontWeight.Normal)
)

val InterBodyFamily = FontFamily(
    Font(R.font.inter, FontWeight.Normal)
)

val JetBrainsMonoFamily = FontFamily(
    Font(R.font.jetbrains_mono, FontWeight.Normal)
)

val VCameraTypography = buildVCameraTypography(
    displayFontFamily = PlusJakartaSansFamily,
    bodyFontFamily = InterBodyFamily
)
