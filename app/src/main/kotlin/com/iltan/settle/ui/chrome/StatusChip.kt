package com.iltan.settle.ui.chrome

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.iltan.settle.R
import com.iltan.settle.domain.backup.BackupHeadline
import com.iltan.settle.domain.backup.BackupPause
import com.iltan.settle.ui.theme.SettleShape
import com.iltan.settle.ui.theme.settleColors
import kotlin.math.roundToInt

/** How a status reads at a glance: fine, working, something to do, something wrong. */
enum class Tone {
    OK,
    BUSY,
    INFO,
    WARN,
    ERR,
}

/** The top bar's one-line backup status (B1–B8). */
@Immutable
data class ChipState(
    val text: String,
    val tone: Tone,
    val icon: Int? = null,
    /** The run's progress for the busy ring. */
    val progress: Float? = null,
    /** What TalkBack says instead of [text], if different. */
    val spoken: String? = null,
) {
    /** Whether tapping it explains a problem rather than opening the Backup tab. */
    val isProblem: Boolean
        get() = tone == Tone.WARN || tone == Tone.ERR
}

@Composable
fun chipState(headline: BackupHeadline): ChipState =
    when (headline) {
        is BackupHeadline.NeedsAttention ->
            ChipState(
                pluralStringResource(
                    R.plurals.status_needs_attention,
                    headline.count,
                    headline.count,
                ),
                Tone.ERR,
                R.drawable.ic_error_filled,
            )
        is BackupHeadline.Paused ->
            if (headline.reason == BackupPause.NOT_CONNECTED) {
                ChipState(
                    stringResource(R.string.status_drive_not_connected),
                    Tone.WARN,
                    R.drawable.ic_cloud_off_filled,
                )
            } else {
                ChipState(
                    stringResource(R.string.status_paused),
                    Tone.WARN,
                    R.drawable.ic_pause_circle_filled,
                )
            }
        is BackupHeadline.BackingUp ->
            ChipState(
                stringResource(
                    R.string.status_backing_up_pct,
                    (headline.run.fraction * 100).roundToInt(),
                ),
                Tone.BUSY,
                progress = headline.run.fraction,
                spoken =
                    pluralStringResource(
                        R.plurals.status_backing_up_n_of_m,
                        headline.run.totalItems,
                        headline.run.doneItems,
                        headline.run.totalItems,
                    ),
            )
        // Never "First backup tonight" when no chosen folder has anything to send.
        is BackupHeadline.FirstBackupPending ->
            if (headline.upcoming > 0) {
                ChipState(
                    stringResource(R.string.status_first_backup),
                    Tone.OK,
                    R.drawable.ic_schedule,
                )
            } else {
                ChipState(
                    stringResource(R.string.status_nothing_to_back_up),
                    Tone.OK,
                    R.drawable.ic_cloud_done_filled,
                )
            }
        is BackupHeadline.AllInDrive ->
            ChipState(
                stringResource(R.string.status_all_in_drive),
                Tone.OK,
                R.drawable.ic_cloud_done_filled,
            )
    }

/** The chip itself: 32 dp tall with a 48 dp touch target (TopBar). */
@Composable
fun StatusChip(state: ChipState, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val settle = MaterialTheme.settleColors
    val (container, content, iconTint) =
        when (state.tone) {
            Tone.WARN ->
                Triple(
                    settle.warningContainer,
                    settle.onWarningContainer,
                    settle.onWarningContainer,
                )
            Tone.ERR ->
                Triple(colors.errorContainer, colors.onErrorContainer, colors.onErrorContainer)
            else -> Triple(colors.surfaceContainer, colors.onSurface, colors.primary)
        }
    Surface(
        onClick = onClick,
        shape = SettleShape.Pill,
        color = container,
        contentColor = content,
        modifier = modifier.semantics { state.spoken?.let { contentDescription = it } },
    ) {
        Row(
            Modifier.height(32.dp).padding(start = 8.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (state.tone == Tone.BUSY) {
                ProgressRing(
                    state.progress ?: 0f,
                    colors.primary,
                    colors.surfaceContainerHighest,
                    hole = container,
                )
            } else if (state.icon != null) {
                Icon(
                    painterResource(state.icon),
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(18.dp),
                )
            }
            Text(
                state.text,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** An 18 dp ring filling clockwise from the top, with a 12 dp hole. */
@Composable
private fun ProgressRing(progress: Float, fill: Color, track: Color, hole: Color) {
    Canvas(Modifier.size(18.dp)) {
        drawCircle(track)
        drawArc(
            fill,
            startAngle = -90f,
            sweepAngle = 360f * progress.coerceIn(0f, 1f),
            useCenter = true,
            topLeft = Offset.Zero,
            size = Size(size.width, size.height),
        )
        drawCircle(hole, radius = 6.dp.toPx())
    }
}
