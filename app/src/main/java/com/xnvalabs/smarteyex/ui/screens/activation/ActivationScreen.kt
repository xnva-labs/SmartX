package com.xnvalabs.smarteyex.ui.screens.activation

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xnvalabs.smarteyex.ui.components.EagleEye
import com.xnvalabs.smarteyex.ui.theme.EagleBrow
import com.xnvalabs.smarteyex.ui.theme.EagleGlint
import com.xnvalabs.smarteyex.ui.theme.EagleIrisInner
import com.xnvalabs.smarteyex.ui.theme.EagleIrisMid
import com.xnvalabs.smarteyex.ui.theme.EagleIrisOuter
import com.xnvalabs.smarteyex.ui.theme.EaglePupil
import com.xnvalabs.smarteyex.ui.theme.FractureColor
import com.xnvalabs.smarteyex.ui.theme.TextMutedLight
import com.xnvalabs.smarteyex.ui.theme.activationBackgroundBrush

/**
 * Activation Screen — native redesign, not a 1:1 port of the HTML
 * prototype. Direction given explicitly for the native app:
 * - background: cool/turquoise-dominant, dark reduced to near zero
 * - fracture line: exactly screen-centered, drawn BEHIND the eye
 * - eye: real eagle-eye render (fibrous iris, sharp pupil, catchlight,
 *   brow ridge) with a pulsing "sharpening" focus-ring effect; pupil
 *   stays centered here (Activation's eye is otherwise static — the
 *   moving pupil is a System Screen behavior, see SystemScreen.kt)
 * - palette kept to two temperatures only (cool bg, warm eye) so
 *   nothing clashes
 *
 * [onActivate] fires on tap. The transition-out animation (the
 * prototype's "split open" effect) is a separate, later step — this
 * screen only wires the tap target and callback for now.
 */
@Composable
fun ActivationScreen(onActivate: () -> Unit) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }
        val brush = activationBackgroundBrush(widthPx, heightPx)

        val infinite = rememberInfiniteTransition(label = "eagle-sharpen")
        val focusPulse by infinite.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(2200, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "focus-pulse",
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(brush),
        ) {
            // Fracture line — exactly centered, drawn first so the eye
            // (added after it in this Box) renders on top of it.
            FractureLine(
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxSize(),
            )

            // Eagle eye — centered, drawn after the fracture line so it
            // sits in front of it.
            EagleEye(
                irisColors = listOf(EagleIrisInner, EagleIrisMid, EagleIrisOuter),
                pupilColor = EaglePupil,
                glintColor = EagleGlint,
                browColor = EagleBrow,
                ringColor = FractureColor,
                focusPulse = focusPulse,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(220.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onActivate,
                    ),
            )

            Text(
                text = "TAP TO ACTIVATE",
                color = TextMutedLight,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                letterSpacing = 3.sp,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(top = 168.dp),
            )
        }
    }
}

/**
 * A jagged crack running horizontally through the exact vertical and
 * horizontal center of its bounds. Deterministic zig-zag (fixed offsets,
 * not random per-recomposition) so it doesn't visually jump on
 * recomposition.
 */
@Composable
private fun FractureLine(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val midY = size.height / 2f
        val jitters = floatArrayOf(0f, -14f, 10f, -22f, 6f, -8f, 18f, -12f, 4f, -18f, 0f)
        val path = Path()
        val stepX = size.width / (jitters.size - 1)
        jitters.forEachIndexed { i, jitter ->
            val x = i * stepX
            val y = midY + jitter
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(
            path = path,
            color = FractureColor,
            style = Stroke(width = 2.5f, cap = StrokeCap.Round),
            alpha = 0.4f,
        )
        // soft glow pass, wider + fainter, behind the crisp line
        drawPath(
            path = path,
            color = FractureColor,
            style = Stroke(width = 8f, cap = StrokeCap.Round),
            alpha = 0.12f,
        )
    }
}
