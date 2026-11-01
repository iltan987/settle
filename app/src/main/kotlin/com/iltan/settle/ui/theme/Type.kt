package com.iltan.settle.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.iltan.settle.R

/** Figtree (OFL), bundled as one variable font; one entry per weight Settle uses. */
private fun figtree(weight: Int) =
    Font(
        R.font.figtree,
        weight = FontWeight(weight),
        variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
    )

val Figtree: FontFamily =
    FontFamily(figtree(300), figtree(400), figtree(500), figtree(600), figtree(700))

private fun style(size: Float, weight: Int, lineHeight: Float, tracking: Float = 0f) =
    TextStyle(
        fontFamily = Figtree,
        fontWeight = FontWeight(weight),
        fontSize = size.sp,
        lineHeight = (size * lineHeight).sp,
        letterSpacing = tracking.em,
    )

/**
 * Tidepool's type scale on the Material 3 slots (ADR-016). Sizes and weights come from the design's
 * components: top bar titles 24/600, the backup header 20/600, notice and row titles 15, body 14.
 */
internal val SettleTypography: Typography =
    Typography(
        displayLarge = style(52f, 700, 1.05f, -0.02f),
        displayMedium = style(40f, 700, 1.1f, -0.02f),
        displaySmall = style(32f, 700, 1.1f, -0.01f),
        headlineLarge = style(28f, 600, 1.2f, -0.01f),
        headlineMedium = style(26f, 600, 1.2f, -0.01f),
        headlineSmall = style(24f, 600, 1.1f, -0.01f),
        titleLarge = style(20f, 600, 1.3f),
        titleMedium = style(15f, 600, 1.3f),
        titleSmall = style(13.5f, 600, 1.3f),
        bodyLarge = style(15f, 400, 1.5f),
        bodyMedium = style(14f, 400, 1.45f),
        bodySmall = style(13f, 400, 1.4f),
        labelLarge = style(14f, 600, 1.2f),
        labelMedium = style(12.5f, 500, 1.3f),
        labelSmall = style(11f, 600, 1f),
    )
