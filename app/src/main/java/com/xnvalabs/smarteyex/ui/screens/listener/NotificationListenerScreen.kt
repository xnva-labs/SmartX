package com.xnvalabs.smarteyex.ui.screens.listener

import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.xnvalabs.smarteyex.data.notifications.NotificationRepository
import com.xnvalabs.smarteyex.ui.theme.ListenerBgBottom
import com.xnvalabs.smarteyex.ui.theme.ListenerBgLower
import com.xnvalabs.smarteyex.ui.theme.ListenerBgMid
import com.xnvalabs.smarteyex.ui.theme.ListenerBgTop
import com.xnvalabs.smarteyex.ui.theme.ListenerCardBg
import com.xnvalabs.smarteyex.ui.theme.ListenerCardBorder
import com.xnvalabs.smarteyex.ui.theme.ListenerCoreBorder
import com.xnvalabs.smarteyex.ui.theme.ListenerCoreGlowInner
import com.xnvalabs.smarteyex.ui.theme.ListenerCoreGlowOuter
import com.xnvalabs.smarteyex.ui.theme.ListenerEditorOverlayBg
import com.xnvalabs.smarteyex.ui.theme.ListenerIconColor
import com.xnvalabs.smarteyex.ui.theme.ListenerMistColor
import com.xnvalabs.smarteyex.ui.theme.ListenerModeSelectedBg
import com.xnvalabs.smarteyex.ui.theme.ListenerModeSelectedBorder
import com.xnvalabs.smarteyex.ui.theme.ListenerMsgColor
import com.xnvalabs.smarteyex.ui.theme.ListenerSectionSub
import com.xnvalabs.smarteyex.ui.theme.ListenerSectionTitle
import com.xnvalabs.smarteyex.ui.theme.ListenerSignalRing
import com.xnvalabs.smarteyex.ui.theme.ListenerStatusActive
import com.xnvalabs.smarteyex.ui.theme.ListenerTextDark
import com.xnvalabs.smarteyex.ui.theme.ListenerToggleOff
import com.xnvalabs.smarteyex.ui.theme.ListenerToggleOn
import kotlinx.coroutines.delay
import kotlin.random.Random
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val APP_LIST = listOf("WhatsApp", "Telegram", "Instagram", "Gmail", "YouTube", "System")
private val MODE_OPTIONS = listOf("\uD83D\uDCF3 GETAR", "\uD83D\uDD0A SPEAK", "\uD83D\uDD14 DERING", "\uD83D\uDD07 SENYAP")

private data class NotifItem(val app: String, val msg: String, val time: String, val rank: Int)

/**
 * Notification Listener Screen — native translation. Unlike most other
 * screens, this one required NO theme-pivot decision: the prototype
 * itself is already a white→green gradient with no purple/navy anywhere
 * in its palette, so this ports close to 1:1 on color.
 *
 * Tahap 5 update: the feed shown here is real notifications from
 * [NotificationRepository] (fed by SmartEyeXNotificationListener), not
 * simulated samples. If the user hasn't granted system "Notification
 * access" yet, a banner above the feed opens that Settings screen — this
 * screen checks access again whenever it resumes, so coming back from
 * Settings after granting it updates the banner without a restart. The
 * access check itself lives in [NotificationRepository.isAccessGranted],
 * shared with MediaScreen (feature #28) since both ride the same grant.
 * Priority ranking, mode selection, and voice-reply toggle are all still
 * local UI state, same as before.
 *
 * [onBack] fires from the "‹" button.
 */
