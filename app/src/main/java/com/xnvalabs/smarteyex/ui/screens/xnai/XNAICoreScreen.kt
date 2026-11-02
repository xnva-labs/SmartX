package com.xnvalabs.smarteyex.ui.screens.xnai

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xnvalabs.smarteyex.data.xnai.XnaiMessage
import com.xnvalabs.smarteyex.data.xnai.XnaiRepository
import com.xnvalabs.smarteyex.ui.theme.XnaiAiBubbleBg
import com.xnvalabs.smarteyex.ui.theme.XnaiAiBubbleBorder
import com.xnvalabs.smarteyex.ui.theme.XnaiAvatarIrisInner
import com.xnvalabs.smarteyex.ui.theme.XnaiAvatarIrisOuter
import com.xnvalabs.smarteyex.ui.theme.XnaiAvatarPupil
import com.xnvalabs.smarteyex.ui.theme.XnaiAvatarRing
import com.xnvalabs.smarteyex.ui.theme.XnaiGlowTurquoise
import com.xnvalabs.smarteyex.ui.theme.XnaiHighA
import com.xnvalabs.smarteyex.ui.theme.XnaiHighB
import com.xnvalabs.smarteyex.ui.theme.XnaiOnlineGreen
import com.xnvalabs.smarteyex.ui.theme.XnaiRelaxA
import com.xnvalabs.smarteyex.ui.theme.XnaiRelaxB
import com.xnvalabs.smarteyex.ui.theme.XnaiSuperA
import com.xnvalabs.smarteyex.ui.theme.XnaiSuperB
import com.xnvalabs.smarteyex.ui.theme.XnaiSuperC
import com.xnvalabs.smarteyex.ui.theme.XnaiTextMuted
import com.xnvalabs.smarteyex.ui.theme.XnaiTextPrimary
import com.xnvalabs.smarteyex.ui.theme.XnaiUserBubbleBorder
import com.xnvalabs.smarteyex.ui.theme.XnaiUserBubbleEnd
import com.xnvalabs.smarteyex.ui.theme.XnaiUserBubbleStart
import com.xnvalabs.smarteyex.ui.theme.xnaiBackgroundBrush
import kotlin.random.Random

private enum class ThinkMode(val label: String, val pulsePeriodMs: Int?) {
    RELAX("RELAX", null),
    HIGH("HIGH", 1800),
    SUPER("SUPERAUTOMATION", 1100),
}

private fun ThinkMode.colorA(): Color = when (this) {
    ThinkMode.RELAX -> XnaiRelaxA
    ThinkMode.HIGH -> XnaiHighA
    ThinkMode.SUPER -> XnaiSuperA
}

private fun ThinkMode.colorB(): Color = when (this) {
    ThinkMode.RELAX -> XnaiRelaxB
    ThinkMode.HIGH -> XnaiHighB
    ThinkMode.SUPER -> XnaiSuperB
}

private fun ThinkMode.colorC(): Color? = if (this == ThinkMode.SUPER) XnaiSuperC else null

private data class ChatMessage(val text: String, val isUser: Boolean)

/**
 * XNAI Core Screen — native translation, first screen built under the
 * post-Profile design rule: dark is allowed, purple/navy is not. Base is
 * a neutral charcoal (not the prototype's blue-cast --deep-void), AI
 * bubble uses a neutral glass instead of the prototype's literal navy
 * rgba(8,27,70). THINK mode colors (green/orange/white) carry over
 * unchanged — none were purple or navy to begin with.
 *
 * Energy rails are drawn from the SAME point coordinates as the
 * prototype's SVG (viewBox 100×400), not a generic approximation, so the
 * jagged silhouette matches exactly, just re-rendered in Compose Canvas.
 *
 * Tahap 3 (Backend XNAI): sending a message calls [XnaiRepository], which
 * talks to the SmartEyeX server (docs/openapi.yaml). The typing indicator
 * stays up for as long as the real network call takes. If the user is not
 * signed in, Cloud Processing is OFF, or the server has no model provider
 * configured, the reason is shown as the AI's reply in the same bubble, so
 * the chat flow stays testable end-to-end. The call also silently
 * respects the Cloud Processing toggle from Tahap 1 and pulls in
 * whatever profile fields Tahap 2 has saved, so replies can reference
 * them once a real backend is live.
 *
 * [onBack] fires from the "‹ SYSTEM" button.
 */
