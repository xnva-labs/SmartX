package com.xnvalabs.smarteyex.ui.screens.profile

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xnvalabs.smarteyex.data.memory.MemoryRepository
import com.xnvalabs.smarteyex.data.privacy.PrivacyRepository
import com.xnvalabs.smarteyex.ui.theme.AccentOrange
import com.xnvalabs.smarteyex.ui.theme.ProfileDust
import com.xnvalabs.smarteyex.ui.theme.ProfileFocusAccent
import com.xnvalabs.smarteyex.ui.theme.ProfilePanelBg
import com.xnvalabs.smarteyex.ui.theme.ProfilePanelBorder
import com.xnvalabs.smarteyex.ui.theme.ProfileSubText
import com.xnvalabs.smarteyex.ui.theme.ProfileTitleText
import com.xnvalabs.smarteyex.ui.theme.profileBackgroundBrush
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random

private data class ProfileField(
    val label: String,
    val placeholder: String,
    val shiftRight: Boolean,
    val delayMs: Long,
)

private val profileFields = listOf(
    ProfileField("Name", "boleh kita kenalan?", shiftRight = true, delayMs = 0),
    ProfileField("Interest", "untuk mengetahui topik dan cara menjawab", shiftRight = false, delayMs = 500),
    ProfileField("Habit", "agar chemistry kita terjalin, boleh?", shiftRight = true, delayMs = 1000),
    ProfileField("BirthInfo", "agar dapat menjalin hubungan dengan baik", shiftRight = false, delayMs = 1500),
    ProfileField("Kelas", "opsional agar kita lebih spesifik", shiftRight = true, delayMs = 2000),
)

/**
 * Profile Screen — native translation. Direction: no explicit color
 * instruction was given for this screen, so it follows the broad "native
 * = light" decision rather than the prototype's black/blackout mood — see
 * ProfileBgTop/Bottom in Color.kt for the reasoning.
 *
 * NOT included: the prototype's EagleClose → full blackout → ProfileVoid
 * handoff animation from System Screen (the eye visually closing before
 * this screen appears). That lives on the System/Activation side of the
 * transition and hasn't been built for Compose yet — this screen starts
 * directly with its own dust-and-panel reveal sequence, timed to match
 * the prototype's internal choreography (void hold ~1s, then panels
 * stagger in at their original delays, title reveal at +2.5s).
 *
 * Tahap 2 (Personal Memory): each field's value is loaded from and saved
 * to [MemoryRepository] as it's typed (see IdPanel), so it survives
 * leaving and returning to this screen — it no longer resets to blank.
 * Saving itself is gated by the Memory privacy toggle inside
 * MemoryRepository, so this screen just shows a heads-up when it's off
 * rather than duplicating that check.
 *
 * [onBack] fires from the "‹ SYSTEM" button.
 */
@Composable
fun ProfileScreen(onBack: () -> Unit) {
    var revealStarted by remember { mutableStateOf(false) }
    val titleAlpha = remember { Animatable(0f) }
    val panelAlphas = remember { profileFields.map { Animatable(0f) } }
    val memoryEnabled = PrivacyRepository.settings.value.memoryEnabled

    LaunchedEffect(Unit) {
        delay(1000) // ProfileVoid hold, matches prototype's 1000ms
        revealStarted = true
        profileFields.forEachIndexed { i, field ->
            launch {
                delay(field.delayMs)
                panelAlphas[i].animateTo(1f, tween(500, easing = LinearEasing))
            }
        }
        delay(2500)
        titleAlpha.animateTo(1f, tween(600, easing = LinearEasing))
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }
        val brush = profileBackgroundBrush(widthPx, heightPx)

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(brush),
        ) {
            if (revealStarted) {
                DustField(modifier = Modifier.fillMaxSize())
            }

            Text(
                text = "‹ SYSTEM",
                color = ProfileFocusAccent,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                letterSpacing = 1.sp,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(top = 28.dp, start = 20.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onBack,
                    ),
            )

            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 64.dp)
                    .alpha(titleAlpha.value),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "PROFILE",
                    color = ProfileTitleText,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                    letterSpacing = 6.sp,
                )
                Text(
                    text = "Let's get to know each other.",
                    color = ProfileSubText,
                    fontSize = 12.sp,
                    fontStyle = FontStyle.Italic,
                    modifier = Modifier.padding(top = 6.dp),
                )
                if (!memoryEnabled) {
                    Text(
                        text = "Memory OFF — aktifkan di Privacy Control biar tersimpan.",
                        color = AccentOrange,
                        fontSize = 10.sp,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }

            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .padding(top = 120.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                profileFields.forEachIndexed { i, field ->
                    IdPanel(field = field, alpha = panelAlphas[i].value)
                }
            }
        }
    }
}

