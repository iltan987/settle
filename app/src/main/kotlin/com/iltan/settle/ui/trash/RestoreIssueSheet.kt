package com.iltan.settle.ui.trash

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.iltan.settle.R
import com.iltan.settle.data.db.TrashedEntry
import com.iltan.settle.domain.deletion.RestoreProblem
import com.iltan.settle.ui.theme.SettleShape
import com.iltan.settle.ui.theme.SettleType
import com.iltan.settle.ui.theme.cardColor
import com.iltan.settle.ui.theme.settleColors

/** What a stopped restore asks of the user (F7). */
class RestoreIssueHandlers(
    val signIn: () -> Unit = {},
    val openInDrive: (driveFileId: String) -> Unit = {},
    val freeUpSpace: () -> Unit = {},
)

/** A reason's icon, short label (also on the cell), and what it means. */
private data class Reason(val icon: Int, val label: Int, val body: Int)

private val RestoreProblem.reason: Reason
    get() =
        when (this) {
            RestoreProblem.AUTH_NEEDED ->
                Reason(
                    R.drawable.ic_account_circle,
                    R.string.restore_problem_auth,
                    R.string.restore_problem_auth_body,
                )
            RestoreProblem.OFFLINE ->
                Reason(
                    R.drawable.ic_wifi_off,
                    R.string.restore_problem_offline,
                    R.string.restore_problem_offline_body,
                )
            RestoreProblem.NOT_IN_DRIVE ->
                Reason(
                    R.drawable.ic_cloud_off,
                    R.string.restore_problem_not_in_drive,
                    R.string.restore_problem_not_in_drive_body,
                )
            RestoreProblem.CHECKSUM_MISMATCH ->
                Reason(
                    R.drawable.ic_difference,
                    R.string.restore_problem_mismatch,
                    R.string.restore_problem_mismatch_body,
                )
            RestoreProblem.NOT_WRITABLE ->
                Reason(
                    R.drawable.ic_storage,
                    R.string.restore_problem_no_space,
                    R.string.restore_problem_no_space_body,
                )
            RestoreProblem.FAILED ->
                Reason(
                    R.drawable.ic_error_filled,
                    R.string.restore_problem_failed,
                    R.string.restore_problem_failed_body,
                )
        }

/** A stopped restore's short label, for the cell's description. */
val RestoreProblem.label: Int
    get() = reason.label

/**
 * Why these weren't restored (RestoreIssueSheet, F7): only the reasons that apply, each with how
 * many items it stopped, what it means and what wasn't touched, and at most one action.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RestoreIssueSheet(
    failed: List<TrashedEntry>,
    handlers: RestoreIssueHandlers,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val byProblem = failed.groupBy { checkNotNull(it.restoreProblem) }.toSortedMap()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = SettleShape.Sheet,
        containerColor = MaterialTheme.cardColor,
        scrimColor = MaterialTheme.settleColors.scrim,
    ) {
        Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding()) {
            Text(
                stringResource(R.string.restore_issues_title),
                style = MaterialTheme.typography.titleLarge,
                modifier =
                    Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp).semantics {
                        heading()
                    },
            )
            byProblem.forEach { (problem, items) ->
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                val reason = problem.reason
                val body =
                    if (problem == RestoreProblem.NOT_WRITABLE) {
                        stringResource(
                            reason.body,
                            Formatter.formatShortFileSize(context, items.sumOf { it.sizeBytes }),
                        )
                    } else stringResource(reason.body)
                val action: Pair<Int, () -> Unit>? =
                    when (problem) {
                        RestoreProblem.AUTH_NEEDED -> R.string.action_sign_in to handlers.signIn
                        RestoreProblem.CHECKSUM_MISMATCH ->
                            items
                                .firstNotNullOfOrNull { it.driveFileId }
                                ?.let { id ->
                                    R.string.restore_open_in_drive to { handlers.openInDrive(id) }
                                }
                        RestoreProblem.NOT_WRITABLE ->
                            R.string.restore_free_up_space to handlers.freeUpSpace
                        else -> null
                    }
                ReasonRow(reason, items.size, body, action)
            }
        }
    }
}

@Composable
private fun ReasonRow(reason: Reason, count: Int, body: String, action: Pair<Int, () -> Unit>?) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth()
            .padding(
                start = 20.dp,
                end = 20.dp,
                top = 16.dp,
                bottom = if (action == null) 16.dp else 4.dp,
            ),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(
            painterResource(reason.icon),
            contentDescription = null,
            tint = colors.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row {
                Text(
                    stringResource(reason.label),
                    style = SettleType.itemTitle,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    count.toString(),
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.onSurfaceVariant,
                )
            }
            Text(body, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            if (action != null) {
                TextButton(
                    onClick = action.second,
                    contentPadding = PaddingValues(horizontal = 0.dp),
                ) {
                    Text(stringResource(action.first), style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}