@Composable
fun XNAICoreScreen(onBack: () -> Unit) {
    var mode by remember { mutableStateOf(ThinkMode.RELAX) }
    var showModeMenu by remember { mutableStateOf(false) }
    var inputText by remember { mutableStateOf("") }
    var isTyping by remember { mutableStateOf(false) }
    val messages = remember { mutableStateOf(listOf<ChatMessage>()) }
    val listState = rememberLazyListState()

    fun sendMessage() {
        val trimmed = inputText.trim()
        if (trimmed.isEmpty()) return
        messages.value = messages.value + ChatMessage(trimmed, isUser = true)
        inputText = ""
    }

    LaunchedEffect(messages.value.size) {
        val last = messages.value.lastOrNull()
        if (last != null && last.isUser) {
            isTyping = true
            val history = messages.value.dropLast(1).map {
                XnaiMessage(role = if (it.isUser) "user" else "assistant", text = it.text)
            }
            val result = XnaiRepository.sendMessage(
                userText = last.text,
                history = history,
                thinkMode = mode.label,
            )
            isTyping = false
            val replyText = result.fold(
                onSuccess = { it },
                onFailure = { e -> e.message ?: "Gagal menghubungi XNAI backend." },
            )
            messages.value = messages.value + ChatMessage(replyText, isUser = false)
        }
        if (messages.value.isNotEmpty()) {
            listState.animateScrollToItem(messages.value.size)
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }
        val railWidth = (widthPx * 0.13f).coerceIn(
            with(density) { 46.dp.toPx() },
            with(density) { 68.dp.toPx() },
        )
        val railWidthDp = with(density) { railWidth.toDp() }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(xnaiBackgroundBrush(widthPx, heightPx)),
        ) {
            // Ambient turquoise glow, upper-center — kept from the
            // prototype's identity; turquoise itself was never part of
            // the purple/navy ban.
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth(0.9f)
                    .height(280.dp)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(XnaiGlowTurquoise, Color.Transparent),
                        ),
                    ),
            )

            Column(modifier = Modifier.fillMaxSize()) {
                StatusBar(
                    mode = mode,
                    showModeMenu = showModeMenu,
                    onToggleMenu = { showModeMenu = !showModeMenu },
                    onSelectMode = { m -> mode = m; showModeMenu = false },
                    railWidthDp = railWidthDp,
                )

                Box(modifier = Modifier.fillMaxSize()) {
                    EnergyRail(
                        isLeft = true,
                        mode = mode,
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .fillMaxHeight()
                            .width(railWidthDp),
                    )
                    EnergyRail(
                        isLeft = false,
                        mode = mode,
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .fillMaxHeight()
                            .width(railWidthDp),
                    )

                    Text(
                        text = "‹ SYSTEM",
                        color = mode.colorA(),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(top = 12.dp, start = railWidthDp + 12.dp)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = onBack,
                            ),
                    )

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = railWidthDp + 10.dp),
                    ) {
                        if (messages.value.isEmpty()) {
                            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                                Text(
                                    text = "XNAI CORE READY — PILIH MODE THINK UNTUK MULAI",
                                    color = XnaiTextMuted,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp,
                                    letterSpacing = 1.sp,
                                )
                            }
                        } else {
                            LazyColumn(
                                state = listState,
                                modifier = Modifier.weight(1f).fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(14.dp),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 16.dp),
                            ) {
                                itemsIndexed(messages.value) { _, msg -> MessageBubble(msg) }
                                if (isTyping) {
                                    item { TypingBubble(mode) }
                                }
                            }
                        }

                        DockBar(
                            inputText = inputText,
                            onInputChange = { inputText = it },
                            onSend = { sendMessage() },
                            mode = mode,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusBar(
    mode: ThinkMode,
    showModeMenu: Boolean,
    onToggleMenu: () -> Unit,
    onSelectMode: (ThinkMode) -> Unit,
    railWidthDp: androidx.compose.ui.unit.Dp,
) {
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0x14000000))
                .padding(horizontal = railWidthDp + 10.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text("STATUS", color = XnaiTextMuted, fontFamily = FontFamily.Monospace, fontSize = 8.sp, letterSpacing = 2.sp)
                Text("ONLINE", color = XnaiOnlineGreen, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("THINK", color = XnaiTextMuted, fontFamily = FontFamily.Monospace, fontSize = 8.sp, letterSpacing = 2.sp)
                Text(
                    text = mode.label,
                    color = mode.colorA(),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    modifier = Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onToggleMenu,
                    ),
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("SISA TOKEN", color = XnaiTextMuted, fontFamily = FontFamily.Monospace, fontSize = 8.sp, letterSpacing = 2.sp)
                Text("24,891", color = XnaiTextPrimary, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            }
        }

        if (showModeMenu) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 44.dp)
                    .background(Color(0xEE141416), RoundedCornerShape(10.dp))
                    .padding(4.dp),
            ) {
                ThinkMode.entries.forEach { m ->
                    Text(
                        text = m.label,
                        color = if (m == mode) m.colorA() else XnaiTextPrimary,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        modifier = Modifier
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = { onSelectMode(m) },
                            )
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}

// Rail geometry — identical point coordinates to the prototype's SVG
// (viewBox 0 0 100 400), so the jagged silhouette matches exactly.
private val leftSmokePoints = listOf(
    0f to 0f, 0f to 400f, 20f to 400f, 60f to 360f, 24f to 320f, 62f to 270f,
    18f to 225f, 66f to 175f, 22f to 130f, 70f to 80f, 30f to 45f, 58f to 0f,
)
private val leftOutlinePoints = listOf(
    58f to 0f, 30f to 45f, 70f to 80f, 22f to 130f, 66f to 175f,
    18f to 225f, 62f to 270f, 24f to 320f, 60f to 360f, 20f to 400f,
)
private val rightSmokePoints = listOf(
    100f to 0f, 100f to 400f, 80f to 400f, 40f to 360f, 76f to 320f, 38f to 270f,
    82f to 225f, 34f to 175f, 78f to 130f, 30f to 80f, 70f to 45f, 42f to 0f,
)
private val rightOutlinePoints = listOf(
    42f to 0f, 70f to 45f, 30f to 80f, 78f to 130f, 34f to 175f,
    82f to 225f, 38f to 270f, 76f to 320f, 40f to 360f, 80f to 400f,
)

@Composable
private fun EnergyRail(isLeft: Boolean, mode: ThinkMode, modifier: Modifier = Modifier) {
    val infinite = rememberInfiniteTransition(label = "rail-pulse")
    val pulse by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(mode.pulsePeriodMs ?: 2600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse",
    )
    val intensity = if (mode.pulsePeriodMs == null) 0f else pulse

    val particles = remember(isLeft) {
        List(6) {
            Triple(Random.nextFloat(), (4000 + Random.nextInt(3000)), Random.nextInt(2500))
        }
    }

    BoxWithConstraints(modifier = modifier) {
        val density = LocalDensity.current
        val wPx = with(density) { maxWidth.toPx() }
        val hPx = with(density) { maxHeight.toPx() }
        val scaleX = wPx / 100f
        val scaleY = hPx / 400f

        val smokePts = if (isLeft) leftSmokePoints else rightSmokePoints
        val outlinePts = if (isLeft) leftOutlinePoints else rightOutlinePoints

        Canvas(modifier = Modifier.fillMaxSize()) {
            fun toPath(points: List<Pair<Float, Float>>, close: Boolean): Path {
                val p = Path()
                points.forEachIndexed { i, (x, y) ->
                    val pt = Offset(x * scaleX, y * scaleY)
                    if (i == 0) p.moveTo(pt.x, pt.y) else p.lineTo(pt.x, pt.y)
                }
                if (close) p.close()
                return p
            }

            val fillBrush = Brush.verticalGradient(listOf(mode.colorB(), mode.colorA()))
            drawPath(path = toPath(smokePts, close = true), brush = fillBrush, alpha = 0.30f + intensity * 0.15f)

            val outlineColor = mode.colorC() ?: mode.colorA()
            drawPath(
                path = toPath(outlinePts, close = false),
                color = outlineColor,
                style = Stroke(width = 1.6f + intensity * 0.8f, cap = StrokeCap.Round),
                alpha = 0.55f + intensity * 0.35f,
            )
            // soft glow pass for High/Super
            if (mode != ThinkMode.RELAX) {
                drawPath(
                    path = toPath(outlinePts, close = false),
                    color = outlineColor,
                    style = Stroke(width = 6f + intensity * 4f, cap = StrokeCap.Round),
                    alpha = 0.10f + intensity * 0.12f,
                )
            }
            outlinePts.forEach { (x, y) ->
                drawCircle(
                    color = mode.colorA(),
                    radius = 3.4f * ((scaleX + scaleY) / 2f) * 0.4f,
                    center = Offset(x * scaleX, y * scaleY),
                    alpha = 0.7f + intensity * 0.3f,
                )
            }
        }

        particles.forEach { (xFraction, durationMs, delayMs) ->
            RailParticle(xFraction, durationMs, delayMs, mode.colorA())
        }
    }
}

@Composable
private fun RailParticle(xFraction: Float, durationMs: Int, delayMs: Int, color: Color) {
    val infinite = rememberInfiniteTransition(label = "rail-particle")
    val progress by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMs, delayMillis = delayMs, easing = LinearEasing),
        ),
        label = "particle-progress",
    )
    val fadeAlpha = if (progress < 0.15f) progress / 0.15f else if (progress > 0.85f) (1f - progress) / 0.15f else 1f

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val heightDp = maxHeight
        val yDp = heightDp * (1f - progress)
        Box(
            modifier = Modifier
                .padding(start = maxWidth * xFraction, top = yDp)
                .size(3.dp)
                .alpha(fadeAlpha * 0.7f)
                .background(color, CircleShape),
        )
    }
}

