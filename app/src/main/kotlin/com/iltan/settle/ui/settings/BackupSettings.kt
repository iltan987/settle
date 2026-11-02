package com.iltan.settle.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.iltan.settle.R
import com.iltan.settle.data.db.FolderSummary
import com.iltan.settle.domain.backup.BackupPause
import com.iltan.settle.domain.backup.BackupSettings
import com.iltan.settle.domain.backup.DEFAULT_GRACE_MILLIS
import com.iltan.settle.domain.backup.HOUR_MILLIS
import com.iltan.settle.domain.transfer.NetworkUse
import com.iltan.settle.ui.chrome.ChoiceSheet
import com.iltan.settle.ui.chrome.RowButtonStyle
import com.iltan.settle.ui.chrome.RowTone
import com.iltan.settle.ui.chrome.SettingsGroup
import com.iltan.settle.ui.chrome.SettingsRow
import com.iltan.settle.ui.chrome.Trailing
import com.iltan.settle.ui.folders.FolderBackupOffDialog
import com.iltan.settle.ui.folders.folderNames

/** What the Backup page's rows do. */
class BackupActions(
    val setAutomatic: (Boolean) -> Unit = {},
    val setGrace: (Long) -> Unit = {},
    val setOnlyWhileCharging: (Boolean) -> Unit = {},
    val setUseMobileData: (Boolean) -> Unit = {},
    val setNetworkUse: (NetworkUse) -> Unit = {},
    val setPaused: (Boolean) -> Unit = {},
    val setFolderBackUp: (FolderSummary, Boolean) -> Unit = { _, _ -> },
    val backUpEverythingNow: () -> Unit = {},
    val retryAfterFull: () -> Unit = {},
    val allowLocation: () -> Unit = {},
    val connect: () -> Unit = {},
    val signIn: () -> Unit = {},
    /** The Backup tab, where items that need attention are listed. */
    val openAttention: () -> Unit = {},
    val openFolders: () -> Unit = {},
)

/** Grace periods offered; a one-minute one only in debug builds, for testing. */
fun graceOptions(debug: Boolean): List<Long> =
    listOfNotNull(
        if (debug) 60_000L else null,
        0L,
        HOUR_MILLIS,
        24 * HOUR_MILLIS,
        3 * 24 * HOUR_MILLIS,
        7 * 24 * HOUR_MILLIS,
    )

/** Fixed limits offered, in megabits per second. */
val LIMIT_OPTIONS_MBPS = listOf(5, 10, 20, 40)

/**
 * Settings → Backup (I6): the status with its one action, then the automatic backup choices, the
 * folders, and Pause. Rows that don't apply now are left out.
 */
@Composable
fun BackupPage(state: BackupSettingsState, debugOptions: Boolean, actions: BackupActions) {
    if (!state.loaded) return
    val settings = state.choices.settings
    val connected = state.pause != BackupPause.NOT_CONNECTED
    SettingsGroup {
        row { StatusRow(state, actions) }
        if (state.needsAttention > 0) {
            row {
                SettingsRow(
                    title =
                        pluralStringResource(
                            R.plurals.status_needs_attention,
                            state.needsAttention,
                            state.needsAttention,
                        ),
                    sub = stringResource(R.string.settings_attention_sub),
                    icon = R.drawable.ic_error_filled,
                    tone = RowTone.ERR,
                    trailing = Trailing.Chevron,
                    onClick = actions.openAttention,
                )
            }
        }
        if (connected && state.run == null && state.upcoming > 0) {
            row {
                SettingsRow(
                    title = stringResource(R.string.backup_everything_now),
                    sub =
                        stringResource(
                            if (state.pause == null) R.string.backup_everything_now_text
                            else R.string.backup_resume_first
                        ),
                    icon = R.drawable.ic_bolt,
                    trailing =
                        Trailing.Action(
                            stringResource(R.string.action_start),
                            RowButtonStyle.TONAL,
                            actions.backUpEverythingNow,
                            // The pause wins (I6b).
                            enabled = state.pause == null,
                        ),
                )
            }
        }
    }
    if (!connected) return
    AutomaticGroup(settings, state.choices.networkUse, debugOptions, actions)
    SettingsGroup(title = stringResource(R.string.settings_what_backs_up)) {
        row {
            SettingsRow(
                title = stringResource(R.string.backup_folders),
                sub = chosenFolders(state.folders),
                trailing = Trailing.Chevron,
                onClick = actions.openFolders,
            )
        }
    }
    SettingsGroup {
        if (!settings.paused) {
            row {
                SettingsRow(
                    title = stringResource(R.string.backup_pause),
                    sub = stringResource(R.string.backup_pause_text),
                    icon = R.drawable.ic_pause_circle,
                    trailing =
                        Trailing.Action(
                            stringResource(R.string.action_pause),
                            RowButtonStyle.OUTLINE,
                            { actions.setPaused(true) },
                        ),
                )
            }
        }
    }
}

