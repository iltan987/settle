package com.iltan.settle.ui.settings

import android.Manifest
import android.app.ActivityManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.iltan.settle.R
import com.iltan.settle.appGraph
import com.iltan.settle.data.db.FolderSummary
import com.iltan.settle.domain.backup.BackupPause
import com.iltan.settle.domain.backup.HOUR_MILLIS
import com.iltan.settle.domain.transfer.NetworkUse
import com.iltan.settle.ui.folders.folderNames

/** The Backup part of Settings, wired to the app. */
@Composable
fun BackupSettings() {
    val context = LocalContext.current
    val graph = context.appGraph
    val viewModel = viewModel {
        BackupSettingsViewModel(
            dao = graph.database.backupDao(),
            library = graph.library,
            preferences = graph.backupPreferences,
            scheduler = graph.backupScheduler,
            cloudBackup = graph.cloudBackup,
            notifications = graph.backupNotifications,
            canReadOriginals = graph::canReadOriginals,
        )
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    var system by remember { mutableStateOf(SystemState.of(context)) }
    // The user comes back from the system's settings pages with things changed.
    LifecycleResumeEffect(Unit) {
        system = SystemState.of(context)
        viewModel.refresh()
        onPauseOrDispose {}
    }
    val askNotifications =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            system = SystemState.of(context)
        }
    val askLocation =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            viewModel.refresh()
        }
    BackupSection(
        state = state,
        system = system,
        debugOptions = graph.debuggable,
        actions =
            BackupActions(
                setAutomatic = viewModel::setAutomatic,
                setGrace = viewModel::setGrace,
                setOnlyWhileCharging = viewModel::setOnlyWhileCharging,
                setUseMobileData = viewModel::setUseMobileData,
                setNetworkUse = viewModel::setNetworkUse,
                setPaused = viewModel::setPaused,
                setFolderBackUp = viewModel::setFolderBackUp,
                backUpEverythingNow = viewModel::backUpEverythingNow,
                retryAfterFull = viewModel::retryAfterFull,
                allowNotifications = {
                    askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                },
                allowLocation = { askLocation.launch(Manifest.permission.ACCESS_MEDIA_LOCATION) },
                openAppInfo = { openAppInfo(context) },
                openNeverSleeping = { openNeverSleeping(context) },
            ),
    )
}

/** What the phone says about Settle's background limits and notifications. */
data class SystemState(
    val notificationsAllowed: Boolean = true,
    /** "Unrestricted" battery use; otherwise Android may hold jobs back. */
    val batteryUnrestricted: Boolean = true,
    /** "Restricted" battery use: jobs don't run at all. */
    val backgroundRestricted: Boolean = false,
    val samsung: Boolean = false,
) {
    companion object {
        fun of(context: Context) =
            SystemState(
                notificationsAllowed =
                    context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                        PackageManager.PERMISSION_GRANTED,
                batteryUnrestricted =
                    context
                        .getSystemService(PowerManager::class.java)
                        .isIgnoringBatteryOptimizations(context.packageName),
                backgroundRestricted =
                    context.getSystemService(ActivityManager::class.java).isBackgroundRestricted,
                samsung = Build.MANUFACTURER.equals("samsung", ignoreCase = true),
            )
    }
}

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
    val allowNotifications: () -> Unit = {},
    val allowLocation: () -> Unit = {},
    val openAppInfo: () -> Unit = {},
    val openNeverSleeping: () -> Unit = {},
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