@Composable
fun NotificationListenerScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var priorityOrder by remember { mutableStateOf(listOf("WhatsApp", "Telegram", "Gmail")) }
    var voiceReplyOn by remember { mutableStateOf(false) }
    var selectedMode by remember { mutableStateOf(0) }
    var editorSlot by remember { mutableStateOf<Int?>(null) }
    var mistSurge by remember { mutableStateOf(false) }
    var corePulse by remember { mutableStateOf(false) }

    var hasAccess by remember { mutableStateOf(NotificationRepository.isAccessGranted(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasAccess = NotificationRepository.isAccessGranted(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun rankOf(app: String): Int {
        val i = priorityOrder.indexOf(app)
        return if (i == -1) 99 else i
    }

    val rawFeed = NotificationRepository.notifications.value
    val notifications = remember(rawFeed, priorityOrder) {
        val timeFmt = SimpleDateFormat("HH:mm", Locale.US)
        rawFeed
            .map { raw -> NotifItem(raw.app, raw.msg, timeFmt.format(Date(raw.timestamp)), rankOf(raw.app)) }
            .sortedBy { it.rank }
            .take(8)
    }

    LaunchedEffect(rawFeed.size) {
        if (rawFeed.isNotEmpty()) {
            mistSurge = true
            corePulse = true
        }
    }

    LaunchedEffect(mistSurge) {
        if (mistSurge) {
            delay(900)
            mistSurge = false
        }
    }

    LaunchedEffect(corePulse) {
        if (corePulse) {
            delay(650)
            corePulse = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0f to ListenerBgTop,
                    0.38f to ListenerBgMid,
                    0.72f to ListenerBgLower,
                    1f to ListenerBgBottom,
                ),
            ),
    ) {
        MistLayer(surge = mistSurge, modifier = Modifier.fillMaxSize())

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 22.dp)
                .padding(top = 24.dp, bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(26.dp),
        ) {
            Box(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "‹",
                    color = ListenerTextDark,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 20.sp,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .alpha(0.75f)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onBack,
                        ),
                )
                Text(
                    text = "NOTIFICATION LISTENER",
                    color = ListenerTextDark,
                    fontSize = 14.sp,
                    letterSpacing = 1.sp,
                    modifier = Modifier.align(Alignment.Center),
                )
            }

            ListenerCore(pulse = corePulse)

            if (!hasAccess) {
                AccessBanner(
                    onOpenSettings = {
                        context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    },
                )
            }

            SectionShell(title = "LIVE LISTENER") {
                NotifStream(items = notifications)
            }

            SectionShell(title = null) {
                VoiceReplyPanel(
                    on = voiceReplyOn,
                    onToggle = {
                        voiceReplyOn = !voiceReplyOn
                        if (voiceReplyOn) corePulse = true
                    },
                )
            }

            SectionShell(title = "NOTIFICATION MODE") {
                ModeGrid(selected = selectedMode, onSelect = { selectedMode = it })
            }

            SectionShell(
                title = "PRIORITAS NOTIFIKASI",
                subtitle = "Tentukan aplikasi yang harus diproses terlebih dahulu.",
            ) {
                PriorityList(order = priorityOrder, onSlotClick = { editorSlot = it })
            }
        }

        editorSlot?.let { slot ->
            PriorityEditorSheet(
                slotIndex = slot,
                currentApp = priorityOrder.getOrNull(slot),
                onSelect = { app ->
                    val next = priorityOrder.toMutableList()
                    val existing = next.indexOf(app)
                    if (existing != -1) next.removeAt(existing)
                    next.add(slot.coerceIn(0, next.size), app)
                    priorityOrder = next.take(3)
                    editorSlot = null
                },
                onDismiss = { editorSlot = null },
            )
        }
    }
}

@Composable
private fun AccessBanner(onOpenSettings: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ListenerCardBg, RoundedCornerShape(14.dp))
            .border(1.dp, ListenerCardBorder, RoundedCornerShape(14.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onOpenSettings,
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Izin notifikasi belum aktif", color = ListenerTextDark, fontSize = 12.5.sp)
            Text("Ketuk buat buka Settings & aktifin akses", color = ListenerSectionSub, fontSize = 11.sp)
        }
        Text("›", color = ListenerSignalRing, fontSize = 16.sp, modifier = Modifier.alpha(0.7f))
    }
}

@Composable
private fun MistLayer(surge: Boolean, modifier: Modifier = Modifier) {
    val particles = remember {
        List(22) {
            val duration = 14000 + Random.nextInt(12000)
            MistParticle(
                xFraction = Random.nextFloat(),
                sizeDp = 40f + Random.nextFloat() * 90f,
                maxAlpha = 0.08f + Random.nextFloat() * 0.08f,
                driftDp = Random.nextFloat() * 40f - 20f,
                durationMs = duration,
                delayMs = Random.nextInt(duration),
            )
        }
    }
    BoxWithConstraints(modifier = modifier) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }
        particles.forEach { p -> MistDot(p, widthPx, heightPx, surge) }
    }
}

private data class MistParticle(
    val xFraction: Float,
    val sizeDp: Float,
    val maxAlpha: Float,
    val driftDp: Float,
    val durationMs: Int,
    val delayMs: Int,
)