/** The top row: the same state as the Backup tab's header, with its one action. */
@Composable
private fun StatusRow(state: BackupSettingsState, actions: BackupActions) {
    val waiting =
        pluralStringResource(R.plurals.backup_count_waiting, state.upcoming, state.upcoming)
    data class Status(
        val icon: Int,
        val tone: RowTone,
        val title: String,
        val sub: String?,
        val action: Trailing.Action?,
    )
    fun action(label: String, onClick: () -> Unit) =
        Trailing.Action(label, RowButtonStyle.FILLED, onClick)
    val run = state.run
    val status =
        when (state.pause) {
            BackupPause.NOT_CONNECTED ->
                Status(
                    R.drawable.ic_cloud_off,
                    RowTone.PLAIN,
                    stringResource(R.string.backup_off),
                    stringResource(R.string.backup_off_sub),
                    action(stringResource(R.string.action_connect), actions.connect),
                )
            BackupPause.AUTH_NEEDED ->
                Status(
                    R.drawable.ic_account_circle,
                    RowTone.WARN,
                    stringResource(R.string.header_paused_sign_in),
                    waiting,
                    action(stringResource(R.string.action_sign_in), actions.signIn),
                )
            BackupPause.QUOTA_FULL ->
                Status(
                    R.drawable.ic_cloud_off_filled,
                    RowTone.WARN,
                    stringResource(R.string.header_paused_drive_full),
                    waiting,
                    action(stringResource(R.string.action_try_again), actions.retryAfterFull),
                )
            BackupPause.NO_ORIGINAL_ACCESS ->
                Status(
                    R.drawable.ic_location_off,
                    RowTone.WARN,
                    stringResource(R.string.header_paused_location),
                    waiting,
                    action(stringResource(R.string.backup_allow), actions.allowLocation),
                )
            BackupPause.BY_USER ->
                Status(
                    R.drawable.ic_pause_circle_filled,
                    RowTone.WARN,
                    stringResource(R.string.status_paused_by_you),
                    waiting,
                    action(stringResource(R.string.backup_resume)) { actions.setPaused(false) },
                )
            null ->
                if (run != null) {
                    Status(
                        R.drawable.ic_cloud_upload,
                        RowTone.PRIMARY,
                        pluralStringResource(
                            R.plurals.header_backing_up,
                            run.totalItems,
                            run.doneItems,
                            run.totalItems,
                        ),
                        backedUpAndWaiting(state.backedUp, state.upcoming),
                        null,
                    )
                } else {
                    Status(
                        R.drawable.ic_cloud_done_filled,
                        RowTone.PRIMARY,
                        backedUpAndWaiting(state.backedUp, state.upcoming),
                        state.lastBackupAtMs?.let { lastBackup(it) }
                            ?: stringResource(R.string.backup_nothing_yet_sub).takeIf {
                                state.backedUp == 0 && state.upcoming == 0
                            },
                        null,
                    )
                }
        }
    SettingsRow(
        title = status.title,
        sub = status.sub,
        icon = status.icon,
        tone = status.tone,
        trailing = status.action,
    )
}

/**
 * "256 backed up · 3 waiting"; before the first backup only what waits, and "Nothing to back up
 * yet" when no chosen folder has anything.
 */
@Composable
internal fun backedUpAndWaiting(backedUp: Int, upcoming: Int): String =
    if (backedUp == 0 && upcoming == 0) {
        stringResource(R.string.backup_nothing_yet)
    } else {
        listOfNotNull(
                backedUp
                    .takeIf { it > 0 }
                    ?.let { pluralStringResource(R.plurals.backup_count_backed_up, it, it) },
                upcoming
                    .takeIf { it > 0 }
                    ?.let { pluralStringResource(R.plurals.backup_count_waiting, it, it) },
            )
            .joinToString(" · ")
    }

/** "Last backup today at 03:12". */
@Composable
internal fun lastBackup(atMillis: Long): String =
    stringResource(R.string.settings_last_backup, whenText(atMillis))

