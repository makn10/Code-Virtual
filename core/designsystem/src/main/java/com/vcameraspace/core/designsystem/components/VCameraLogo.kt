package com.vcameraspace.core.designsystem.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.vcameraspace.core.designsystem.theme.VCameraTheme

/**
 * Vector-drawn emblem combining a camera lens aperture and circular space orbit
 * using the teal & white brand palette.
 */
@Composable
fun VCameraLogo(
    modifier: Modifier = Modifier,
    size: Dp = 64.dp,
    contentDescription: String? = "VCamera Space logo"
) {
    val primary = MaterialTheme.colorScheme.primary
    val darkBrand = VCameraTheme.extendedColors.darkBrand

    Canvas(
        modifier = modifier
            .size(size)
            .then(
                if (contentDescription != null) {
                    Modifier.semantics { this.contentDescription = contentDescription }
                } else {
                    Modifier
                }
            )
    ) {
        val diameter = this.size.minDimension
        val center = Offset(this.size.width / 2f, this.size.height / 2f)
        val radius = diameter / 2f

        // Base teal badge circle
        drawCircle(
            brush = Brush.linearGradient(
                colors = listOf(primary, darkBrand),
                start = Offset.Zero,
                end = Offset(diameter, diameter)
            ),
            radius = radius,
            center = center
        )

        // Outer "Space" orbital arcs
        val orbitStroke = diameter * 0.065f
        val orbitInset = diameter * 0.16f
        val orbitSize = Size(diameter - orbitInset * 2f, diameter - orbitInset * 2f)
        drawArc(
            color = Color.White.copy(alpha = 0.92f),
            startAngle = -55f,
            sweepAngle = 215f,
            useCenter = false,
            topLeft = Offset(orbitInset, orbitInset),
            size = orbitSize,
            style = Stroke(width = orbitStroke, cap = StrokeCap.Round)
        )
        drawArc(
            color = Color.White.copy(alpha = 0.55f),
            startAngle = 175f,
            sweepAngle = 110f,
            useCenter = false,
            topLeft = Offset(orbitInset, orbitInset),
            size = orbitSize,
            style = Stroke(width = orbitStroke, cap = StrokeCap.Round)
        )

        // Inner camera lens housing ring
        val lensRadius = diameter * 0.21f
        drawCircle(
            color = Color.White,
            radius = lensRadius,
            center = center,
            style = Stroke(width = diameter * 0.055f)
        )

        // Lens aperture core
        drawCircle(
            color = Color.White,
            radius = diameter * 0.09f,
            center = center
        )

        // Orbital space node accent
        val nodeAngleRad = Math.toRadians(-35.0)
        val nodeOrbitRadius = (diameter - orbitInset * 2f) / 2f
        val nodeCenter = Offset(
            x = center.x + (nodeOrbitRadius * kotlin.math.cos(nodeAngleRad)).toFloat(),
            y = center.y + (nodeOrbitRadius * kotlin.math.sin(nodeAngleRad)).toFloat()
        )
        drawCircle(
            color = Color.White,
            radius = diameter * 0.055f,
            center = nodeCenter
        )
    }
}