@Composable
private fun MistDot(p: MistParticle, containerWidthPx: Float, containerHeightPx: Float, surge: Boolean) {
    val infinite = rememberInfiniteTransition(label = "mist")
    val progress by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(p.durationMs, delayMillis = p.delayMs, easing = LinearEasing),
        ),
        label = "mist-progress",
    )
    val baseAlpha = when {
        progress < 0.12f -> (progress / 0.12f) * p.maxAlpha
        progress < 0.85f -> p.maxAlpha
        else -> p.maxAlpha * (1f - (progress - 0.85f) / 0.15f)
    }
    val finalAlpha = (baseAlpha * (if (surge) 1.3f else 1f)).coerceIn(0f, 1f)

    val density = LocalDensity.current
    val startY = containerHeightPx * 1.1f
    val endY = -containerHeightPx * 0.12f
    val y = startY + (endY - startY) * progress
    val driftPx = with(density) { p.driftDp.dp.toPx() } * progress
    val x = containerWidthPx * p.xFraction + driftPx

    Box(
        modifier = Modifier
            .offset { IntOffset(x.toInt(), y.toInt()) }
            .size(p.sizeDp.dp)
            .alpha(finalAlpha)
            .background(
                Brush.radialGradient(listOf(ListenerMistColor, Color.Transparent)),
                CircleShape,
            ),
    )
}

@Composable
private fun ListenerCore(pulse: Boolean) {
    val infinite = rememberInfiniteTransition(label = "core-breath")
    val breath by infinite.animateFloat(
        initialValue = 1f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(
            animation = tween(2250, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "breath",
    )
    val signal1 by infinite.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(3000, easing = LinearEasing)),
        label = "signal1",
    )
    val signal2 by infinite.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(3000, delayMillis = 1500, easing = LinearEasing)),
        label = "signal2",
    )
    val pulseScale by animateFloatAsState(
        targetValue = if (pulse) 1.15f else 1f,
        animationSpec = tween(300),
        label = "voice-pulse",
    )

    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(110.dp)) {
            SignalRing(progress = signal1)
            SignalRing(progress = signal2)
            Box(
                modifier = Modifier
                    .size(90.dp)
                    .scale(breath * pulseScale)
                    .background(
                        Brush.radialGradient(listOf(ListenerCoreGlowInner, ListenerCoreGlowOuter)),
                        CircleShape,
                    )
                    .border(1.dp, ListenerCoreBorder, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                BellIcon(modifier = Modifier.size(38.dp))
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(modifier = Modifier.size(6.dp).background(ListenerStatusActive, CircleShape))
            Text("ACTIVE", color = ListenerStatusActive, fontFamily = FontFamily.Monospace, fontSize = 11.sp, letterSpacing = 2.sp)
        }
    }
}

@Composable
private fun BellIcon(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        drawArc(
            color = ListenerIconColor,
            startAngle = 180f,
            sweepAngle = 180f,
            useCenter = false,
            style = Stroke(width = 2.2f),
            topLeft = Offset(w * 0.12f, h * 0.15f),
            size = Size(w * 0.76f, h * 0.6f),
        )
        drawLine(
            color = ListenerIconColor,
            start = Offset(w * 0.12f, h * 0.75f),
            end = Offset(w * 0.88f, h * 0.75f),
            strokeWidth = 2.2f,
        )
        drawArc(
            color = ListenerIconColor,
            startAngle = 0f,
            sweepAngle = 180f,
            useCenter = false,
            style = Stroke(width = 2.2f),
            topLeft = Offset(w * 0.38f, h * 0.78f),
            size = Size(w * 0.24f, h * 0.16f),
        )
    }
}

@Composable
private fun SignalRing(progress: Float) {
    val scale = 0.7f + progress * 0.8f
    val alphaVal = (0.6f * (1f - progress)).coerceIn(0f, 0.6f)
    Box(
        modifier = Modifier
            .size(110.dp)
            .scale(scale)
            .alpha(alphaVal)
            .border(1.5.dp, ListenerSignalRing, CircleShape),
    )
}

@Composable
private fun SectionShell(title: String?, subtitle: String? = null, content: @Composable () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (title != null) {
            Text(title, color = ListenerSectionTitle, fontFamily = FontFamily.Monospace, fontSize = 10.sp, letterSpacing = 2.sp)
        }
        if (subtitle != null) {
            Text(subtitle, color = ListenerSectionSub, fontSize = 11.sp)
        }
        content()
    }
}

@Composable
private fun NotifStream(items: List<NotifItem>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 200.dp)
            .background(ListenerCardBg, RoundedCornerShape(14.dp))
            .border(1.dp, ListenerCardBorder, RoundedCornerShape(14.dp))
            .padding(6.dp),
    ) {
        if (items.isEmpty()) {
            Text(
                "Belum ada notifikasi masuk.",
                color = ListenerSectionSub,
                fontSize = 11.sp,
                modifier = Modifier.padding(10.dp),
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(items) { item -> NotifRow(item) }
            }
        }
    }
}

