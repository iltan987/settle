package com.iltan.settle.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.iltan.settle.R
import com.iltan.settle.data.system.SystemState
import com.iltan.settle.ui.chrome.RowButtonStyle
import com.iltan.settle.ui.chrome.RowTone
import com.iltan.settle.ui.chrome.SettingsRow
import com.iltan.settle.ui.chrome.StepList
import com.iltan.settle.ui.chrome.Trailing
import com.iltan.settle.ui.theme.SettleShape
import com.iltan.settle.ui.theme.cardColor
import com.iltan.settle.ui.theme.settleColors

/** A step that keeps backup running at night (A7, I10), in the order they are listed. */
enum class SetupStep {
    NOTIFICATIONS,
    BATTERY,
    /**
     * Samsung's Never sleeping apps, while battery use isn't Unrestricted. Android can't report the
     * list. An Unrestricted app is in the exempted standby bucket, which Samsung's sleeping can't
     * restrict, and One UI leaves such apps out of the list's picker (seen on the S24 FE, One UI
     * 9).
     */
    NEVER_SLEEPING,
}

/**
 * The steps Android still lacks. Samsung's only shows while battery use isn't Unrestricted:
 * Unrestricted covers it, and Samsung then won't offer Settle in that list at all.
 */
val SystemState.openSetupSteps: List<SetupStep>
    get() = buildList {
        val batteryOpen = backgroundRestricted || !batteryUnrestricted
        if (!notificationsAllowed) add(SetupStep.NOTIFICATIONS)
        if (batteryOpen) add(SetupStep.BATTERY)
        if (samsung && batteryOpen) add(SetupStep.NEVER_SLEEPING)
    }

/** What the setup steps open. */
class KeepRunningActions(
    val allowNotifications: () -> Unit = {},
    val openAppInfo: () -> Unit = {},
    /** Opens Samsung's Never sleeping apps (from the guide, A8). */
    val openNeverSleeping: () -> Unit = {},
)

/**
 * One setup step: what it is and why, with the button that does it, or checked once it's [done]. A
 * Samsung step's button opens the guide (A8) through [onGuide].
 */
@Composable
fun SetupStepRow(
    step: SetupStep,
    system: SystemState,
    done: Boolean,
    actions: KeepRunningActions,
    onGuide: () -> Unit,
    tone: RowTone = RowTone.PLAIN,
) {
    val allow = stringResource(R.string.backup_allow)
    val doneSub = stringResource(R.string.setup_done)
    val rowTone = if (done) RowTone.PLAIN else tone
    when (step) {
        SetupStep.NOTIFICATIONS ->
            SettingsRow(
                title = stringResource(R.string.setup_notifications),
                sub = if (done) doneSub else stringResource(R.string.setup_notifications_sub),
                icon = R.drawable.ic_notifications,
                tone = rowTone,
                trailing =
                    if (done) Trailing.Done
                    else Trailing.Action(allow, RowButtonStyle.TONAL, actions.allowNotifications),
            )
        SetupStep.BATTERY ->
            SettingsRow(
                title = stringResource(R.string.keep_running_battery),
                sub =
                    when {
                        done -> doneSub
                        system.backgroundRestricted ->
                            stringResource(R.string.backup_battery_restricted)
                        else -> stringResource(R.string.backup_battery_text)
                    },
                icon = R.drawable.ic_battery_charging_full,
                tone = rowTone,
                trailing =
                    if (done) Trailing.Done
                    else Trailing.Action(allow, RowButtonStyle.TONAL, actions.openAppInfo),
            )
        SetupStep.NEVER_SLEEPING ->
            SettingsRow(
                title = stringResource(R.string.keep_running_never_sleeping),
                sub =
                    if (done) doneSub else stringResource(R.string.keep_running_never_sleeping_sub),
                icon = R.drawable.ic_bedtime_off,
                trailing =
                    if (done) Trailing.Done
                    else {
                        Trailing.Action(
                            stringResource(R.string.action_open_settings),
                            RowButtonStyle.OUTLINE,
                            onGuide,
                        )
                    },
            )
    }
}

/**
 * How to add Settle to Samsung's Never sleeping apps (A8). Samsung offers no way to check or ask
 * for it, so the sheet explains the path, opens the setting and trusts "I've done this".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NeverSleepingGuide(onOpen: () -> Unit, onDone: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = SettleShape.Sheet,
        containerColor = MaterialTheme.cardColor,
        scrimColor = MaterialTheme.settleColors.scrim,
    ) {
        Column(
            Modifier.navigationBarsPadding().padding(start = 24.dp, end = 24.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                stringResource(R.string.guide_never_sleeping_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                stringResource(R.string.guide_never_sleeping_body),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            StepList(
                listOf(
                    stringResource(R.string.guide_step_battery),
                    stringResource(R.string.guide_step_limits),
                    stringResource(R.string.keep_running_never_sleeping),
                    stringResource(R.string.guide_step_add),
                )
            )
            Column(
                Modifier.padding(top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = onOpen, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.guide_open_battery))
                    Icon(
                        painterResource(R.drawable.ic_open_in_new),
                        contentDescription = null,
                        modifier = Modifier.padding(start = 8.dp).size(18.dp),
                    )
                }
                TextButton(
                    onClick = onDone,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    Text(stringResource(R.string.guide_done))
                }
            }
        }
    }
}
