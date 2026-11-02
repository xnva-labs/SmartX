package com.xnvalabs.smarteyex.ui.screens.system

import androidx.compose.animation.core.LinearEasing
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xnvalabs.smarteyex.ui.components.EagleEye
import com.xnvalabs.smarteyex.ui.components.GlassPanel
import com.xnvalabs.smarteyex.ui.theme.SystemBrow
import com.xnvalabs.smarteyex.ui.theme.SystemGlint
import com.xnvalabs.smarteyex.ui.theme.SystemIrisInner
import com.xnvalabs.smarteyex.ui.theme.SystemIrisMid
import com.xnvalabs.smarteyex.ui.theme.SystemIrisOuter
import com.xnvalabs.smarteyex.ui.theme.SystemLabelText
import com.xnvalabs.smarteyex.ui.theme.SystemNodeIdle
import com.xnvalabs.smarteyex.ui.theme.SystemPupil
import com.xnvalabs.smarteyex.ui.theme.SystemRing
import com.xnvalabs.smarteyex.ui.theme.SystemTraceActive
import com.xnvalabs.smarteyex.ui.theme.SystemTraceIdle
import com.xnvalabs.smarteyex.ui.theme.systemBackgroundBrush
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

private data class SystemNode(val id: String, val label: String)

private val systemMenu = listOf(
    SystemNode("profile", "PROFILE"),
    SystemNode("listener", "NOTIFICATION LISTENER"),
    SystemNode("xnai", "XNAI CORE"),
    SystemNode("memory", "MEMORY"),
    SystemNode("vision", "COMPUTER VISION"),
    SystemNode("media", "MEDIA ASSISTANT"),
    SystemNode("translation", "TRANSLATION"),
    SystemNode("device", "DEVICE"),
    SystemNode("emergency", "EMERGENCY"),
    SystemNode("navigation", "NAVIGATION"),
    SystemNode("call", "CALL"),
    SystemNode("enterprise", "ENTERPRISE"),
    SystemNode("library", "PERPUSTAKAAN"),
    SystemNode("progress", "PROGRESS"),
    SystemNode("schedule", "SCHEDULE"),
)

/**
 * System Screen — native redesign. Direction given explicitly:
 * - colors: nearly the same family as Activation, but a bit darker,
 *   with a thin glassmorphism treatment on the header/eye panels
 * - eye: same eagle-eye style as Activation, but recolored to match the
 *   ORIGINAL prototype's icon (orange/white/near-black, not the
 *   naturalistic amber of Activation) — and here the eye itself stays
 *   fixed in place while only the PUPIL drifts, unlike Activation
 * - branch lines: redesigned deterministic curves (no randomness) for a
 *   clean, modern, professional look instead of the prototype's
 *   jittered paths
 * - non-overlap: node/label placement is clamped and offset away from
 *   screen edges, same defensive intent as the prototype
 *
 * Node positions are computed from systemMenu.size (angleFor divides
 * the full circle evenly), so adding a node — "vision" (Tahap 4),
 * "media" (#28), "translation" (#9), "device" (#37), "emergency" (#30), "navigation", "call"
 * — just needs a new entry in the list above; no position math
 * elsewhere needs touching.
 *
 * [onNodeSelected] fires with a node id when tapped; [onOpenSettings]
 * fires from the settings icon. "profile", "listener", "xnai", "memory",
 * "vision", "media", "translation", "device", "emergency" and "schedule"
 * are wired in MainActivity; "library", "progress", "navigation", "call" and "enterprise" are wired too.
 */
