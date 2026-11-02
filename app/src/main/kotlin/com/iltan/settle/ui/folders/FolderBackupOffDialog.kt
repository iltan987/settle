package com.iltan.settle.ui.folders

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.iltan.settle.R
import com.iltan.settle.ui.chrome.ConfirmDialog

/**
 * Asks before a folder stops backing up (H4). It can be turned on again, so the confirm is primary,
 * not red: copies already in Drive stay there, only new photos stay on the phone.
 */
@Composable
fun FolderBackupOffDialog(name: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    ConfirmDialog(
        title = stringResource(R.string.folder_stop_title, name),
        text = listOf(stringResource(R.string.folder_stop_text, name)),
        confirm = stringResource(R.string.folder_stop_confirm),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}