@Composable
fun BackupSection(
    state: BackupSettingsState,
    system: SystemState,
    debugOptions: Boolean,
    actions: BackupActions,
) {
    if (!state.loaded) return
    Status(state, actions)
    if (state.pause == BackupPause.NOT_CONNECTED) return

    val settings = state.choices.settings
    if (state.pause == null && state.queued + state.waiting > 0) {
        ListItem(
            onClick = actions.backUpEverythingNow,
            leadingContent = { Icon(painterResource(R.drawable.ic_cloud_upload), null) },
            content = { Text(stringResource(R.string.backup_everything_now)) },
            supportingContent = { Text(stringResource(R.string.backup_everything_now_text)) },
        )
    }
    SwitchItem(
        R.string.backup_automatic,
        R.string.backup_automatic_text,
        settings.automatic,
        actions.setAutomatic,
    )
    if (settings.automatic) {
        var choosingGrace by rememberSaveable { mutableStateOf(false) }
        ListItem(
            onClick = { choosingGrace = true },
            content = { Text(stringResource(R.string.backup_grace)) },
            supportingContent = { Text(graceLabel(settings.graceMillis)) },
        )
        if (choosingGrace) {
            ChoiceDialog(
                title = R.string.backup_grace,
                options = graceOptions(debugOptions),
                selected = settings.graceMillis,
                label = { graceLabel(it) },
                onChoose = actions.setGrace,
                onDismiss = { choosingGrace = false },
            )
        }
        SwitchItem(
            R.string.backup_only_charging,
            R.string.backup_only_charging_text,
            settings.onlyWhileCharging,
            actions.setOnlyWhileCharging,
        )
    }
    SwitchItem(
        R.string.backup_mobile_data,
        R.string.backup_mobile_data_text,
        settings.useMobileData,
        actions.setUseMobileData,
    )
    NetworkUseItem(state.choices.networkUse, actions.setNetworkUse)
    FolderChoices(state.folders, actions.setFolderBackUp)
    // While paused, the status line above offers Resume.
    if (!settings.paused) {
        ListItem(
            onClick = { actions.setPaused(true) },
            content = { Text(stringResource(R.string.backup_pause)) },
        )
    }
    KeepRunning(system, actions)
}

@Composable
private fun Status(state: BackupSettingsState, actions: BackupActions) {
    val (text, action) =
        when (state.pause) {
            BackupPause.NOT_CONNECTED ->
                stringResource(R.string.backup_status_not_connected) to null
            BackupPause.NO_ORIGINAL_ACCESS ->
                stringResource(R.string.backup_needs_location) to
                    (R.string.backup_allow to actions.allowLocation)
            BackupPause.BY_USER ->
                stringResource(R.string.backup_status_paused) to
                    (R.string.backup_resume to { actions.setPaused(false) })
            BackupPause.AUTH_NEEDED -> stringResource(R.string.backup_status_reconnect) to null
            BackupPause.QUOTA_FULL ->
                stringResource(R.string.backup_problem_quota) to
                    (R.string.backup_try_again to actions.retryAfterFull)
            null -> summary(state) to null
        }
    ListItem(
        leadingContent = {
            Icon(
                painterResource(
                    if (state.pause == null) R.drawable.ic_cloud_done else R.drawable.ic_cloud_off
                ),
                null,
            )
        },
        content = { Text(text) },
        supportingContent =
            if (state.pause == null && state.needsAttention > 0) {
                {
                    Text(
                        pluralStringResource(
                            R.plurals.backup_needs_attention,
                            state.needsAttention,
                            state.needsAttention,
                        )
                    )
                }
            } else null,
        trailingContent =
            action?.let { (label, onClick) ->
                { TextButton(onClick = onClick) { Text(stringResource(label)) } }
            },
    )
}

@Composable
private fun summary(state: BackupSettingsState): String =
    listOfNotNull(
            pluralStringResource(R.plurals.backup_count_backed_up, state.backedUp, state.backedUp),
            state.uploading
                .takeIf { it > 0 }
                ?.let { pluralStringResource(R.plurals.backup_count_uploading, it, it) },
            state.queued
                .takeIf { it > 0 }
                ?.let { pluralStringResource(R.plurals.backup_count_queued, it, it) },
            state.waiting
                .takeIf { it > 0 }
                ?.let { pluralStringResource(R.plurals.backup_count_waiting, it, it) },
        )
        .joinToString(" · ")

@Composable
private fun SwitchItem(title: Int, text: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        onClick = { onChange(!checked) },
        content = { Text(stringResource(title)) },
        supportingContent = { Text(stringResource(text)) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
    )
}

