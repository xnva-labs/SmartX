package com.xnvalabs.smarteyex.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.xnvalabs.smarteyex.ui.theme.SystemGlassBorder
import com.xnvalabs.smarteyex.ui.theme.SystemGlassTint

/**
 * A thin, subtle glass card: translucent white fill + a soft light border.
 *
 * This is a translucent-surface approximation of glassmorphism, not true
 * backdrop blur — real backdrop blur (RenderEffect.createBlurEffect) needs
 * API 31+ and blurs whatever is drawn *behind* this composable in the same
 * layer, which isn't reliably available across all target devices yet. If
 * min-SDK later moves to 31+, this can be upgraded to a real blur via
 * Modifier.graphicsLayer { renderEffect = ... }; documented here so the
 * gap is visible rather than silently pretended away.
 */
@Composable
fun GlassPanel(
    shape: Shape,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .background(color = SystemGlassTint, shape = shape)
            .border(width = 1.dp, color = SystemGlassBorder, shape = shape),
    ) {
        content()
    }
}
