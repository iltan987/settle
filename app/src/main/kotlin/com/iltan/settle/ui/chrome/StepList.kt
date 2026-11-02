package com.iltan.settle.ui.chrome

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.iltan.settle.ui.theme.SettleShape
import com.iltan.settle.ui.theme.cardColor

/**
 * The way through someone else's settings, one numbered step a line (StepList: c1, radius 20, 24 dp
 * discs). The steps repeat Android's or Samsung's own wording, so they can be followed there.
 */
@Composable
fun StepList(steps: List<String>, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer, SettleShape.Card)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        steps.forEachIndexed { index, step ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    Modifier.size(24.dp).background(MaterialTheme.cardColor, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "${index + 1}",
                        style =
                            MaterialTheme.typography.labelSmall.copy(
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.SemiBold,
                            ),
                    )
                }
                Text(step, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
