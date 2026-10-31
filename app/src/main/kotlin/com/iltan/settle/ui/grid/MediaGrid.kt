package com.iltan.settle.ui.grid

import android.text.format.DateFormat
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.iltan.settle.R
import com.iltan.settle.data.db.MediaEntry
import com.iltan.settle.data.thumbnail
import com.iltan.settle.domain.ItemStatus
import com.iltan.settle.domain.LocalState
import com.iltan.settle.domain.deriveStatus
import com.iltan.settle.domain.scan.MediaKind
import com.iltan.settle.domain.timeline.DaySection
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** MediaStore's `_special_format` value for motion photos (API 37). */
const val SPECIAL_FORMAT_MOTION_PHOTO = 2

/** What every photo grid shows: items grouped by day, newest first. */
data class MediaGridState(
    val sections: List<DaySection<MediaEntry>> = emptyList(),
    val count: Int = 0,
    /** True until the first scan has filled the library, so "empty" isn't shown too early. */
    val loading: Boolean = true,
)

/**
 * Photos and videos by day. With [onSelectionChange] set, a long-press starts selecting (dragging
 * extends it), taps then toggle items, and a day's header selects the whole day (R3, R12).
 */
@Composable
fun MediaGrid(
    sections: List<DaySection<MediaEntry>>,
    onOpen: (MediaEntry) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    state: LazyGridState = rememberLazyGridState(),
    selection: Set<String> = emptySet(),
    onSelectionChange: ((Set<String>) -> Unit)? = null,
    /** Shown above the first day, across the grid, e.g. a notice that needs the user. */
    header: (@Composable () -> Unit)? = null,
) {
    val today = remember { LocalDate.now() }
    val ids = remember(sections) { sections.flatMap { section -> section.items.map { it.id } } }
    val byId = remember(sections) { sections.flatMap { it.items }.associateBy { it.id } }
    val currentSelection by rememberUpdatedState(selection)
    val selecting = selection.isNotEmpty()
    val tap: (MediaEntry) -> Unit = { entry ->
        if (currentSelection.isNotEmpty() && onSelectionChange != null) {
            onSelectionChange(
                if (entry.id in currentSelection) currentSelection - entry.id
                else currentSelection + entry.id
            )
        } else onOpen(entry)
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(COLUMNS),
        state = state,
        modifier =
            modifier.gridGestures(
                state,
                ids,
                { currentSelection },
                onTap = { id -> byId[id]?.let(tap) },
                onSelectionChange = onSelectionChange,
            ),
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(GAP),
        verticalArrangement = Arrangement.spacedBy(GAP),
    ) {
        if (header != null) {
            item(key = "header", span = { GridItemSpan(maxLineSpan) }, contentType = "header") {
                header()
            }
        }
        for (section in sections) {
            stickyHeader(key = "day-${section.date}", contentType = "day") {
                val dayIds = section.items.map { it.id }
                val allSelected = selecting && selection.containsAll(dayIds)
                DayHeader(
                    section.date,
                    today,
                    selected = if (selecting) allSelected else null,
                    onToggle = {
                        onSelectionChange?.invoke(
                            if (allSelected) selection - dayIds.toSet() else selection + dayIds
                        )
                    },
                )
            }
            items(section.items, key = { it.id }, contentType = { "media" }) { entry ->
                MediaCell(
                    entry,
                    selected = if (selecting) entry.id in selection else null,
                    onClick = { tap(entry) },
                    onLongClick =
                        onSelectionChange?.let { change ->
                            { change(currentSelection + entry.id) }
                        },
                )
            }
        }
    }
}

@Composable
private fun DayHeader(
    date: LocalDate,
    today: LocalDate,
    /** Null when nothing is being selected. */
    selected: Boolean? = null,
    onToggle: () -> Unit = {},
) {
    val locale = LocalConfiguration.current.locales[0]
    val label =
        when (date) {
            today -> stringResource(R.string.day_today)
            today.minusDays(1) -> stringResource(R.string.day_yesterday)
            else -> {
                val skeleton = if (date.year == today.year) "EEEEdMMMM" else "EEEEdMMMMyyyy"
                remember(date, locale) {
                    DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, skeleton))
                        .withLocale(locale)
                        .format(date)
                }
            }
        }
    Row(
        Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .then(if (selected != null) Modifier.clickable(onClick = onToggle) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        if (selected != null) SelectionMark(selected, R.string.selection_day)
    }
}

