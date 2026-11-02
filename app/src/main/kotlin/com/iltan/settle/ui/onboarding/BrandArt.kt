package com.iltan.settle.ui.onboarding

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.dp
import com.iltan.settle.ui.theme.SettleMotion

/**
 * The welcome art (BrandArt, A1), from the app icon's shapes: a dome settled on two rounded layers,
 * with small photo tiles that drift down to rest when it first shows. It is drawn here rather than
 * bundled as a VectorDrawable because its fills are theme tokens (pc, pri, c1–c3), so it follows
 * light, dark and the wallpaper's colours, and because the tiles move.
 */
@Composable
fun BrandArt(modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    // The tiles start 8 dp up and settle (spring 300/0.9).
    val drift = remember { Animatable(-8f) }
    LaunchedEffect(Unit) { drift.animateTo(0f, SettleMotion.drift()) }
    Canvas(modifier.fillMaxWidth().aspectRatio(WIDTH / HEIGHT)) {
        val unit = size.width / WIDTH
        val tileShift = drift.value.dp.toPx()
        fun rect(x: Float, y: Float, w: Float, h: Float, r: Float, color: Color, dy: Float = 0f) =
            drawRoundRect(
                color,
                topLeft = Offset(x * unit, y * unit + dy),
                size = Size(w * unit, h * unit),
                cornerRadius = CornerRadius(r * unit),
            )
        rect(58f, 44f, 40f, 40f, 10f, colors.surfaceContainerHigh, tileShift)
        rect(258f, 74f, 30f, 30f, 8f, colors.surfaceContainerHighest, tileShift)
        rect(206f, 22f, 22f, 22f, 6f, colors.surfaceContainer, tileShift)
        dome(unit, colors.primaryContainer)
        rect(70f, 154f, 208f, 34f, 17f, colors.primary)
        rect(104f, 200f, 140f, 28f, 14f, colors.surfaceContainerHigh)
    }
}

/** The dome: the part of a 78-radius circle above its chord at y = 146. */
private fun DrawScope.dome(unit: Float, color: Color) {
    clipRect(bottom = 146f * unit) {
        drawCircle(color, radius = 78f * unit, center = Offset(174f * unit, 152f * unit))
    }
}

/** The design's viewBox. */
private const val WIDTH = 348f
private const val HEIGHT = 250f
