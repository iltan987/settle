package com.iltan.settle.ui.settings

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import com.iltan.settle.R
import com.iltan.settle.domain.deletion.ExternalDeletionPolicy
import com.iltan.settle.ui.chrome.RowButtonStyle
import com.iltan.settle.ui.chrome.SettingsGroup
import com.iltan.settle.ui.chrome.SettingsRadioRow
import com.iltan.settle.ui.chrome.SettingsRow
import com.iltan.settle.ui.chrome.Trailing

/** The policy's name, as the main page's Deletion row shows it. */
val ExternalDeletionPolicy.label: Int
    get() =
        when (this) {
            ExternalDeletionPolicy.MOVE_DRIVE_COPY_TO_TRASH -> R.string.deletion_policy_move
            ExternalDeletionPolicy.ASK -> R.string.deletion_policy_ask
        }

/**
 * Settings → Deletion (I9, R13, ADR-015): what happens to the Drive copy when a file is deleted in
 * another app, and Android's optional Media management access, whose row leaves once it's granted.
 */
@Composable
fun DeletionPage(
    policy: ExternalDeletionPolicy,
    canDeleteWithoutAsking: Boolean,
    onPolicy: (ExternalDeletionPolicy) -> Unit,
    onAllowDeletingWithoutAsking: () -> Unit,
) {
    SettingsGroup(
        title = stringResource(R.string.deletion_policy_title),
        lead = stringResource(R.string.deletion_policy_text),
        modifier = Modifier.selectableGroup(),
    ) {
        listOf(
                Triple(
                    ExternalDeletionPolicy.MOVE_DRIVE_COPY_TO_TRASH,
                    R.string.deletion_policy_move,
                    R.string.deletion_policy_move_sub,
                ),
                Triple(
                    ExternalDeletionPolicy.ASK,
                    R.string.deletion_policy_ask,
                    R.string.deletion_policy_ask_sub,
                ),
            )
            .forEach { (option, title, sub) ->
                row {
                    SettingsRadioRow(
                        title = stringResource(title),
                        sub = stringResource(sub),
                        selected = option == policy,
                        onClick = { onPolicy(option) },
                    )
                }
            }
    }
    SettingsGroup(
        title = stringResource(R.string.deletion_permission),
        note = stringResource(R.string.deletion_permission_note),
    ) {
        if (!canDeleteWithoutAsking) {
            row { MediaManagementRow(onAllowDeletingWithoutAsking) }
        }
    }
}

/** "Delete without asking each time", with Allow. */
@Composable
internal fun MediaManagementRow(onAllow: () -> Unit) {
    SettingsRow(
        title = stringResource(R.string.manage_media_title),
        sub = stringResource(R.string.manage_media_text),
        icon = R.drawable.ic_bolt,
        trailing =
            Trailing.Action(stringResource(R.string.backup_allow), RowButtonStyle.TONAL, onAllow),
    )
}

/** Android's Media management page for Settle (Special app access). */
internal fun openMediaManagement(context: Context) {
    context.startActivity(
        Intent(Settings.ACTION_REQUEST_MANAGE_MEDIA, "package:${context.packageName}".toUri())
    )
}
