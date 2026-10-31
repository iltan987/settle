package com.iltan.settle.ui.grid

import android.text.format.DateFormat
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
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.iltan.settle.R
import com.iltan.settle.data.db.MediaEntry
import com.iltan.settle.data.thumbnail
import com.iltan.settle.domain.CloudState
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

@Composable
fun MediaGrid(
    sections: List<DaySection<MediaEntry>>,
    onOpen: (MediaEntry) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    state: LazyGridState = rememberLazyGridState(),
) {
    val today = remember { LocalDate.now() }
    LazyVerticalGrid(
        columns = GridCells.Fixed(COLUMNS),
        state = state,
        modifier = modifier,
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(GAP),
        verticalArrangement = Arrangement.spacedBy(GAP),
    ) {
        for (section in sections) {
            stickyHeader(key = "day-${section.date}", contentType = "day") {
                DayHeader(section.date, today)
            }
            items(section.items, key = { it.id }, contentType = { "media" }) { entry ->
                MediaCell(entry, onClick = { onOpen(entry) })
            }
        }
    }
}

@Composable
private fun DayHeader(date: LocalDate, today: LocalDate) {
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
    Text(
        label,
        style = MaterialTheme.typography.titleSmall,
        modifier =
            Modifier.fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

@Composable
private fun MediaCell(entry: MediaEntry, onClick: () -> Unit) {
    Box(
        Modifier.aspectRatio(1f)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick)
    ) {
        AsyncImage(
            model = entry.thumbnail,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
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
        if (entry.cloudState == CloudState.VERIFIED) {
            Badge(Modifier.align(Alignment.TopStart)) {
                BadgeIcon(R.drawable.ic_cloud_done_filled, R.string.media_backed_up)
            }
        }
    }
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