@Composable
private fun IdPanel(field: ProfileField, alpha: Float) {
    var text by remember { mutableStateOf(MemoryRepository.getProfileValue(field.label) ?: "") }
    var focused by remember { mutableStateOf(false) }
    val shiftFraction = if (field.shiftRight) 0.09f else -0.09f

    BoxWithConstraints(modifier = Modifier.fillMaxWidth(0.82f)) {
        val density = LocalDensity.current
        val shiftPx = with(density) { maxWidth.toPx() * shiftFraction }

        Column(
            modifier = Modifier
                .offset { IntOffset(shiftPx.toInt(), 0) }
                .alpha(alpha)
                .background(color = ProfilePanelBg, shape = RoundedCornerShape(16.dp))
                .border(
                    width = 1.dp,
                    color = if (focused) ProfileFocusAccent.copy(alpha = 0.6f) else ProfilePanelBorder,
                    shape = RoundedCornerShape(16.dp),
                )
                .padding(horizontal = 18.dp, vertical = 14.dp),
        ) {
            Text(
                text = field.label.uppercase(),
                color = ProfileTitleText,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                letterSpacing = 2.sp,
            )
            Box(modifier = Modifier.padding(top = 6.dp)) {
                if (text.isEmpty()) {
                    Text(
                        text = field.placeholder,
                        color = ProfileSubText,
                        fontSize = 14.sp,
                        fontStyle = FontStyle.Italic,
                    )
                }
                BasicTextField(
                    value = text,
                    onValueChange = {
                        text = it
                        MemoryRepository.upsertProfileField(field.label, it)
                    },
                    textStyle = TextStyle(color = ProfileTitleText, fontSize = 14.sp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { focused = it.isFocused },
                )
            }
        }
    }
}

@Composable
private fun DustField(modifier: Modifier = Modifier) {
    val particles = remember {
        List(26) {
            DustParticle(
                xFraction = Random.nextFloat(),
                sizeDp = 2f + Random.nextFloat() * 3f,
                maxAlpha = 0.15f + Random.nextFloat() * 0.35f,
                driftDp = Random.nextFloat() * 60f - 30f,
                durationMs = (6000 + Random.nextFloat() * 6000).toInt(),
                delayMs = (Random.nextFloat() * 4000).toInt(),
            )
        }
    }
    BoxWithConstraints(modifier = modifier) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }
        particles.forEach { p -> FallingDust(p, widthPx, heightPx) }
    }
}

private data class DustParticle(
    val xFraction: Float,
    val sizeDp: Float,
    val maxAlpha: Float,
    val driftDp: Float,
    val durationMs: Int,
    val delayMs: Int,
)

@Composable
private fun FallingDust(p: DustParticle, containerWidthPx: Float, containerHeightPx: Float) {
    val infinite = rememberInfiniteTransition(label = "dust")
    val progress by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(p.durationMs, delayMillis = p.delayMs, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "dust-progress",
    )
    val alpha = when {
        progress < 0.10f -> (progress / 0.10f) * p.maxAlpha
        progress < 0.85f -> p.maxAlpha
        else -> p.maxAlpha * (1f - (progress - 0.85f) / 0.15f)
    }

    val density = LocalDensity.current
    val startY = -0.04f * containerHeightPx
    val endY = 1.08f * containerHeightPx
    val y = startY + (endY - startY) * progress
    val driftPx = with(density) { p.driftDp.dp.toPx() } * progress
    val x = containerWidthPx * p.xFraction + driftPx

    Box(
        modifier = Modifier
            .offset { IntOffset(x.toInt(), y.toInt()) }
            .size(p.sizeDp.dp)
            .alpha(alpha)
            .background(color = ProfileDust, shape = CircleShape),
    )
}
