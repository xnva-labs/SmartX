package com.xnvalabs.smarteyex.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val SmartEyeXLightScheme = lightColorScheme(
    primary = AccentOrange,
    onPrimary = LightSurface,
    secondary = AccentTurquoise,
    onSecondary = LightSurface,
    tertiary = AccentViolet,
    background = LightBgWarm,
    onBackground = TextPrimaryLight,
    surface = LightSurface,
    onSurface = TextPrimaryLight,
    surfaceVariant = LightSurfaceMuted,
    outline = LightBorder,
    error = SmartRed,
)

fun smartEyeXBackgroundBrush(width: Float, height: Float): Brush =
    Brush.linearGradient(
        colors = listOf(LightBgWarm, LightBgPeach, LightBgAqua, LightBgViolet),
        start = Offset(0f, 0f),
        end = Offset(width, height),
    )

fun activationBackgroundBrush(width: Float, height: Float): Brush =
    Brush.radialGradient(
        colors = listOf(ActivationBgSoft, ActivationBgTurq),
        center = Offset(width / 2f, height * 0.42f),
        radius = maxOf(width, height) * 0.85f,
    )

fun systemBackgroundBrush(width: Float, height: Float): Brush =
    Brush.radialGradient(
        colors = listOf(SystemBgMid, SystemBgDeep),
        center = Offset(width / 2f, height * 0.38f),
        radius = maxOf(width, height) * 0.9f,
    )

fun profileBackgroundBrush(width: Float, height: Float): Brush =
    Brush.verticalGradient(
        colors = listOf(ProfileBgTop, ProfileBgBottom),
        startY = 0f,
        endY = height,
    )

fun xnaiBackgroundBrush(width: Float, height: Float): Brush =
    Brush.verticalGradient(
        colors = listOf(XnaiBgBase, XnaiBgBase2),
        startY = 0f,
        endY = height,
    )

@Composable
fun SmartEyeXTheme(content: @Composable () -> Unit) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        val activity = view.context as? android.app.Activity
        activity?.window?.let { window ->
            window.statusBarColor = LightBgWarm.toArgb()
            window.navigationBarColor = LightBgWarm.toArgb()
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = true
            controller.isAppearanceLightNavigationBars = true
        }
    }
    MaterialTheme(
        colorScheme = SmartEyeXLightScheme,
        typography = SmartEyeXTypography,
        content = content,
    )
}