@Composable
private fun NotifRow(item: NotifItem) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 9.dp, horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(item.time, color = ListenerSignalRing, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
        Column(modifier = Modifier.weight(1f)) {
            Text(item.app, color = ListenerTextDark, fontSize = 12.5.sp)
            Text(item.msg, color = ListenerMsgColor, fontSize = 11.5.sp)
        }
        if (item.rank < 99) {
            Text(
                "P${item.rank + 1}",
                color = ListenerSignalRing,
                fontFamily = FontFamily.Monospace,
                fontSize = 9.sp,
                modifier = Modifier.alpha(0.7f),
            )
        }
    }
}

@Composable
private fun VoiceReplyPanel(on: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ListenerCardBg, RoundedCornerShape(14.dp))
            .border(1.dp, ListenerCardBorder, RoundedCornerShape(14.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("BALAS DENGAN SUARA", color = ListenerTextDark, fontFamily = FontFamily.Monospace, fontSize = 11.sp, letterSpacing = 1.sp)
        ToggleSwitch(on = on, onClick = onToggle)
    }
}

@Composable
private fun ToggleSwitch(on: Boolean, onClick: () -> Unit) {
    val density = LocalDensity.current
    val knobOffsetDp by animateDpAsState(targetValue = if (on) 20.dp else 0.dp, label = "knob-offset")
    val knobOffsetPx = with(density) { knobOffsetDp.roundToPx() }

    Box(
        modifier = Modifier
            .size(width = 44.dp, height = 24.dp)
            .background(if (on) ListenerToggleOn else ListenerToggleOff, RoundedCornerShape(50))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
    ) {
        Box(
            modifier = Modifier
                .padding(3.dp)
                .offset { IntOffset(knobOffsetPx, 0) }
                .size(18.dp)
                .background(Color.White, CircleShape),
        )
    }
}

@Composable
private fun ModeGrid(selected: Int, onSelect: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        MODE_OPTIONS.chunked(2).forEachIndexed { rowIndex, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEachIndexed { colIndex, label ->
                    val index = rowIndex * 2 + colIndex
                    val isSelected = index == selected
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .background(
                                if (isSelected) ListenerModeSelectedBg else ListenerCardBg,
                                RoundedCornerShape(12.dp),
                            )
                            .border(
                                1.dp,
                                if (isSelected) ListenerModeSelectedBorder else ListenerCardBorder,
                                RoundedCornerShape(12.dp),
                            )
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = { onSelect(index) },
                            )
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            label,
                            color = if (isSelected) ListenerTextDark else ListenerSectionTitle,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            letterSpacing = 1.sp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PriorityList(order: List<String>, onSlotClick: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        order.forEachIndexed { i, app ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(ListenerCardBg, RoundedCornerShape(12.dp))
                    .border(1.dp, ListenerCardBorder, RoundedCornerShape(12.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { onSlotClick(i) },
                    )
                    .padding(horizontal = 14.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .background(ListenerSignalRing.copy(alpha = 0.16f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("${i + 1}", color = ListenerSignalRing, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                }
                Text(app, color = ListenerTextDark, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Text("›", color = ListenerSignalRing, fontSize = 14.sp, modifier = Modifier.alpha(0.7f))
            }
        }
    }
}

@Composable
private fun PriorityEditorSheet(
    slotIndex: Int,
    currentApp: String?,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ListenerEditorOverlayBg)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            ),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.White, RoundedCornerShape(20.dp, 20.dp, 0.dp, 0.dp))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                )
                .padding(20.dp),
        ) {
            Text("PRIORITY ${slotIndex + 1}", color = ListenerTextDark, fontFamily = FontFamily.Monospace, fontSize = 12.sp, letterSpacing = 2.sp)
            Text("Pilih aplikasi", color = ListenerSectionSub, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp, bottom = 14.dp))

            APP_LIST.forEach { app ->
                val checked = app == currentApp
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { onSelect(app) },
                        )
                        .padding(vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .border(
                                1.5.dp,
                                if (checked) ListenerStatusActive else ListenerCardBorder,
                                CircleShape,
                            )
                            .padding(3.dp)
                            .then(if (checked) Modifier.background(ListenerStatusActive, CircleShape) else Modifier),
                    )
                    Text(app, color = ListenerTextDark, fontSize = 13.sp)
                }
            }

            Text(
                "Batal",
                color = ListenerSectionSub,
                fontSize = 12.sp,
                modifier = Modifier
                    .padding(top = 10.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss,
                    ),
            )
        }
    }
}