@Composable
fun SystemScreen(
    onNodeSelected: (String) -> Unit = {},
    onOpenSettings: () -> Unit = {},
) {
    var selectedId by remember { mutableStateOf<String?>(null) }

    val infinite = rememberInfiniteTransition(label = "system-eye")
    val pupilAngle by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 2f * Math.PI.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(9000, easing = LinearEasing),
        ),
        label = "pupil-drift-angle",
    )
    // Pupil traces a small, slow, uneven loop rather than a perfect
    // circle — reads as idle scanning rather than a mechanical orbit.
    val pupilOffset = Offset(
        x = cos(pupilAngle) * 0.5f,
        y = sin(pupilAngle * 1.3f) * 0.35f,
    )

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }
        val brush = systemBackgroundBrush(widthPx, heightPx)

        val ringCenter = Offset(widthPx / 2f, heightPx * 0.46f)
        val ringRadius = smallerOf(widthPx, heightPx) * 0.32f

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(brush),
        ) {
            // Branch lines — drawn first, behind the nodes/labels/eye.
            Canvas(modifier = Modifier.fillMaxSize()) {
                systemMenu.forEachIndexed { i, node ->
                    val angle = angleFor(i, systemMenu.size)
                    val nodePos = pointOn(ringCenter, ringRadius, angle)
                    val control = branchControlPoint(ringCenter, ringRadius, angle, i)
                    val path = Path().apply {
                        moveTo(ringCenter.x, ringCenter.y)
                        quadraticBezierTo(control.x, control.y, nodePos.x, nodePos.y)
                    }
                    val active = selectedId == node.id
                    drawPath(
                        path = path,
                        color = if (active) SystemTraceActive else SystemTraceIdle,
                        style = Stroke(
                            width = if (active) 2.6f else 1.4f,
                            cap = StrokeCap.Round,
                        ),
                        alpha = if (active) 1f else 0.55f,
                    )
                }
            }

            // Central eagle eye — fixed position, prototype's icon colors,
            // only the pupil drifts.
            val eyeSizePx = ringRadius * 0.72f
            GlassPanel(
                shape = CircleShape,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset {
                        IntOffset(
                            (ringCenter.x - eyeSizePx / 2f).toInt(),
                            (ringCenter.y - eyeSizePx / 2f).toInt(),
                        )
                    }
                    .size(with(density) { eyeSizePx.toDp() }),
            ) {
                EagleEye(
                    irisColors = listOf(SystemIrisInner, SystemIrisMid, SystemIrisOuter),
                    pupilColor = SystemPupil,
                    glintColor = SystemGlint,
                    browColor = SystemBrow,
                    ringColor = SystemRing,
                    showBrow = false,
                    focusPulse = 0.4f,
                    pupilOffset = pupilOffset,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(10.dp),
                )
            }

            // Nodes + labels
            systemMenu.forEachIndexed { i, node ->
                val angle = angleFor(i, systemMenu.size)
                val nodePos = pointOn(ringCenter, ringRadius, angle)
                val active = selectedId == node.id
                val dotSizeDp = 12.dp
                val dotSizePx = with(density) { dotSizeDp.toPx() }

                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .offset {
                            IntOffset(
                                (nodePos.x - dotSizePx / 2f).toInt(),
                                (nodePos.y - dotSizePx / 2f).toInt(),
                            )
                        }
                        .size(dotSizeDp)
                        .background(
                            color = if (active) SystemTraceActive else SystemNodeIdle,
                            shape = CircleShape,
                        )
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {
                                selectedId = node.id
                                onNodeSelected(node.id)
                            },
                        ),
                ) {}

                NodeLabel(
                    text = node.label,
                    angle = angle,
                    nodePos = nodePos,
                    density = density,
                )
            }

            // Header — glass pill with a ticking clock, top of screen.
            SystemHeader(modifier = Modifier.align(Alignment.TopCenter))

            // Settings icon — top-right corner.
            SettingsIconButton(
                onClick = onOpenSettings,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 20.dp, end = 20.dp),
            )
        }
    }
}

/** Returns the smaller of two Float dimensions — used for ring sizing so it fits either axis. */
private fun smallerOf(width: Float, height: Float): Float = if (width < height) width else height

private fun angleFor(index: Int, count: Int): Float =
    (-90f + index * (360f / count)) * (Math.PI.toFloat() / 180f)

private fun pointOn(center: Offset, radius: Float, angle: Float): Offset =
    Offset(center.x + radius * cos(angle), center.y + radius * sin(angle))

