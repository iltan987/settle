package com.iltan.settle.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/*
 * Tidepool's tokens and the Material 3 roles they map to, from the handoff sheet
 * (docs/design/turn4/Settle Handoff.dc.html, ADR-016):
 *
 * | Tidepool      | M3 role                              |
 * |---------------|--------------------------------------|
 * | bg            | background, surface                  |
 * | surf          | cards, sheets: see [SettleColors.card]|
 * | c1 / c2 / c3  | surfaceContainer / High / Highest    |
 * | ink / ink2    | onSurface / onSurfaceVariant         |
 * | line          | outlineVariant                       |
 * | pri / onpri   | primary / onPrimary                  |
 * | pc / onpc     | primaryContainer / onPrimaryContainer|
 * | err / onerr   | error / onError                      |
 * | ec / onec     | errorContainer / onErrorContainer    |
 * | inv / oninv   | inverseSurface / inverseOnSurface    |
 * | invpri        | inversePrimary                       |
 * | warn, wc, onwc| SettleColors (M3 has no warning role)|
 *
 * On the phone the wallpaper's dynamic scheme fills the M3 roles; the error roles of a dynamic
 * scheme are fixed already. The fixed Tidepool schemes below serve tests and previews.
 */

internal val TidepoolLight: ColorScheme =
    lightColorScheme(
        background = Color(0xFFF4F8F7),
        onBackground = Color(0xFF141D1B),
        surface = Color(0xFFF4F8F7),
        onSurface = Color(0xFF141D1B),
        surfaceContainerLowest = Color(0xFFFFFFFF),
        surfaceContainerLow = Color(0xFFEDF3F1),
        surfaceContainer = Color(0xFFE6EFEC),
        surfaceContainerHigh = Color(0xFFD6E4E0),
        surfaceContainerHighest = Color(0xFFC2D4CF),
        surfaceVariant = Color(0xFFD6E4E0),
        onSurfaceVariant = Color(0xFF3F4D49),
        outline = Color(0xFF6F7D79),
        outlineVariant = Color(0xFFCBD8D4),
        primary = Color(0xFF1F6A64),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFBFEDE6),
        onPrimaryContainer = Color(0xFF00201D),
        secondaryContainer = Color(0xFFBFEDE6),
        onSecondaryContainer = Color(0xFF00201D),
        error = Color(0xFFB3261E),
        onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFF9DEDC),
        onErrorContainer = Color(0xFF410E0B),
        inverseSurface = Color(0xFF2A3230),
        inverseOnSurface = Color(0xFFEDF2F0),
        inversePrimary = Color(0xFF8ED5CC),
    )

internal val TidepoolDark: ColorScheme =
    darkColorScheme(
        background = Color(0xFF0E1413),
        onBackground = Color(0xFFDDE4E2),
        surface = Color(0xFF0E1413),
        onSurface = Color(0xFFDDE4E2),
        surfaceContainerLowest = Color(0xFF172020),
        surfaceContainerLow = Color(0xFF172020),
        surfaceContainer = Color(0xFF1B2524),
        surfaceContainerHigh = Color(0xFF24302E),
        surfaceContainerHighest = Color(0xFF34423F),
        surfaceVariant = Color(0xFF24302E),
        onSurfaceVariant = Color(0xFFA8B6B2),
        outline = Color(0xFF8A9894),
        outlineVariant = Color(0xFF2F3B39),
        primary = Color(0xFF8ED5CC),
        onPrimary = Color(0xFF003732),
        primaryContainer = Color(0xFF1E4F49),
        onPrimaryContainer = Color(0xFFBFEDE6),
        secondaryContainer = Color(0xFF1E4F49),
        onSecondaryContainer = Color(0xFFBFEDE6),
        error = Color(0xFFF2B8B5),
        onError = Color(0xFF601410),
        errorContainer = Color(0xFF8C1D18),
        onErrorContainer = Color(0xFFFFDAD6),
        inverseSurface = Color(0xFFDDE4E2),
        inverseOnSurface = Color(0xFF2A3230),
        inversePrimary = Color(0xFF1F6A64),
    )

/**
 * The colours Material 3 has no role for. Warnings keep a fixed amber so they never take on the
 * wallpaper's hue; content over photos and the viewer's stage are fixed too.
 */
@Immutable
data class SettleColors(
    val warning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
    val dark: Boolean,
) {
    /**
     * The viewer's stage, its chrome and the markers drawn over photos: the same in both themes.
     */
    val stage: Color = Color.Black
    val onStage: Color = Color.White
    /** #202020 at 92%, no blur: the viewer's toolbar and status chip. */
    val stageChrome: Color = Color(0xEB202020)
    /** The viewer chip's icon hints on the stage (D4): safe, paused, attention. */
    val stageOk: Color = Color(0xFF8ED5CC)
    val stageWarn: Color = Color(0xFFF0BE6E)
    val stageErr: Color = Color(0xFFF2B8B5)
    /** The viewer chip's one action pill. */
    val stageAction: Color = Color(0xFFBFEDE6)
    val onStageAction: Color = Color(0xFF00201D)
    /** Behind the viewer's top bar: black at 55%, fading out over 120 dp. */
    val stageScrim: Color = Color(0x8C000000)
    val onPhoto: Color = Color.White
    /** Behind a cell's status glyph (black at 46%). */
    val photoBadge: Color = Color(0x75000000)
    /** Behind sheets and dialogs (black at 50%). */
    val scrim: Color = Color(0x80000000)

    companion object {
        val Light =
            SettleColors(
                warning = Color(0xFF7A5000),
                warningContainer = Color(0xFFFFDDAE),
                onWarningContainer = Color(0xFF2A1800),
                dark = false,
            )
        val Dark =
            SettleColors(
                warning = Color(0xFFF0BE6E),
                warningContainer = Color(0xFF4D3500),
                onWarningContainer = Color(0xFFFFDDAE),
                dark = true,
            )
    }
}

internal val LocalSettleColors = staticCompositionLocalOf { SettleColors.Light }

/** Settle's own colours, next to [MaterialTheme.colorScheme]. */
val MaterialTheme.settleColors: SettleColors
    @Composable @ReadOnlyComposable get() = LocalSettleColors.current

/** Tidepool's `surf`: settings cards, sheets, dialogs, attention lists. */
val MaterialTheme.cardColor: Color
    @Composable
    @ReadOnlyComposable
    get() =
        if (LocalSettleColors.current.dark) colorScheme.surfaceContainerLow
        else colorScheme.surfaceContainerLowest
