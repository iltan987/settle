package com.iltan.settle.ui.viewer

import android.icu.text.DisplayContext
import android.icu.text.RelativeDateTimeFormatter
import android.icu.util.ULocale
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.iltan.settle.R
import com.iltan.settle.domain.backup.BackupPause
import com.iltan.settle.domain.backup.UploadProblem
import com.iltan.settle.ui.chrome.Tone
import com.iltan.settle.ui.theme.SettleShape
import com.iltan.settle.ui.theme.settleColors
import java.util.Locale
import kotlin.math.roundToInt

/** What the viewer's chip can do about an item's backup, besides opening Details (D4). */
enum class ChipAction(val label: Int) {
    BACK_UP(R.string.backup_now),
    TRY_AGAIN(R.string.backup_try_again),
    BACK_UP_FOLDER(R.string.viewer_back_up_folder),
    DETAILS(R.string.viewer_info),
    CONNECT(R.string.action_connect),
    SIGN_IN(R.string.action_sign_in),
    MANAGE(R.string.action_manage),
    RESUME(R.string.backup_resume),
    ALLOW(R.string.backup_allow),
}

/** The viewer's one-line backup status: always an icon (or ring) and text, at most one action. */
@Immutable
data class ViewerChip(
    val text: String,
    val tone: Tone,
    val icon: Int?,
    val action: ChipAction? = null,
    /** An upload's progress for the ring that replaces the icon; null while it starts. */
    val progress: Float? = null,
    val uploading: Boolean = false,
)

@Composable
fun viewerChip(
    status: CloudStatus,
    /** The item's folder, as the user knows it: "Screenshots isn't backed up". */
    folder: String,
    /** Whether automatic backup waits for the charger, which in practice means tonight. */
    chargingOnly: Boolean,
    nowMillis: Long,
): ViewerChip =
    when (status) {
        is CloudStatus.BackedUp ->
            ViewerChip(
                stringResource(R.string.viewer_backed_up),
                Tone.OK,
                R.drawable.ic_cloud_done_filled,
            )
        is CloudStatus.Uploading ->
            ViewerChip(
                status.fraction?.let {
                    stringResource(R.string.viewer_uploading_pct, (it * 100).roundToInt())
                } ?: stringResource(R.string.viewer_uploading),
                Tone.OK,
                icon = null,
                progress = status.fraction,
                uploading = true,
            )
        CloudStatus.Checking ->
            ViewerChip(
                stringResource(R.string.viewer_checking),
                Tone.INFO,
                R.drawable.ic_cloud_upload,
            )
        is CloudStatus.Waiting ->
            ViewerChip(
                stringResource(
                    R.string.viewer_backs_up_in,
                    inTime(status.untilMillis, nowMillis, LocalConfiguration.current.locales[0]),
                ),
                Tone.INFO,
                R.drawable.ic_schedule,
                ChipAction.BACK_UP,
            )
        CloudStatus.Queued ->
            ViewerChip(
                stringResource(
                    if (chargingOnly) R.string.viewer_backs_up_tonight
                    else R.string.viewer_backs_up_soon
                ),
                Tone.INFO,
                R.drawable.ic_cloud_upload,
                ChipAction.BACK_UP,
            )
        CloudStatus.NotChosen ->
            ViewerChip(
                stringResource(R.string.viewer_folder_not_backed_up, folder),
                Tone.INFO,
                R.drawable.ic_cloud_off,
                ChipAction.BACK_UP_FOLDER,
            )
        CloudStatus.NotBackedUp ->
            ViewerChip(
                stringResource(R.string.backup_not_backed_up),
                Tone.INFO,
                R.drawable.ic_cloud_off,
                ChipAction.BACK_UP,
            )
        CloudStatus.NotConnected ->
            ViewerChip(
                stringResource(R.string.viewer_not_connected),
                Tone.INFO,
                R.drawable.ic_cloud_off,
                ChipAction.CONNECT,
            )
        is CloudStatus.Problem ->
            ViewerChip(
                stringResource(status.problem.short),
                Tone.ERR,
                R.drawable.ic_error_filled,
                status.problem.action,
            )
        is CloudStatus.Paused -> pausedChip(status.reason)
    }

@Composable
private fun pausedChip(reason: BackupPause): ViewerChip {
    val (text, action) =
        when (reason) {
            BackupPause.AUTH_NEEDED -> R.string.viewer_paused_sign_in to ChipAction.SIGN_IN
            BackupPause.QUOTA_FULL -> R.string.viewer_paused_drive_full to ChipAction.MANAGE
            BackupPause.BY_USER -> R.string.status_paused_by_you to ChipAction.RESUME
            BackupPause.NO_ORIGINAL_ACCESS -> R.string.viewer_paused_location to ChipAction.ALLOW
            BackupPause.NOT_CONNECTED -> R.string.viewer_not_connected to ChipAction.CONNECT
        }
    return ViewerChip(stringResource(text), Tone.WARN, R.drawable.ic_pause_circle_filled, action)
}

