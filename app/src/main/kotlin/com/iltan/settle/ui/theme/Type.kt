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

/** Figtree (OFL), bundled as one variable font; one entry per weight Tidepool uses. */
private fun figtree(weight: Int) =
    Font(
        R.font.figtree,
        weight = FontWeight(weight),
        variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
    )

val Figtree: FontFamily = FontFamily(figtree(400), figtree(500), figtree(600), figtree(700))

private fun style(size: Float, weight: Int, lineHeight: Float, tracking: Float = 0f) =
    TextStyle(
        fontFamily = Figtree,
        fontWeight = FontWeight(weight),
        fontSize = size.sp,
        lineHeight = (size * lineHeight).sp,
        letterSpacing = tracking.em,
    )

/** Tidepool's type scale from the handoff sheet (`docs/design/turn4/Settle Handoff.dc.html`). */
internal val SettleTypography: Typography =
    Typography(
        // Big numbers ("18.4 GB").
        displayLarge = style(44f, 600, 1.05f, -0.02f),
        displayMedium = style(44f, 600, 1.05f, -0.02f),
        displaySmall = style(44f, 600, 1.05f, -0.02f),
        // The welcome headline.
        headlineLarge = style(28f, 600, 1.2f, -0.01f),
        // Full-screen titles.
        headlineMedium = style(26f, 600, 1.25f, -0.01f),
        // Tab titles: Photos, Folders, Backup.
        headlineSmall = style(24f, 600, 1.1f, -0.01f),
        // Pushed-screen bars, dialog titles.
        titleLarge = style(22f, 600, 1.25f),
        // Sheet titles, the selection count, the backup status header.
        titleMedium = style(20f, 600, 1.3f),
        // Notice card titles, the viewer's date.
        titleSmall = style(15f, 600, 1.3f),
        bodyLarge = style(15f, 400, 1.5f),
        bodyMedium = style(14f, 400, 1.45f),
        // Row subtitles, day-header dates.
        bodySmall = style(13f, 400, 1.4f),
        // Buttons and text actions.
        labelLarge = style(14f, 600, 1.2f),
        // Chips.
        labelMedium = style(13f, 600, 1.2f),
        // Navigation labels, section meta.
        labelSmall = style(12.5f, 500, 1.3f),
    )

/** Tidepool's styles that have no Material 3 slot. */
object SettleType {
    /** A settings row's title. */
    val rowTitle = style(15f, 500, 1.3f)
    /** A settings group's title, in primary. */
    val groupTitle = style(13.5f, 600, 1.3f)
    /** "Thursday, 29 October". */
    val dayHeader = style(14f, 600, 1.3f)
    /** An attention row's reason, a restore card's title. */
    val itemTitle = style(14.5f, 600, 1.3f)
    /** Video durations and "2 days" on cells. */
    val micro = style(11f, 600, 1f)
    /** Days-left chips on trash cells. */
    val chipMicro = style(10.5f, 600, 1f)
}
