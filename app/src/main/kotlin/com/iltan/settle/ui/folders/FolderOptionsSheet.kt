package com.iltan.settle.ui.folders

import android.content.res.Resources
import android.text.format.Formatter
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.iltan.settle.R
import com.iltan.settle.data.db.FolderSummary
import com.iltan.settle.domain.backup.backsUpByDefault
import com.iltan.settle.ui.AppMessages
import com.iltan.settle.ui.LocalAppMessages
import com.iltan.settle.ui.chrome.SettingsGroup
import com.iltan.settle.ui.chrome.SettingsRow
import com.iltan.settle.ui.chrome.Trailing
import com.iltan.settle.ui.theme.SettleShape
import com.iltan.settle.ui.theme.cardColor
import com.iltan.settle.ui.theme.settleColors

/**
 * Changes a folder's choices and says what happened (H3): backing up says how many items go, hiding
 * offers Undo.
 */
@Composable
fun rememberFolderChoices(
    setHidden: (String, Boolean) -> Unit,
    setBackUp: (String, Boolean) -> Unit,
): FolderChoices {
    val messages = LocalAppMessages.current
    val resources = LocalResources.current
    return remember(messages, resources, setHidden, setBackUp) {
        FolderChoices(messages, resources, setHidden, setBackUp)
    }
}

/** Shows or hides a folder and turns its backup on or off, saying what happened. */
@Immutable
class FolderChoices(
    private val messages: AppMessages,
    private val resources: Resources,
    private val setHidden: (String, Boolean) -> Unit,
    private val setBackUp: (String, Boolean) -> Unit,
) {
    fun hide(folder: FolderSummary, name: String, hidden: Boolean) {
        setHidden(folder.relativePath, hidden)
        if (hidden) {
            messages.offer(
                resources.getString(R.string.folder_hidden_done, name),
                resources.getString(R.string.trash_undo),
            ) {
                setHidden(folder.relativePath, false)
            }
        }
    }

    fun backUp(folder: FolderSummary, name: String, backUp: Boolean) {
        setBackUp(folder.relativePath, backUp)
        val waiting = folder.itemCount - folder.backedUpCount
        messages.note(
            if (!backUp) resources.getString(R.string.folder_backup_stopped, name)
            else if (waiting > 0) {
                resources.getQuantityString(R.plurals.folder_backs_up_now, waiting, waiting, name)
            } else resources.getString(R.string.folder_backs_up, name)
        )
    }
}

/**
 * A folder's options (FolderOptionsSheet, H3): Show in Photos and Back up, each applied at once.
 * Turning backup off for a folder that already has copies in Drive asks first (H4).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderOptionsSheet(
    folder: FolderSummary,
    name: String,
    choices: FolderChoices,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var confirmStop by remember { mutableStateOf(false) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = SettleShape.Sheet,
        containerColor = MaterialTheme.cardColor,
        scrimColor = MaterialTheme.settleColors.scrim,
    ) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
            Text(
                name,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 24.dp).semantics { heading() },
            )
            Text(
                listOf(
                        pluralStringResource(
                            R.plurals.item_count,
                            folder.itemCount,
                            folder.itemCount,
                        ),
                        Formatter.formatShortFileSize(context, folder.sizeBytes),
                        folder.relativePath.trimEnd('/').ifEmpty { "/" },
                    )
                    .joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 12.dp),
            )
            SettingsGroup(
                modifier = Modifier.padding(horizontal = 12.dp),
                container = MaterialTheme.colorScheme.surfaceContainer,
                note =
                    stringResource(R.string.folder_only_camera).takeIf {
                        !folder.backsUp && !backsUpByDefault(folder.relativePath)
                    },
            ) {
                row {
                    SettingsRow(
                        title = stringResource(R.string.folder_show_in_photos),
                        sub = stringResource(R.string.folder_show_in_photos_sub),
                        icon = R.drawable.ic_visibility,
                        trailing = Trailing.Toggle(!folder.hiddenFromTimeline),
                        onClick = { choices.hide(folder, name, !folder.hiddenFromTimeline) },
                    )
                }
                row {
                    SettingsRow(
                        title = stringResource(R.string.folder_back_up),
                        sub = stringResource(R.string.folder_back_up_sub),
                        icon = R.drawable.ic_cloud_upload,
                        trailing = Trailing.Toggle(folder.backsUp),
                        onClick = {
                            when {
                                !folder.backsUp -> choices.backUp(folder, name, true)
                                folder.backedUpCount > 0 -> confirmStop = true
                                else -> choices.backUp(folder, name, false)
                            }
                        },
                    )
                }
            }
        }
    }
    if (confirmStop) {
        FolderBackupOffDialog(
            name = name,
            onConfirm = {
                confirmStop = false
                choices.backUp(folder, name, false)
            },
            onDismiss = { confirmStop = false },
        )
    }
}
