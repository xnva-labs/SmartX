package com.xnvalabs.smarteyex.ui.screens.loading

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xnvalabs.smarteyex.ui.theme.AccentBlue
import com.xnvalabs.smarteyex.ui.theme.AccentOrange
import com.xnvalabs.smarteyex.ui.theme.AccentViolet
import com.xnvalabs.smarteyex.ui.theme.TextMutedLight
import com.xnvalabs.smarteyex.ui.theme.TextPrimaryLight
import com.xnvalabs.smarteyex.ui.theme.smartEyeXBackgroundBrush
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Loading Screen — native Compose translation of #loading-screen in
 * smarteyex-ui-v17.html. Sequence mirrors the original animation names
 * (SEX v1.2 §8 Animation Naming System) but re-timed and re-colored for
 * the native light theme (SEX v1.9 §20):
 *
 * ANIM-001 InkFall (dark wipe)      → soft alpha reveal of the shared
 *                                      pastel background brush
 * ANIM-002 VoltageArrival           → same left-to-right line sweep,
 *                                      deepened blue for contrast on white
 * ANIM-003 TriColorScribble         → same 3 curved strokes,
 *                                      orange / blue / violet
 * ANIM-005 TextRunToCenter          → same left-to-center run
 *
 * [onFinished] fires once the boot sequence completes, for the caller to
 * navigate onward — mirrors the HTML's
 * `setTimeout(()=>showOnly(activation), 3200)`.
 */
@Composable
fun LoadingScreen(onFinished: () -> Unit) {
    val reveal = remember { Animatable(0f) }
    val voltage = remember { Animatable(0f) }
    val scribble = remember { List(3) { Animatable(0f) } }
    val textRun = remember { Animatable(0f) }
    val subtitleAlpha = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        launch {
            reveal.animateTo(1f, tween(900, easing = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)))
        }

        // ANIM-002 VoltageArrival — delay .45s, duration 1.1s (matches HTML 1:1)
        launch {
            delay(450)
            voltage.animateTo(1f, tween(1100, easing = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)))
        }

        // ANIM-003 TriColorScribble — staggered 150ms apart, 700ms each
        scribble.forEachIndexed { i, anim ->
            launch {
                delay(700 + i * 150L)
                anim.animateTo(1f, tween(700, easing = LinearEasing))
            }
        }

        // ANIM-005 TextRunToCenter — delay .4s, ~1.7s to settle at center
        launch {
            delay(400)
            textRun.animateTo(1f, tween(1700, easing = CubicBezierEasing(0.22f, 0.9f, 0.28f, 1f)))
        }

        launch {
            delay(2400)
            subtitleAlpha.animateTo(1f, tween(400, easing = LinearEasing))
        }

        delay(3200)
        onFinished()
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }
        val backgroundBrush = smartEyeXBackgroundBrush(widthPx, heightPx)

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(backgroundBrush, alpha = reveal.value),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(14.dp, alignment = Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // ANIM-002 VoltageArrival
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.38f)
                        .height(2.dp),
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        if (voltage.value > 0f) {
                            val startX = -size.width + (size.width * 2.2f * voltage.value)
                            drawLine(
                                brush = Brush.horizontalGradient(
                                    listOf(Color.Transparent, AccentBlue, AccentBlue.copy(alpha = 0.4f)),
                                ),
                                start = Offset(startX, size.height / 2f),
                                end = Offset(startX + size.width, size.height / 2f),
                                strokeWidth = size.height,
                                cap = StrokeCap.Round,
                                alpha = 0.9f,
                            )
                        }
                    }
                }

                // ANIM-003 TriColorScribble
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth(0.7f)
                        .height(60.dp),
                ) {
                    val scaleX = size.width / 300f
                    val scaleY = size.height / 60f
                    val strokes = listOf(
                        scribblePath("M10,42 Q60,10 110,32 T210,20", scaleX, scaleY) to AccentOrange,
                        scribblePath("M15,48 Q70,20 120,40 T220,28", scaleX, scaleY) to AccentBlue,
                        scribblePath("M20,54 Q80,28 130,46 T230,36", scaleX, scaleY) to AccentViolet,
                    )
                    strokes.forEachIndexed { i, (path, color) ->
                        drawPath(
                            path = trimPath(path, scribble[i].value),
                            color = color,
                            style = Stroke(width = 2.5f, cap = StrokeCap.Round),
                            alpha = 0.9f,
                        )
                    }
                }

                // ANIM-005 TextRunToCenter — bias interpolates start(-1) → center(0)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(40.dp),
                    contentAlignment = BiasAlignment(horizontalBias = -1f + textRun.value, verticalBias = 0f),
                ) {
                    if (textRun.value > 0f) {
                        Text(
                            text = buildAnnotatedString {
                                append("SMART")
                                withStyle(SpanStyle(color = AccentOrange)) { append("EYE") }
                                append("X")
                            },
                            color = TextPrimaryLight,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 26.sp,
                            letterSpacing = 4.sp,
                        )
                    }
                }

                // Subtitle — "INITIALIZING NEURAL CORE..."
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .alpha(subtitleAlpha.value),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "INITIALIZING NEURAL CORE...",
                        color = TextMutedLight,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 9.sp,
                        letterSpacing = 2.sp,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

/**
 * Parses one of the prototype's three "M x,y Q cx,cy x,y T x,y" scribble
 * paths into a scaled Compose [Path]. All three source paths share this
 * exact two-curve shape, so a generic SVG parser isn't needed.
 */
private fun scribblePath(svgPath: String, scaleX: Float, scaleY: Float): Path {
    val nums = svgPath
        .replace(Regex("[MQT]"), " ")
        .trim()
        .split(Regex("[,\\s]+"))
        .map { it.toFloat() }

    val path = Path()
    path.moveTo(nums[0] * scaleX, nums[1] * scaleY)
    path.quadraticBezierTo(
        nums[2] * scaleX, nums[3] * scaleY,
        nums[4] * scaleX, nums[5] * scaleY,
    )
    // "T x,y" reflects the previous control point across the current end
    // point — this is what SVG's T command does implicitly.
    val reflectedCx = nums[4] * 2 - nums[2]
    val reflectedCy = nums[5] * 2 - nums[3]
    path.quadraticBezierTo(
        reflectedCx * scaleX, reflectedCy * scaleY,
        nums[6] * scaleX, nums[7] * scaleY,
    )
    return path
}

/** Returns the sub-path of [path] from its start to [progress] (0f..1f) of its length. */
private fun trimPath(path: Path, progress: Float): Path {
    if (progress >= 1f) return path
    if (progress <= 0f) return Path()
    val measure = android.graphics.PathMeasure(path.asAndroidPath(), false)
    val out = android.graphics.Path()
    measure.getSegment(0f, measure.length * progress, out, true)
    return out.asComposePath()
}