/**
 * Deterministic control point for a node's branch line — a modest,
 * fixed-magnitude perpendicular bend that alternates direction by index,
 * instead of the prototype's random jitter. Same shape every recomposition,
 * every launch — this is what makes it read as designed rather than messy.
 */
private fun branchControlPoint(center: Offset, radius: Float, angle: Float, index: Int): Offset {
    val mid = pointOn(center, radius * 0.55f, angle)
    val perp = angle + (Math.PI.toFloat() / 2f)
    val bendSign = if (index % 2 == 0) 1f else -1f
    val bend = radius * 0.10f
    return Offset(
        mid.x + cos(perp) * bend * bendSign,
        mid.y + sin(perp) * bend * bendSign,
    )
}

/**
 * Places a node's label beside it, oriented away from the nearest screen
 * edge — mirrors the prototype's align-left/right/center logic so long
 * labels never run off-screen or collide with the node ring.
 */
@Composable
private fun NodeLabel(
    text: String,
    angle: Float,
    nodePos: Offset,
    density: androidx.compose.ui.unit.Density,
) {
    val cosA = cos(angle)
    val sinA = sin(angle)
    val boxWidth = 118.dp
    val boxWidthPx = with(density) { boxWidth.toPx() }
    val gapPx = with(density) { 10.dp.toPx() }

    val (offsetX, offsetY, textAlign) = when {
        kotlin.math.abs(cosA) < 0.25f -> {
            val yShift = if (sinA > 0) gapPx * 1.6f else -gapPx * 1.6f - 14f
            Triple(nodePos.x - boxWidthPx / 2f, nodePos.y + yShift, TextAlign.Center)
        }
        cosA < 0 -> Triple(nodePos.x - boxWidthPx - gapPx, nodePos.y - 8f, TextAlign.End)
        else -> Triple(nodePos.x + gapPx, nodePos.y - 8f, TextAlign.Start)
    }

    Box(
        modifier = Modifier
            .offset { IntOffset(offsetX.toInt(), offsetY.toInt()) }
            .width(boxWidth),
    ) {
        Text(
            text = text,
            color = SystemLabelText,
            fontFamily = FontFamily.Monospace,
            fontSize = 9.sp,
            letterSpacing = 1.sp,
            textAlign = textAlign,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun SystemHeader(modifier: Modifier = Modifier) {
    var timeText by remember { mutableStateOf("00:00:00") }
    var dateText by remember { mutableStateOf("-") }

    LaunchedEffect(Unit) {
        val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)
        val dateFormat = SimpleDateFormat("EEE, dd MMM yyyy", Locale.US)
        while (true) {
            val now = Date()
            timeText = timeFormat.format(now)
            dateText = dateFormat.format(now)
            delay(1000)
        }
    }

    GlassPanel(
        shape = RoundedCornerShape(20.dp),
        modifier = modifier.padding(top = 28.dp),
    ) {
        Box(modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)) {
            Text(
                text = "$timeText · $dateText",
                color = SystemLabelText,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                letterSpacing = 1.sp,
            )
        }
    }
}

@Composable
private fun SettingsIconButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(38.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            // bridge
            drawLine(
                color = SystemTraceActive,
                start = Offset(w * 0.30f, h * 0.40f),
                end = Offset(w * 0.70f, h * 0.40f),
                strokeWidth = 1.6f,
            )
            // two lenses
            drawCircle(
                color = Color(0xFFFFF8ED),
                radius = w * 0.24f,
                center = Offset(w * 0.24f, h * 0.46f),
            )
            drawCircle(
                color = SystemTraceActive,
                radius = w * 0.24f,
                center = Offset(w * 0.24f, h * 0.46f),
                style = Stroke(width = 1.6f),
            )
            drawCircle(
                color = Color(0xFFFFF8ED),
                radius = w * 0.24f,
                center = Offset(w * 0.76f, h * 0.46f),
            )
            drawCircle(
                color = SystemTraceActive,
                radius = w * 0.24f,
                center = Offset(w * 0.76f, h * 0.46f),
                style = Stroke(width = 1.6f),
            )
        }
    }
}