/**
 * "in 5 hours", written to sit mid-sentence ("Backs up in 5 hours"), rounded to whole minutes,
 * hours or days; under a minute left reads "in 1 minute".
 */
fun inTime(untilMillis: Long, nowMillis: Long, locale: Locale): String {
    val minutes = ((untilMillis - nowMillis + MINUTE_MILLIS - 1) / MINUTE_MILLIS).coerceAtLeast(1)
    val (amount, unit) =
        when {
            minutes < 60 -> minutes.toDouble() to RelativeDateTimeFormatter.RelativeUnit.MINUTES
            minutes < 48 * 60 ->
                (minutes / 60.0).roundToInt().toDouble() to
                    RelativeDateTimeFormatter.RelativeUnit.HOURS
            else ->
                (minutes / (24 * 60.0)).roundToInt().toDouble() to
                    RelativeDateTimeFormatter.RelativeUnit.DAYS
        }
    return RelativeDateTimeFormatter.getInstance(
            ULocale.forLocale(locale),
            null,
            RelativeDateTimeFormatter.Style.LONG,
            DisplayContext.CAPITALIZATION_FOR_MIDDLE_OF_SENTENCE,
        )
        .format(amount, RelativeDateTimeFormatter.Direction.NEXT, unit)
}

private const val MINUTE_MILLIS = 60_000L

/** An upload problem in a few words for the chip; Details says more. */
private val UploadProblem.short: Int
    get() =
        when (this) {
            UploadProblem.AUTH_NEEDED -> R.string.viewer_paused_sign_in
            UploadProblem.QUOTA_FULL -> R.string.viewer_paused_drive_full
            UploadProblem.OFFLINE -> R.string.viewer_problem_offline
            UploadProblem.ORIGINAL_UNAVAILABLE -> R.string.viewer_problem_unreadable
            UploadProblem.CHANGED_DURING_UPLOAD -> R.string.viewer_problem_changed
            UploadProblem.CHECKSUM_MISMATCH -> R.string.viewer_problem_mismatch
            UploadProblem.FAILED -> R.string.viewer_problem_failed
        }

/** Only what the user can do now: an unreadable file needs Details' explanation first. */
private val UploadProblem.action: ChipAction
    get() =
        when (this) {
            UploadProblem.AUTH_NEEDED -> ChipAction.SIGN_IN
            UploadProblem.QUOTA_FULL -> ChipAction.MANAGE
            UploadProblem.ORIGINAL_UNAVAILABLE -> ChipAction.DETAILS
            UploadProblem.OFFLINE,
            UploadProblem.CHANGED_DURING_UPLOAD,
            UploadProblem.CHECKSUM_MISMATCH,
            UploadProblem.FAILED -> ChipAction.TRY_AGAIN
        }

/**
 * The chip on the stage (ViewerStatusChip): #202020 at 92%, the icon's colour a hint only. Tapping
 * it opens Details at the backup section; the action pill beside it does the one thing offered.
 * Both are 32 dp tall; Surface keeps their touch targets at 48 dp.
 */
@Composable
fun ViewerStatusChip(
    chip: ViewerChip,
    onClick: () -> Unit,
    onAction: (ChipAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val stage = MaterialTheme.settleColors
    Row(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            onClick = onClick,
            shape = SettleShape.Pill,
            color = stage.stageChrome,
            contentColor = stage.onStage,
        ) {
            Row(
                Modifier.height(32.dp).padding(start = 10.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val tint =
                    when (chip.tone) {
                        Tone.OK,
                        Tone.BUSY -> stage.stageOk
                        Tone.WARN -> stage.stageWarn
                        Tone.ERR -> stage.stageErr
                        Tone.INFO -> stage.onStage
                    }
                if (chip.uploading) {
                    val progress = chip.progress
                    if (progress == null) {
                        CircularProgressIndicator(
                            Modifier.size(16.dp),
                            color = tint,
                            strokeWidth = 2.dp,
                        )
                    } else {
                        CircularProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.size(16.dp),
                            color = tint,
                            trackColor = stage.onStage.copy(alpha = 0.24f),
                            strokeWidth = 2.dp,
                        )
                    }
                } else if (chip.icon != null) {
                    Icon(
                        painterResource(chip.icon),
                        contentDescription = null,
                        tint = tint,
                        modifier = Modifier.size(16.dp),
                    )
                }
                Text(
                    chip.text,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        chip.action?.let { action ->
            Surface(
                onClick = { onAction(action) },
                shape = SettleShape.Pill,
                color = stage.stageAction,
                contentColor = stage.onStageAction,
            ) {
                Row(
                    Modifier.height(32.dp).padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(action.label),
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