@Composable
private fun AutomaticGroup(
    settings: BackupSettings,
    networkUse: NetworkUse,
    debugOptions: Boolean,
    actions: BackupActions,
) {
    var choosingGrace by rememberSaveable { mutableStateOf(false) }
    var choosingNetwork by rememberSaveable { mutableStateOf(false) }
    SettingsGroup(title = stringResource(R.string.settings_automatic_backup)) {
        row {
            SettingsRow(
                title = stringResource(R.string.backup_automatic),
                trailing = Trailing.Toggle(settings.automatic),
                onClick = { actions.setAutomatic(!settings.automatic) },
            )
        }
        // Without automatic backup, its wait and the charger mean nothing.
        if (settings.automatic) {
            row {
                SettingsRow(
                    title = stringResource(R.string.backup_grace),
                    sub = graceLabel(settings.graceMillis),
                    trailing = Trailing.Chevron,
                    onClick = { choosingGrace = true },
                )
            }
            row {
                SettingsRow(
                    title = stringResource(R.string.backup_only_charging),
                    trailing = Trailing.Toggle(settings.onlyWhileCharging),
                    onClick = { actions.setOnlyWhileCharging(!settings.onlyWhileCharging) },
                )
            }
        }
        row {
            SettingsRow(
                title = stringResource(R.string.backup_mobile_data),
                sub =
                    stringResource(
                        if (settings.useMobileData) R.string.backup_mobile_data_on
                        else R.string.backup_mobile_data_off
                    ),
                trailing = Trailing.Toggle(settings.useMobileData),
                onClick = { actions.setUseMobileData(!settings.useMobileData) },
            )
        }
        row {
            SettingsRow(
                title = stringResource(R.string.backup_network),
                sub = networkLabel(networkUse),
                trailing = Trailing.Chevron,
                onClick = { choosingNetwork = true },
            )
        }
    }
    if (choosingGrace) {
        ChoiceSheet(
            title = stringResource(R.string.backup_grace),
            lead = stringResource(R.string.backup_grace_lead),
            options = graceOptions(debugOptions),
            selected = settings.graceMillis,
            label = { graceLabel(it) },
            sub = {
                when (it) {
                    0L -> stringResource(R.string.backup_grace_none_sub)
                    DEFAULT_GRACE_MILLIS -> stringResource(R.string.backup_recommended)
                    else -> null
                }
            },
            onChoose = actions.setGrace,
            onDismiss = { choosingGrace = false },
        )
    }
    if (choosingNetwork) {
        val options = remember {
            listOf(NetworkUse.Polite) +
                LIMIT_OPTIONS_MBPS.map { NetworkUse.Limit(it * 1_000_000L / 8) } +
                NetworkUse.Unlimited
        }
        ChoiceSheet(
            title = stringResource(R.string.backup_network),
            lead = stringResource(R.string.backup_network_lead),
            options = options,
            selected = networkUse,
            label = {
                if (it == NetworkUse.Polite) stringResource(R.string.backup_network_polite_short)
                else networkLabel(it)
            },
            sub = {
                if (it == NetworkUse.Polite) stringResource(R.string.backup_network_polite_sub)
                else null
            },
            onChoose = actions.setNetworkUse,
            onDismiss = { choosingNetwork = false },
        )
    }
}

@Composable
private fun chosenFolders(folders: List<FolderSummary>): String {
    val root = stringResource(R.string.folder_root)
    val chosen = folders.filter { it.backsUp }
    if (chosen.isEmpty()) return stringResource(R.string.backup_folders_none)
    val names = remember(folders, root) { folderNames(folders.map { it.relativePath }, root) }
    return chosen.joinToString(", ") { names[it.relativePath].orEmpty() }
}

@Composable
internal fun graceLabel(millis: Long): String =
    when (millis) {
        0L -> stringResource(R.string.backup_grace_none)
        60_000L -> stringResource(R.string.backup_grace_minute)
        else ->
            if (millis % (24 * HOUR_MILLIS) == 0L) {
                val days = (millis / (24 * HOUR_MILLIS)).toInt()
                if (days == 1) pluralStringResource(R.plurals.backup_grace_hours, 24, 24)
                else pluralStringResource(R.plurals.backup_grace_days, days, days)
            } else {
                val hours = (millis / HOUR_MILLIS).toInt()
                pluralStringResource(R.plurals.backup_grace_hours, hours, hours)
            }
    }

@Composable
private fun networkLabel(use: NetworkUse): String =
    when (use) {
        NetworkUse.Polite -> stringResource(R.string.backup_network_polite)
        NetworkUse.Unlimited -> stringResource(R.string.backup_network_unlimited)
        is NetworkUse.Limit ->
            stringResource(R.string.backup_network_limit, (use.bytesPerSecond * 8 / 1_000_000))
    }

/**
 * Folders to back up: every folder with its switch. Turning one off asks first, since new photos
 * there then stay only on the phone (H4).
 */
@Composable
fun BackupFoldersPage(folders: List<FolderSummary>, onChange: (FolderSummary, Boolean) -> Unit) {
    val root = stringResource(R.string.folder_root)
    val names = remember(folders, root) { folderNames(folders.map { it.relativePath }, root) }
    var stopping by remember { mutableStateOf<FolderSummary?>(null) }
    SettingsGroup(lead = stringResource(R.string.backup_folders_lead)) {
        folders.forEach { folder ->
            row {
                SettingsRow(
                    title = names[folder.relativePath].orEmpty(),
                    sub =
                        pluralStringResource(
                            R.plurals.item_count,
                            folder.itemCount,
                            folder.itemCount,
                        ),
                    trailing = Trailing.Toggle(folder.backsUp),
                    onClick = { if (folder.backsUp) stopping = folder else onChange(folder, true) },
                )
            }
        }
    }
    stopping?.let { folder ->
        FolderBackupOffDialog(
            name = names[folder.relativePath].orEmpty(),
            onConfirm = {
                onChange(folder, false)
                stopping = null
            },
            onDismiss = { stopping = null },
        )
    }
}
