package com.iltan.settle.ui.settings

import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.iltan.settle.R
import com.iltan.settle.appGraph
import com.iltan.settle.domain.backup.BackupPause
import kotlinx.coroutines.flow.map

/** The Google Drive part of Settings, wired to the app. */
@Composable
fun DriveSettings(snackbar: SnackbarHostState) {
    val graph = LocalContext.current.appGraph
    val resources = LocalResources.current
    val viewModel = viewModel {
        DriveSettingsViewModel(
            dao = graph.database.backupDao(),
            auth = graph.driveAuth,
            drive = graph.drive,
            sandbox = graph.driveSandbox,
            folder = graph.driveRootFolder,
            onAccountChanged = { connected ->
                if (connected && graph.backupPreferences.unblock(BackupPause.AUTH_NEEDED)) {
                    graph.backupNotifications.clearPause()
                }
                graph.backupScheduler.reschedule()
            },
            needsReconnect =
                graph.backupPreferences.choices.map { it.blocked == BackupPause.AUTH_NEEDED },
        )
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val consent by viewModel.consent.collectAsStateWithLifecycle()
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
            viewModel.onConsentResult(it.resultCode, it.data)
        }
    LaunchedEffect(consent) {
        consent?.let {
            viewModel.consentLaunched()
            launcher.launch(IntentSenderRequest.Builder(it).build())
        }
    }
    LaunchedEffect(state.notice) {
        state.notice?.let {
            viewModel.noticeShown()
            snackbar.showSnackbar(resources.getString(it.message))
        }
    }
    DriveSection(
        state = state,
        onConnect = viewModel::connect,
        onDisconnect = viewModel::disconnect,
        onResetSandbox = viewModel::resetSandbox,
    )
}

@Composable
fun DriveSection(
    state: DriveSettingsState,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onResetSandbox: () -> Unit,
) {
    if (!state.loaded) return
    var confirmDisconnect by rememberSaveable { mutableStateOf(false) }
    var confirmReset by rememberSaveable { mutableStateOf(false) }

    if (state.email == null) {
        ListItem(
            leadingContent = { Icon(painterResource(R.drawable.ic_cloud_off), null) },
            content = { Text(stringResource(R.string.drive_not_connected)) },
            supportingContent = { Text(stringResource(R.string.drive_explainer)) },
        )
        Button(
            onClick = onConnect,
            enabled = !state.busy,
            modifier = Modifier.padding(start = 16.dp, bottom = 8.dp),
        ) {
            Text(stringResource(R.string.drive_connect))
        }
    } else {
        ListItem(
            leadingContent = { Icon(painterResource(R.drawable.ic_cloud_done), null) },
            content = { Text(state.email) },
            supportingContent = {
                Text(
                    storageText(state) + "\n" + stringResource(R.string.drive_folder, state.folder)
                )
            },
        )
        if (state.needsReconnect) {
            ListItem(content = { Text(stringResource(R.string.drive_reconnect_text)) })
            Button(
                onClick = onConnect,
                enabled = !state.busy,
                modifier = Modifier.padding(start = 16.dp, bottom = 8.dp),
            ) {
                Text(stringResource(R.string.drive_reconnect))
            }
        }
        ListItem(
            onClick = { confirmDisconnect = true },
            enabled = !state.busy,
            content = { Text(stringResource(R.string.drive_disconnect)) },
        )
    }
    if (state.sandbox) {
        ListItem(
            onClick = { confirmReset = true },
            enabled = !state.busy && state.email != null,
            content = { Text(stringResource(R.string.drive_sandbox_reset)) },
        )
    }

    if (confirmDisconnect) {
        Confirm(
            title = R.string.drive_disconnect_title,
            text = stringResource(R.string.drive_disconnect_text),
            action = R.string.drive_disconnect,
            onConfirm = onDisconnect,
            onDismiss = { confirmDisconnect = false },
        )
    }
    if (confirmReset) {
        Confirm(
            title = R.string.drive_sandbox_reset,
            text = stringResource(R.string.drive_sandbox_reset_text, state.folder),
            action = R.string.drive_sandbox_reset_action,
            onConfirm = onResetSandbox,
            onDismiss = { confirmReset = false },
        )
    }
}

@Composable
private fun storageText(state: DriveSettingsState): String {
    val context = LocalContext.current
    val about = state.about
    val usage = about?.usageBytes ?: return stringResource(R.string.drive_storage_unknown)
    val used = Formatter.formatShortFileSize(context, usage)
    val limit = about.limitBytes ?: return stringResource(R.string.drive_storage_unlimited, used)
    return stringResource(
        R.string.drive_storage,
        used,
        Formatter.formatShortFileSize(context, limit),
    )
}

@Composable
private fun Confirm(
    title: Int,
    text: String,
    action: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title)) },
        text = { Text(text) },
        confirmButton = {
            TextButton(
                onClick = {
                    onDismiss()
                    onConfirm()
                }
            ) {
                Text(stringResource(action))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

private val DriveNotice.message: Int
    get() =
        when (this) {
            DriveNotice.CONNECT_FAILED -> R.string.drive_connect_failed
            DriveNotice.SANDBOX_RESET -> R.string.drive_sandbox_reset_done
            DriveNotice.SANDBOX_RESET_FAILED -> R.string.drive_sandbox_reset_failed
        }