@Composable
private fun MediaCell(
    entry: MediaEntry,
    /** Null when nothing is being selected. */
    selected: Boolean?,
    /** Only for accessibility services; the grid handles touches itself (see gridGestures). */
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
) {
    val inset by animateDpAsState(if (selected == true) 12.dp else 0.dp, label = "selected")
    Box(
        Modifier.aspectRatio(1f)
            .background(
                if (selected == true) MaterialTheme.colorScheme.secondaryContainer
                else MaterialTheme.colorScheme.surfaceContainerHigh
            )
            .semantics {
                onClick { onClick().let { true } }
                if (onLongClick != null) onLongClick { onLongClick().let { true } }
            }
            .testTag("media-${entry.id}")
    ) {
        AsyncImage(
            model = entry.thumbnail,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().padding(inset).clip(RoundedCornerShape(inset)),
        )
        if (entry.kind == MediaKind.VIDEO) {
            Badge(Modifier.align(Alignment.BottomEnd)) {
                Text(
                    formatDuration(entry.durationMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                )
                BadgeIcon(R.drawable.ic_play, R.string.media_video)
            }
        }
        if (entry.specialFormat == SPECIAL_FORMAT_MOTION_PHOTO) {
            Badge(Modifier.align(Alignment.TopEnd)) {
                BadgeIcon(R.drawable.ic_motion_photo, R.string.media_motion_photo)
            }
        }
        if (entry.isFavorite) {
            Badge(Modifier.align(Alignment.BottomStart)) {
                BadgeIcon(R.drawable.ic_favorite_filled, R.string.media_favorite)
            }
        }
        if (selected != null) {
            Box(Modifier.align(Alignment.TopStart).padding(4.dp)) {
                SelectionMark(selected, R.string.selection_item, onPhoto = true)
            }
        } else {
            StatusBadge(entry, Modifier.align(Alignment.TopStart))
        }
    }
}

/**
 * The item's one status (R11, ADR-002). Grids show items on the phone, so only backed up, uploading
 * and needs attention get a badge; waiting and queued are told in the viewer.
 */
@Composable
private fun StatusBadge(entry: MediaEntry, modifier: Modifier) {
    val status =
        deriveStatus(
            local = LocalState.PRESENT,
            cloud = entry.cloudState,
            inTrash = false,
            uploadEligibleAtMillis = null,
            nowMillis = 0,
        )
    val (icon, label) =
        when (status) {
            ItemStatus.BACKED_UP -> R.drawable.ic_cloud_done_filled to R.string.media_backed_up
            ItemStatus.UPLOADING -> R.drawable.ic_cloud_upload to R.string.backup_uploading
            ItemStatus.NEEDS_ATTENTION -> R.drawable.ic_cloud_off to R.string.media_needs_attention
            else -> return
        }
    Badge(modifier) { BadgeIcon(icon, label) }
}

/** A round check that shows whether an item or a day is selected. */
@Composable
fun SelectionMark(selected: Boolean, label: Int, onPhoto: Boolean = false) {
    Icon(
        painterResource(
            if (selected) R.drawable.ic_check_circle_filled
            else R.drawable.ic_radio_button_unchecked
        ),
        contentDescription = stringResource(label),
        tint =
            when {
                selected -> MaterialTheme.colorScheme.primary
                onPhoto -> Color.White
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
        modifier =
            Modifier.size(24.dp)
                .then(
                    if (onPhoto && selected) {
                        Modifier.background(MaterialTheme.colorScheme.onPrimary, CircleShape)
                    } else Modifier
                ),
    )
}

/** A small dark pill so white badges stay readable on any photo. */
@Composable
private fun Badge(modifier: Modifier, content: @Composable () -> Unit) {
    Row(
        modifier =
            modifier
                .padding(4.dp)
                .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(50))
                .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        content()
    }
}

@Composable
private fun BadgeIcon(icon: Int, description: Int) {
    Icon(
        painterResource(icon),
        contentDescription = stringResource(description),
        tint = Color.White,
        modifier = Modifier.size(14.dp),
    )
}

/** `0:07`, `12:34` or `1:02:03`. */
fun formatDuration(millis: Long): String {
    val total = millis / 1000
    val hours = total / 3600
    val minutes = total % 3600 / 60
    val seconds = total % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
    else "%d:%02d".format(minutes, seconds)
}

private const val COLUMNS = 4
private val GAP = 2.dp
