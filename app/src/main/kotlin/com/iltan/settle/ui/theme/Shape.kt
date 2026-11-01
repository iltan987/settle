package com.iltan.settle.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Tidepool's corners: 4 dp grid cells, 12 dp snackbars, 16 dp chips, 20 dp setting cards, 24 dp
 * notice cards, 28 dp the backup header, sheets and the viewer's toolbar.
 */
internal val SettleShapes: Shapes =
    Shapes(
        extraSmall = RoundedCornerShape(4.dp),
        small = RoundedCornerShape(12.dp),
        medium = RoundedCornerShape(16.dp),
        large = RoundedCornerShape(20.dp),
        largeIncreased = RoundedCornerShape(24.dp),
        extraLarge = RoundedCornerShape(28.dp),
    )

/** Named corners that the M3 slots don't carry. */
object SettleShape {
    val Cell = RoundedCornerShape(4.dp)
    val SelectedCell = RoundedCornerShape(6.dp)
    val Card = RoundedCornerShape(20.dp)
    val Notice = RoundedCornerShape(24.dp)
    val Header = RoundedCornerShape(28.dp)
    val Tile = RoundedCornerShape(14.dp)
    val Pill = RoundedCornerShape(50)
}
