package com.iltan.settle.ui.settings

import android.text.format.Formatter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.iltan.settle.R
import com.iltan.settle.ui.chrome.AccountInitial
import com.iltan.settle.ui.chrome.ConfirmDialog
import com.iltan.settle.ui.chrome.NoticeAction
import com.iltan.settle.ui.chrome.NoticeCard
import com.iltan.settle.ui.chrome.NoticeContent
import com.iltan.settle.ui.chrome.RowTone
import com.iltan.settle.ui.chrome.SettingsGroup
import com.iltan.settle.ui.chrome.SettingsRow
import com.iltan.settle.ui.chrome.Tone
import com.iltan.settle.ui.chrome.Trailing

/** What the Google Drive group's rows do. */
class DriveActions(
    val connect: () -> Unit = {},
    val disconnect: () -> Unit = {},
    val openFolder: (folderId: String) -> Unit = {},
    val resetSandbox: () -> Unit = {},
)

/**
 * Settings → Google Drive (I1, I3, I4): the account, Drive's storage with a meter, Settle's folder
 * and Disconnect; before connecting, a card that offers to connect; when Google wants the user to
 * sign in again, the same warning card as the timeline above the rows.
 */
@Composable
fun DriveGroup(state: DriveSettingsState, actions: DriveActions) {
    if (!state.loaded) return
    var confirmDisconnect by rememberSaveable { mutableStateOf(false) }
    var confirmReset by rememberSaveable { mutableStateOf(false) }
    val email = state.email
    val notice: (@Composable () -> Unit)? =
        when {
            email == null -> {
                {
                    NoticeCard(
                        NoticeContent(
                            Tone.INFO,
                            R.drawable.ic_add_to_drive,
                            stringResource(R.string.drive_not_connected),
                            stringResource(R.string.drive_explainer),
                            NoticeAction(stringResource(R.string.action_connect), actions.connect),
                        ),
                        flush = true,
                    )
                }
            }
            state.needsReconnect -> {
                {
                    NoticeCard(
                        NoticeContent(
                            Tone.WARN,
                            R.drawable.ic_account_circle,
                            stringResource(R.string.notice_signin_title),
                            stringResource(R.string.notice_signin_body),
                            NoticeAction(stringResource(R.string.action_sign_in), actions.connect),
                        ),
                        flush = true,
                    )
                }
            }
            else -> null
        }
    SettingsGroup(title = stringResource(R.string.settings_drive), notice = notice) {
        if (email != null) {
            row {
                SettingsRow(
                    title = email,
                    sub =
                        stringResource(
                            if (state.needsReconnect) R.string.drive_needs_sign_in
                            else R.string.drive_connected
                        ),
                    subTone = if (state.needsReconnect) RowTone.WARN else RowTone.PLAIN,
                    leading = { AccountInitial(email) },
                )
            }
            row { StorageRow(state) }
            row {
                val folderId = state.folderId
                SettingsRow(
                    title = stringResource(R.string.drive_folder_title),
                    sub =
                        stringResource(
                            if (folderId != null) R.string.drive_folder_in_my_drive
                            else R.string.drive_folder_not_yet,
                            state.folder,
                        ),
                    icon = R.drawable.ic_folder,
                    trailing = folderId?.let { Trailing.External },
                    onClick = folderId?.let { { actions.openFolder(it) } },
                )
            }
            row {
                SettingsRow(
                    title = stringResource(R.string.drive_disconnect),
                    icon = R.drawable.ic_link_off,
                    tone = RowTone.ERR,
                    danger = true,
                    onClick = { if (!state.busy) confirmDisconnect = true },
                )
            }
            if (state.sandbox) {
                row {
                    SettingsRow(
                        title = stringResource(R.string.drive_sandbox_reset),
                        sub = stringResource(R.string.drive_sandbox_reset_text, state.folder),
                        icon = R.drawable.ic_delete,
                        onClick = { if (!state.busy) confirmReset = true },
                    )
                }
            }
        }
    }

    if (confirmDisconnect) {
        // Connecting again resumes from the same folder, so this is primary, not red (I5).
        ConfirmDialog(
            title = stringResource(R.string.drive_disconnect_title),
            text = listOf(stringResource(R.string.drive_disconnect_text)),
            confirm = stringResource(R.string.drive_disconnect),
            icon = R.drawable.ic_link_off,
            onConfirm = {
                confirmDisconnect = false
                actions.disconnect()
            },
            onDismiss = { confirmDisconnect = false },
        )
    }
    if (confirmReset) {
        ConfirmDialog(
            title = stringResource(R.string.drive_sandbox_reset),
            text = listOf(stringResource(R.string.drive_sandbox_reset_text, state.folder)),
            confirm = stringResource(R.string.drive_sandbox_reset_action),
            destructive = true,
            onConfirm = {
                confirmReset = false
                actions.resetSandbox()
            },
            onDismiss = { confirmReset = false },
        )
    }
}

/** "50 GB of 5.5 TB used" with its meter; the last known value stays while signed out (I4). */
@Composable
private fun StorageRow(state: DriveSettingsState) {
    val context = LocalContext.current
    val about = state.about
    val usage = about?.usageBytes
    val limit = about?.limitBytes
    SettingsRow(
        title =
            when {
                usage == null && state.readingAbout -> stringResource(R.string.settings_checking)
                usage == null -> stringResource(R.string.drive_storage_unknown)
                limit == null ->
                    stringResource(
                        R.string.drive_storage_unlimited,
                        Formatter.formatShortFileSize(context, usage),
                    )
                else ->
                    stringResource(
                        R.string.drive_storage,
                        Formatter.formatShortFileSize(context, usage),
                        Formatter.formatShortFileSize(context, limit),
                    )
            },
        sub = stringResource(R.string.drive_storage_sub),
        icon = R.drawable.ic_cloud,
        meter = if (usage != null && limit != null && limit > 0) usage.toFloat() / limit else null,
    )
}