@Composable
private fun MessageBubble(msg: ChatMessage) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (msg.isUser) Arrangement.End else Arrangement.Start,
    ) {
        if (!msg.isUser) {
            Avatar()
            Box(modifier = Modifier.width(8.dp))
        }
        Box(
            modifier = Modifier
                .fillMaxWidth(0.74f)
                .then(
                    if (msg.isUser) {
                        Modifier.background(
                            brush = Brush.linearGradient(listOf(XnaiUserBubbleStart, XnaiUserBubbleEnd)),
                            shape = RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp),
                        )
                    } else {
                        Modifier.background(
                            color = XnaiAiBubbleBg,
                            shape = RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp),
                        )
                    },
                )
                .border(
                    width = 1.dp,
                    color = if (msg.isUser) XnaiUserBubbleBorder else XnaiAiBubbleBorder,
                    shape = if (msg.isUser) {
                        RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp)
                    } else {
                        RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp)
                    },
                )
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text(text = msg.text, color = XnaiTextPrimary, fontSize = 13.sp, lineHeight = 18.sp)
        }
    }
}

@Composable
private fun TypingBubble(mode: ThinkMode) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Avatar()
        Box(modifier = Modifier.width(8.dp))
        Row(
            modifier = Modifier
                .background(XnaiAiBubbleBg, RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp))
                .border(1.dp, XnaiAiBubbleBorder, RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp))
                .padding(horizontal = 16.dp, vertical = 11.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            repeat(3) { i -> TypingDot(delayMs = i * 150, color = mode.colorA()) }
        }
    }
}

