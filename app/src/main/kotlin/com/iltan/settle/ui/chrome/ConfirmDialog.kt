package com.iltan.settle.ui.chrome

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.iltan.settle.R
import com.iltan.settle.ui.theme.SettleShape

/**
 * A question before an action (ConfirmDialog): radius 28, Cancel on the left, and the action as the
 * one filled button. [destructive] is only for what can't be undone (F4): an error icon and the
 * app's only filled error button. Neither button takes focus by itself, and back cancels.
 */
@Composable
fun ConfirmDialog(
    title: String,
    text: List<String>,
    confirm: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
    icon: Int? = if (destructive) R.drawable.ic_delete_forever else null,
) {
    val colors = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = SettleShape.Large,
        icon =
            icon?.let {
                {
                    Icon(
                        painterResource(it),
                        contentDescription = null,
                        tint = if (destructive) colors.error else colors.secondary,
                    )
                }
            },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                text.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                shape = SettleShape.Pill,
                colors =
                    if (destructive) {
                        ButtonDefaults.buttonColors(
                            containerColor = colors.error,
                            contentColor = colors.onError,
                        )
                    } else ButtonDefaults.buttonColors(),
            ) {
                Text(confirm)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