@Composable
private fun graceLabel(millis: Long): String =
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
private fun NetworkUseItem(use: NetworkUse, onChoose: (NetworkUse) -> Unit) {
    var choosing by rememberSaveable { mutableStateOf(false) }
    ListItem(
        onClick = { choosing = true },
        content = { Text(stringResource(R.string.backup_network)) },
        supportingContent = { Text(networkLabel(use)) },
    )
    if (choosing) {
        val options =
            listOf(NetworkUse.Polite) +
                LIMIT_OPTIONS_MBPS.map { NetworkUse.Limit(it * 1_000_000L / 8) } +
                NetworkUse.Unlimited
        ChoiceDialog(
            title = R.string.backup_network,
            options = options,
            selected = use,
            label = { networkLabel(it) },
            onChoose = onChoose,
            onDismiss = { choosing = false },
        )
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

@Composable
private fun <T> ChoiceDialog(
    title: Int,
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onChoose: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title)) },
        text = {
            Column(Modifier.selectableGroup()) {
                options.forEach { option ->
                    ListItem(
                        selected = option == selected,
                        onClick = {
                            onChoose(option)
                            onDismiss()
                        },
                        leadingContent = {
                            RadioButton(selected = option == selected, onClick = null)
                        },
                        content = { Text(label(option)) },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun FolderChoices(
    folders: List<FolderSummary>,
    onChange: (FolderSummary, Boolean) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val chosen = folders.count { it.backsUp }
    ListItem(
        onClick = { expanded = !expanded },
        content = { Text(stringResource(R.string.backup_folders)) },
        supportingContent = {
            Text(pluralStringResource(R.plurals.backup_folders_chosen, chosen, chosen))
        },
    )
    if (!expanded) return
    val rootName = stringResource(R.string.folder_root)
    val names =
        remember(folders, rootName) { folderNames(folders.map { it.relativePath }, rootName) }
    folders.forEach { folder ->
        ListItem(
            onClick = { onChange(folder, !folder.backsUp) },
            content = { Text(names[folder.relativePath].orEmpty()) },
            supportingContent = {
                Text(pluralStringResource(R.plurals.item_count, folder.itemCount, folder.itemCount))
            },
            trailingContent = { Switch(checked = folder.backsUp, onCheckedChange = null) },
        )
    }
}

/** One-time setup so the phone doesn't hold backup back (R5); shown only while it matters. */
@Composable
private fun KeepRunning(system: SystemState, actions: BackupActions) {
    if (!system.notificationsAllowed) {
        ListItem(
            onClick = actions.allowNotifications,
            content = { Text(stringResource(R.string.backup_allow_notifications)) },
            supportingContent = { Text(stringResource(R.string.backup_allow_notifications_text)) },
        )
    }
    if (system.backgroundRestricted || !system.batteryUnrestricted) {
        ListItem(
            onClick = actions.openAppInfo,
            content = { Text(stringResource(R.string.backup_battery)) },
            supportingContent = {
                Text(
                    stringResource(
                        if (system.backgroundRestricted) R.string.backup_battery_restricted
                        else R.string.backup_battery_text
                    )
                )
            },
        )
    }
    if (system.samsung) {
        ListItem(
            onClick = actions.openNeverSleeping,
            content = { Text(stringResource(R.string.backup_never_sleeping)) },
            supportingContent = { Text(stringResource(R.string.backup_never_sleeping_text)) },
        )
    }
}

private fun openAppInfo(context: Context) {
    context.startActivity(
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            "package:${context.packageName}".toUri(),
        )
    )
}

/**
 * Samsung's "Never sleeping apps" list, through the intent Samsung documents
 * (developer.samsung.com/mobile/app-management.html); App info if this One UI lacks it.
 */
private fun openNeverSleeping(context: Context) {
    try {
        context.startActivity(
            Intent("com.samsung.android.sm.ACTION_OPEN_CHECKABLE_LISTACTIVITY")
                .setPackage("com.samsung.android.lool")
                .putExtra("activity_type", 2)
        )
    } catch (e: ActivityNotFoundException) {
        openAppInfo(context)
    }
}
