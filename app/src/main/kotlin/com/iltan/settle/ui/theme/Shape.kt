package com.iltan.settle.ui.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/** Tidepool's corner scale from the handoff sheet, on the Material 3 slots. */
internal val SettleShapes: Shapes =
    Shapes(
        extraSmall = RoundedCornerShape(4.dp),
        small = RoundedCornerShape(8.dp),
        medium = RoundedCornerShape(12.dp),
        large = RoundedCornerShape(16.dp),
        largeIncreased = RoundedCornerShape(20.dp),
        extraLarge = RoundedCornerShape(28.dp),
        extraLargeIncreased = RoundedCornerShape(32.dp),
    )

/** Named corners, so screens say what they draw rather than a size. */
object SettleShape {
    /** Grid cells. */
    val Cell = RoundedCornerShape(4.dp)
    /** A selected cell's inset image. */
    val SelectedCell = RoundedCornerShape(6.dp)
    /** Thumbnails in rows, days-left chips. */
    val Thumb = RoundedCornerShape(8.dp)
    val Snackbar = RoundedCornerShape(12.dp)
    /** The backup header's icon tile. */
    val IconTile = RoundedCornerShape(14.dp)
    /** Folder covers, utility tiles, info strips. */
    val Cover = RoundedCornerShape(16.dp)
    /** Settings cards, popovers, lists. */
    val Card = RoundedCornerShape(20.dp)
    val Notice = RoundedCornerShape(24.dp)
    /** Sheets, dialogs, the backup header, the viewer's toolbar. */
    val Large = RoundedCornerShape(28.dp)
    val Sheet = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    /** The 96 dp empty-state tile. */
    val EmptyTile = RoundedCornerShape(32.dp)
    /** Buttons, chips, the nav pill. */
    val Pill = CircleShape
}
