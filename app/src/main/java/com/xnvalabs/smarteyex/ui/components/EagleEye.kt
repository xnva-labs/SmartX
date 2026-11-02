package com.xnvalabs.smarteyex.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.minDimension
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.cos
import kotlin.math.sin

/**
 * Shared eagle-eye render used across screens (Activation, System, ...).
 * One drawing routine, re-colored and re-behaved per screen via
 * parameters, rather than duplicating the Canvas code per screen.
 *
 * @param irisColors [inner, mid, outer] stops for the iris radial gradient
 * @param pupilColor near-solid pupil fill
 * @param glintColor small catchlight highlight
 * @param browColor brow-ridge arc color; ignored if [showBrow] is false
 * @param ringColor focus-ring stroke color
 * @param showBrow whether to draw the brow ridge above the eye
 * @param focusPulse 0f..1f — drives the focus-ring pulse and iris-strand
 *   shimmer; pass a constant (e.g. 0f) for a fully static
 *   ring, or an animated value for a "sharpening" pulse
 * @param pupilOffset fraction (-1f..1f per axis) of the pupil's travel
 *   range within the iris — the iris/eye itself never
 *   moves, only the pupil+glint translate. Offset.Zero
 *   keeps the pupil centered (fully static eye).
 */
@Composable
fun EagleEye(
    irisColors: List<Color>,
    pupilColor: Color,
    glintColor: Color,
    browColor: Color,
    ringColor: Color,
    modifier: Modifier = Modifier,
    showBrow: Boolean = true,
    focusPulse: Float = 0f,
    pupilOffset: Offset = Offset.Zero,
) {
    require(irisColors.size == 3) { "irisColors must have exactly 3 stops: inner, mid, outer" }

    Canvas(modifier = modifier) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val irisRadius = size.minDimension * 0.34f
        val pupilRadius = irisRadius * 0.36f
        val maxPupilTravel = irisRadius - pupilRadius * 1.3f
        val pupilCenter = Offset(
            center.x + pupilOffset.x.coerceIn(-1f, 1f) * maxPupilTravel,
            center.y + pupilOffset.y.coerceIn(-1f, 1f) * maxPupilTravel,
        )

        if (showBrow) {
            val browPath = Path().apply {
                moveTo(center.x - irisRadius * 1.15f, center.y - irisRadius * 0.55f)
                quadraticBezierTo(
                    center.x, center.y - irisRadius * 1.55f,
                    center.x + irisRadius * 1.15f, center.y - irisRadius * 0.55f,
                )
            }
            drawPath(
                path = browPath,
                color = browColor,
                style = Stroke(width = irisRadius * 0.16f, cap = StrokeCap.Round),
                alpha = 0.85f,
            )
        }

        // Iris — stays centered and fixed regardless of pupil movement.
        drawCircle(
            brush = Brush.radialGradient(
                colors = irisColors,
                center = center,
                radius = irisRadius,
            ),
            radius = irisRadius,
            center = center,
        )

        // Fibrous striations radiating from the iris's own center — these
        // do not track the pupil, since real iris fibers radiate from a
        // fixed point even as the pupil shifts within them.
        val strandCount = 56
        for (i in 0 until strandCount) {
            val angle = (i / strandCount.toFloat()) * 2f * Math.PI.toFloat()
            val jitterR = if (i % 3 == 0) 0.92f else 1f
            val startR = pupilRadius * 1.05f
            val endR = irisRadius * 0.96f * jitterR
            val start = Offset(center.x + cos(angle) * startR, center.y + sin(angle) * startR)
            val end = Offset(center.x + cos(angle) * endR, center.y + sin(angle) * endR)
            val strandAlpha = (0.18f + 0.14f * (i % 4)) * (0.7f + 0.3f * focusPulse)
            drawLine(
                color = if (i % 2 == 0) irisColors[2] else irisColors[0],
                start = start,
                end = end,
                strokeWidth = 1.1f,
                alpha = strandAlpha,
            )
        }

        // Pupil — follows pupilOffset, clamped to stay inside the iris.
        drawCircle(color = pupilColor, radius = pupilRadius, center = pupilCenter)

        // Catchlight — moves together with the pupil.
        drawCircle(
            color = glintColor,
            radius = pupilRadius * 0.28f,
            center = Offset(
                pupilCenter.x - pupilRadius * 0.42f,
                pupilCenter.y - pupilRadius * 0.42f,
            ),
            alpha = 0.95f,
        )

        // Focus ring — always centered on the iris (not the pupil), since
        // this represents the eye's own focus, not eye-tracking.
        val ringRadius = irisRadius * (1.12f - 0.12f * focusPulse)
        drawCircle(
            color = ringColor,
            radius = ringRadius,
            center = center,
            style = Stroke(width = 1.6f),
            alpha = 0.35f + 0.25f * focusPulse,
        )
    }
}
