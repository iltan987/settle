package com.iltan.settle.ui.trash

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.iltan.settle.R

/**
 * Many files vanished from the phone in one scan (R14, ADR-006). Nothing changed in Drive; this
 * stays until the user answers, also after Settle restarts, because the answer is stored.
 */
@Composable
fun MassDisappearanceNotice(
    count: Int,
    onKeep: () -> Unit,
    onMoveToTrash: () -> Unit,
    onCheckAgain: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        colors =
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painterResource(R.drawable.ic_warning),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp).padding(end = 4.dp),
                )
                Text(
                    pluralStringResource(R.plurals.breaker_title, count, count),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            Text(stringResource(R.string.breaker_text), style = MaterialTheme.typography.bodyMedium)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                FilledTonalButton(onClick = onKeep) { Text(stringResource(R.string.breaker_keep)) }
                OutlinedButton(onClick = onMoveToTrash) {
                    Text(stringResource(R.string.move_drive_copies_to_trash))
                }
                TextButton(onClick = onCheckAgain) {
                    Text(stringResource(R.string.breaker_check_again))
                }
            }
        }
    }
}
