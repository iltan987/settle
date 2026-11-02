package com.iltan.settle.ui.trash

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.iltan.settle.R
import com.iltan.settle.data.db.TrashedEntry
import com.iltan.settle.data.media.MediaThumbnail
import com.iltan.settle.data.mediaContentUri
import com.iltan.settle.domain.CloudState
import com.iltan.settle.domain.scan.MediaKind
import com.iltan.settle.ui.grid.SelectionMarker
import com.iltan.settle.ui.grid.formatDuration
import com.iltan.settle.ui.theme.SettleMotion
import com.iltan.settle.ui.theme.SettleShape
import com.iltan.settle.ui.theme.SettleType
import com.iltan.settle.ui.theme.settleColors

/** What a trash cell shows over its photo, besides the selection. */
sealed interface TrashMark {
    /** Days left, in the warning colour when [soon] (F1). */
    data class DaysLeft(val days: Int, val soon: Boolean) : TrashMark

    /** Downloading from Drive, or waiting its turn with 0 (F5). */
    data class Restoring(val progress: Float) : TrashMark

    /** Just back on the phone (F5). */
    data object Restored : TrashMark

    /** Its restore stopped; the reasons sheet says why (F6, F7). */
    data object Failed : TrashMark

    /** No countdown: it waits for the user (F8). */
    data object Waiting : TrashMark
}

/** The mark [entry] gets now. */
fun trashMark(
    entry: TrashedEntry,
    nowMillis: Long,
    justRestored: Boolean,
    downloadingId: String?,
    fraction: Float,
): TrashMark =
    when {
        justRestored -> TrashMark.Restored
        entry.restoreProblem != null -> TrashMark.Failed
        entry.restoring -> TrashMark.Restoring(if (entry.id == downloadingId) fraction else 0f)
        entry.waitingReason != null -> TrashMark.Waiting
        else -> {
            val days = daysLeft(entry, nowMillis) ?: 0
            TrashMark.DaysLeft(days, days <= SOON_DAYS)
        }
    }

/**
 * One item in the trash: its photo, or a cloud when only Drive has it, with the duration or a
 * not-backed-up mark bottom-left and its [mark] (TrashCell, F1).
 */
@Composable
fun TrashCell(
    entry: TrashedEntry,
    mark: TrashMark,
    /** Null when nothing is being selected. */
    selected: Boolean?,
    description: String,
    /** Only for accessibility services; the grid handles touches itself. */
    onClick: () -> Unit,
) {
    val isSelected = selected == true
    val inset by animateDpAsState(if (isSelected) 8.dp else 0.dp, SettleMotion.select())
    val shownInset = inset.coerceAtLeast(0.dp)
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier.aspectRatio(1f)
            .clip(SettleShape.Cell)
            .background(if (isSelected) colors.primaryContainer else colors.surfaceContainerHigh)
            .semantics {
                contentDescription = description
                if (selected != null) this.selected = isSelected
                onClick { onClick().let { true } }
            }
            .testTag("trash-${entry.id}")
    ) {
        val mediaStoreId = entry.mediaStoreId
        if (mediaStoreId != null) {
            AsyncImage(
                model =
                    MediaThumbnail(
                        mediaContentUri(entry.kind, entry.volume, mediaStoreId),
                        entry.dateModifiedS,
                    ),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier =
                    Modifier.fillMaxSize()
                        .padding(shownInset)
                        .clip(RoundedCornerShape(shownInset * 0.75f)),
            )
        } else if (mark !is TrashMark.Restoring && mark != TrashMark.Restored) {
            // The phone no longer has the file; Drive does.
            Icon(
                painterResource(R.drawable.ic_cloud),
                contentDescription = null,
                tint = colors.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Center).size(28.dp),
            )
        }
        when {
            // Never backed up: this is the only copy anywhere.
            entry.cloudState != CloudState.VERIFIED ->
                Disc(Alignment.BottomStart, MaterialTheme.settleColors.photoBadge) {
                    Glyph(R.drawable.ic_cloud_off, Color.White)
                }
            entry.kind == MediaKind.VIDEO ->
                Text(
                    formatDuration(entry.durationMs),
                    style = SettleType.micro.copy(shadow = MarkerShadow),
                    color = Color.White,
                    modifier = Modifier.align(Alignment.BottomStart).padding(6.dp),
                )
        }
        Mark(mark)
        SelectionMarker(selected)
    }
}

@Composable
private fun BoxScope.Mark(mark: TrashMark) {
    val colors = MaterialTheme.colorScheme
    when (mark) {
        is TrashMark.DaysLeft -> {
            val settle = MaterialTheme.settleColors
            Text(
                if (mark.days <= 0) stringResource(R.string.day_today)
                else pluralStringResource(R.plurals.trash_days_left, mark.days, mark.days),
                style =
                    SettleType.chipMicro.copy(
                        fontWeight = if (mark.soon) FontWeight.Bold else FontWeight.SemiBold
                    ),
                color = if (mark.soon) settle.onWarningContainer else Color.White,
                modifier =
                    Modifier.align(Alignment.BottomEnd)
                        .padding(4.dp)
                        .background(
                            if (mark.soon) settle.warningContainer else DaysScrim,
                            RoundedCornerShape(8.dp),
                        )
                        .padding(horizontal = 6.dp, vertical = 3.dp),
            )
        }
        is TrashMark.Restoring -> {
            Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.42f)))
            Box(Modifier.align(Alignment.Center).size(32.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.matchParentSize()) {
                    drawCircle(Color.White.copy(alpha = 0.3f))
                    drawArc(
                        Color.White,
                        -90f,
                        360f * mark.progress.coerceIn(0f, 1f),
                        useCenter = true,
                        topLeft = Offset.Zero,
                    )
                }
                Box(
                    Modifier.size(26.dp).background(Color.Black.copy(alpha = 0.55f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Glyph(R.drawable.ic_download, Color.White, size = 15)
                }
            }
        }
        TrashMark.Restored -> {
            Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.22f)))
            Icon(
                painterResource(R.drawable.ic_check_circle_filled),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.align(Alignment.Center).size(28.dp),
            )
        }
        TrashMark.Failed -> {
            Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.4f)))
            Disc(Alignment.BottomEnd, colors.error) {
                Glyph(R.drawable.ic_priority_high, colors.onError, size = 13)
            }
        }
        TrashMark.Waiting -> {}
    }
}

@Composable
private fun BoxScope.Disc(alignment: Alignment, color: Color, content: @Composable () -> Unit) {
    Box(
        Modifier.align(alignment).padding(4.dp).size(18.dp).background(color, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

@Composable
private fun Glyph(icon: Int, tint: Color, size: Int = 12) {
    Icon(
        painterResource(icon),
        contentDescription = null,
        tint = tint,
        modifier = Modifier.size(size.dp),
    )
}

private val DaysScrim = Color.Black.copy(alpha = 0.52f)
private val MarkerShadow = Shadow(Color.Black.copy(alpha = 0.6f), Offset(0f, 2f), blurRadius = 4f)