@Composable
private fun TypingDot(delayMs: Int, color: Color) {
    val infinite = rememberInfiniteTransition(label = "typing-dot")
    val bounce by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1100, delayMillis = delayMs, easing = LinearEasing),
        ),
        label = "bounce",
    )
    val alphaVal = if (bounce < 0.3f) 0.3f + bounce * 2.3f else 1f - (bounce - 0.3f) * 1.0f
    Box(
        modifier = Modifier
            .size(5.dp)
            .alpha(alphaVal.coerceIn(0.3f, 1f))
            .background(color, CircleShape),
    )
}

@Composable
private fun Avatar() {
    Canvas(modifier = Modifier.size(24.dp)) {
        drawCircle(
            brush = Brush.radialGradient(listOf(XnaiAvatarRing, Color.White, XnaiAvatarRing)),
            radius = size.minDimension / 2f,
        )
        drawCircle(
            brush = Brush.radialGradient(listOf(XnaiAvatarIrisInner, XnaiAvatarIrisOuter)),
            radius = size.minDimension * 0.32f,
        )
        drawCircle(color = XnaiAvatarPupil, radius = size.minDimension * 0.16f)
    }
}

@Composable
private fun DockBar(
    inputText: String,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    mode: ThinkMode,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 14.dp, top = 6.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0x261C1C1F), RoundedCornerShape(22.dp))
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.weight(1f)) {
                if (inputText.isEmpty()) {
                    Text("Tulis pesan…", color = XnaiTextMuted, fontSize = 13.sp)
                }
                BasicTextField(
                    value = inputText,
                    onValueChange = onInputChange,
                    textStyle = TextStyle(color = XnaiTextPrimary, fontSize = 13.sp),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Text(
                text = "SEND",
                color = mode.colorA(),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                modifier = Modifier
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onSend,
                    )
                    .padding(start = 10.dp),
            )
        }

        // XNVA_X signature mark — simplified as a small glowing X badge
        // rather than the prototype's full dash-offset shine animation;
        // alpha-pulses instead so it still reads as "alive", not static.
        XnvaMark(
            mode = mode,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(end = 6.dp)
                .offset { androidx.compose.ui.unit.IntOffset(0, -13) },
        )
    }
}

@Composable
private fun XnvaMark(mode: ThinkMode, modifier: Modifier = Modifier) {
    val infinite = rememberInfiniteTransition(label = "xnva-pulse")
    val pulse by infinite.animateFloat(
        initialValue = 0.8f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "xnva-pulse-value",
    )
    Canvas(
        modifier = modifier
            .size(27.dp)
            .alpha(pulse),
    ) {
        val strokeColor = mode.colorA()
        val w = size.width
        val h = size.height
        drawLine(color = strokeColor, start = Offset(w * 0.15f, h * 0.15f), end = Offset(w * 0.85f, h * 0.85f), strokeWidth = 3f, cap = StrokeCap.Round)
        drawLine(color = strokeColor, start = Offset(w * 0.85f, h * 0.15f), end = Offset(w * 0.15f, h * 0.85f), strokeWidth = 3f, cap = StrokeCap.Round)
    }
}
