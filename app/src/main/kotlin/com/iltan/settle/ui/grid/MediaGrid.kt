package com.iltan.settle.ui.grid

import android.content.res.Resources
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.selection.triStateToggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import com.iltan.settle.R
import com.iltan.settle.data.db.MediaEntry
import com.iltan.settle.domain.ItemStatus
import com.iltan.settle.domain.backup.cellShowsStatus
import com.iltan.settle.domain.scan.MediaKind
import com.iltan.settle.domain.timeline.DaySection
import com.iltan.settle.ui.DayTitles
import com.iltan.settle.ui.LocalMediaFlight
import com.iltan.settle.ui.theme.SettleMotion
import com.iltan.settle.ui.theme.SettleType
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** What every photo grid shows: items grouped by day, newest first. */
data class MediaGridState(
    val sections: List<DaySection<MediaEntry>> = emptyList(),
    val count: Int = 0,
    /** True until the first scan has filled the library, so "empty" isn't shown too early. */
    val loading: Boolean = true,
    /** The density the user pinched to: 3 to 6 columns (B4). */
    val columns: Int = DEFAULT_COLUMNS,
)

const val DEFAULT_COLUMNS = 4
private const val MIN_COLUMNS = 3
private const val MAX_COLUMNS = 6

/** From 5 columns on, headers group by month and hearts are hidden (B4). */
private fun byMonth(columns: Int) = columns >= 5

/** A header or a cell, in the order the grid shows them. */
private sealed interface GridRow {
    val key: String
    /** The day (or a month's first day) the row belongs to, for the fast scroller's bubble. */
    val date: LocalDate
}

private data class HeaderRow(
    override val date: LocalDate,
    val month: Boolean,
    val ids: List<String>,
) : GridRow {
    override val key = (if (month) "month-" else "day-") + date
}

private data class CellRow(val entry: MediaEntry, override val date: LocalDate) : GridRow {
    override val key = entry.id
}

private fun rowsOf(sections: List<DaySection<MediaEntry>>, month: Boolean): List<GridRow> {
    val groups =
        if (!month) sections.map { it.date to it.items }
        else {
            sections
                .groupBy { it.date.withDayOfMonth(1) }
                .map { (first, days) -> first to days.flatMap { it.items } }
        }
    return groups.flatMap { (date, items) ->
        listOf(HeaderRow(date, month, items.map { it.id })) + items.map { CellRow(it, date) }
    }
}

/**
 * Photos and videos by day, or by month when dense (PhotoGrid). With [onSelectionChange] set, a
 * long-press starts selecting (dragging extends it), taps then toggle items, and a header selects
 * its whole day (R3, R12, C1–C3). With [onColumnsChange] set, a pinch steps through 3 to 6 columns,
 * keeping the item under the fingers in place (B4).
 */
@Composable
fun MediaGrid(
    sections: List<DaySection<MediaEntry>>,
    onOpen: (MediaEntry) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    state: LazyGridState = rememberLazyGridState(),
    columns: Int = DEFAULT_COLUMNS,
    onColumnsChange: ((Int) -> Unit)? = null,
    selection: Set<String> = emptySet(),
    onSelectionChange: ((Set<String>) -> Unit)? = null,
    /** Each item's backup status, or null when unknown; filtered here by where it may show. */
    backup: (MediaEntry) -> CellBackup? = { null },
    /** Whether Drive is connected: before that, "Not backed up" isn't badged (B12). */
    connected: Boolean = false,
    /** Shown above the first day, across the grid, e.g. notices that need the user. */
    header: (@Composable () -> Unit)? = null,
) {
    // The density shown, changed at once by a pinch and kept in step with the saved one.
    var shown by rememberSaveable { mutableIntStateOf(columns) }
    LaunchedEffect(columns) { shown = columns }
    val month = byMonth(shown)
    val rows = remember(sections, month) { rowsOf(sections, month) }
    val ids = remember(rows) { rows.mapNotNull { (it as? CellRow)?.entry?.id } }
    val byId = remember(rows) { rows.mapNotNull { (it as? CellRow)?.entry }.associateBy { it.id } }
    val currentSelection by rememberUpdatedState(selection)
    val selecting = selection.isNotEmpty()
    val haptics = LocalHapticFeedback.current
    val changeColumns by rememberUpdatedState(onColumnsChange)
    val flight = LocalMediaFlight.current
    val tap: (MediaEntry) -> Unit = { entry ->
        if (currentSelection.isNotEmpty() && onSelectionChange != null) {
            onSelectionChange(
                if (entry.id in currentSelection) currentSelection - entry.id
                else currentSelection + entry.id
            )
        } else {
            // Its cell joins the shared element before the viewer comes in.
            flight?.itemId = entry.id
            onOpen(entry)
        }
    }
    val today = remember { LocalDate.now() }
    val labels = rememberDateLabels(today)
    val scope = rememberCoroutineScope()
    val zoom = remember { Animatable(1f) }
    var origin by remember { mutableStateOf(TransformOrigin.Center) }
    // The item a pinch held on to, and where its line was, until the new density is laid out.
    var anchor by remember { mutableStateOf<Pair<String, Int>?>(null) }
    var toast by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(shown) {
        val (key, offset) = anchor ?: return@LaunchedEffect
        anchor = null
        val index = rows.indexOfFirst { it.key == key }
        if (index >= 0) {
            state.scrollToItem(index + if (header != null) 1 else 0)
            state.scrollBy(-offset.toFloat())
        }
    }
    // A notice arriving above the first day would be laid out off screen: the grid keeps the day it
    // showed in place. Someone at the top should see it, so the grid follows it up.
    val hasHeader = header != null
    LaunchedEffect(hasHeader) {
        if (
            hasHeader && state.firstVisibleItemIndex == 1 && state.firstVisibleItemScrollOffset == 0
        ) {
            state.scrollToItem(0)
        }
    }
    LaunchedEffect(toast) {
        if (toast != null) {
            delay(DENSITY_TOAST_MILLIS)
            toast = null
        }
    }
    val direction = LocalLayoutDirection.current
    val padding =
        PaddingValues(
            start = contentPadding.calculateStartPadding(direction) + SIDE_INSET,
            top = contentPadding.calculateTopPadding(),
            end = contentPadding.calculateEndPadding(direction) + SIDE_INSET,
            bottom = contentPadding.calculateBottomPadding(),
        )
    BoxWithConstraints(modifier) {
        val size = constraints
        val pinchStep by rememberUpdatedState { stepIn: Boolean, centroid: Offset ->
            val from = shown
            val next = from + if (stepIn) -1 else 1
            if (next !in MIN_COLUMNS..MAX_COLUMNS) return@rememberUpdatedState false
            anchor = itemAt(state, centroid)?.let { it.key.toString() to it.offset.y }
            origin =
                TransformOrigin(
                    centroid.x / size.maxWidth.coerceAtLeast(1),
                    centroid.y / size.maxHeight.coerceAtLeast(1),
                )
            scope.launch {
                zoom.snapTo(next.toFloat() / from)
                zoom.animateTo(1f, SettleMotion.density())
            }
            shown = next
            toast = next
            changeColumns?.invoke(next)
            true
        }
        // One handler for the grid's life: a new lambda would restart the pinch detector mid-pinch,
        // and it would step again.
        val onPinch = remember {
            { stepIn: Boolean, centroid: Offset -> pinchStep(stepIn, centroid) }
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(shown),
            state = state,
            modifier =
                Modifier.fillMaxSize()
                    // Outside the zoom layer: inside it, the layer's own scale animation would move
                    // the fingers' positions and read as more pinching.
                    .then(
                        if (onColumnsChange == null) Modifier
                        else Modifier.pinchColumns(size, onPinch)
                    )
                    .graphicsLayer {
                        scaleX = zoom.value
                        scaleY = zoom.value
                        transformOrigin = origin
                    }
                    .gridGestures(
                        state,
                        ids,
                        { currentSelection },
                        onTap = { id -> byId[id]?.let(tap) },
                        onSelectionChange = onSelectionChange,
                        haptics = haptics,
                    ),
            contentPadding = padding,
            horizontalArrangement = Arrangement.spacedBy(GAP),
            verticalArrangement = Arrangement.spacedBy(GAP),
        ) {
            if (header != null) {
                item(
                    key = "notices",
                    span = { GridItemSpan(maxLineSpan) },
                    contentType = "notices",
                ) {
                    header()
                }
            }
            items(
                rows,
                key = { it.key },
                span = { if (it is HeaderRow) GridItemSpan(maxLineSpan) else GridItemSpan(1) },
                contentType = { if (it is HeaderRow) "header" else "media" },
            ) { row ->
                when (row) {
                    is HeaderRow -> {
                        val chosen = if (selecting) row.ids.count { it in selection } else 0
                        GroupHeader(
                            label = labels.header(row),
                            count = row.ids.size,
                            chosen = if (selecting) chosen else null,
                            onToggle = {
                                onSelectionChange?.invoke(
                                    if (chosen == row.ids.size) selection - row.ids.toSet()
                                    else selection + row.ids
                                )
                            },
                            onLongPress =
                                onSelectionChange?.let { change ->
                                    {
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        change(currentSelection + row.ids)
                                    }
                                },
                        )
                    }
                    is CellRow -> {
                        val entry = row.entry
                        val status =
                            backup(entry)?.takeIf {
                                cellShowsStatus(it.status, connected, selecting)
                            }
                        GridCell(
                            entry,
                            selected = if (selecting) entry.id in selection else null,
                            backup = status,
                            showFavorite = !month,
                            description = labels.cell(entry, status),
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
        FastScroller(
            state,
            rows,
            offset = if (header != null) 1 else 0,
            top = padding.calculateTopPadding(),
            bottom = padding.calculateBottomPadding(),
            label = labels::month,
        )
        AnimatedVisibility(
            visible = toast != null,
            enter = fadeIn(SettleMotion.fade()),
            exit = fadeOut(SettleMotion.fade()),
            modifier =
                Modifier.align(Alignment.BottomCenter)
                    .padding(bottom = padding.calculateBottomPadding() + 16.dp),
        ) {
            DensityToast(toast ?: shown)
        }
    }
}

/** The item under [point], in the grid's own coordinates. */
private fun itemAt(state: LazyGridState, point: Offset) =
    state.layoutInfo.let { layout ->
        // Item offsets start inside the top content padding; viewportStartOffset is minus it.
        val inContent = point.round() + IntOffset(0, layout.viewportStartOffset)
        layout.visibleItemsInfo.firstOrNull { IntRect(it.offset, it.size).contains(inContent) }
    }

/**
 * Two-finger pinches, seen before the grid scrolls so they never also scroll it. [onStep] gets
 * whether the fingers spread (fewer, larger columns) and where they are, and says whether it acted.
 * One pinch makes at most one step; the next needs the fingers lifted, so a small pinch can't run
 * through several densities.
 */
private fun Modifier.pinchColumns(
    key: Any,
    onStep: (stepIn: Boolean, centroid: Offset) -> Boolean,
) =
    pointerInput(key) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            var zoom = 1f
            var stepped = false
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.changes.count { it.pressed } >= 2) {
                    event.changes.forEach { it.consume() }
                    if (!stepped) {
                        zoom *= event.calculateZoom()
                        val stepIn =
                            when {
                                zoom > PINCH_STEP -> true
                                zoom < 1 / PINCH_STEP -> false
                                else -> null
                            }
                        if (stepIn != null) stepped = onStep(stepIn, event.calculateCentroid())
                    }
                }
            } while (event.changes.any { it.pressed })
        }
    }

/** How far fingers spread or close for one step of density. */
private const val PINCH_STEP = 1.25f

private const val DENSITY_TOAST_MILLIS = 1_200L

@Composable
private fun DensityToast(columns: Int) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.92f),
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
    ) {
        Row(
            Modifier.height(36.dp).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(painterResource(R.drawable.ic_grid_view), null, Modifier.size(18.dp))
            Text(
                stringResource(
                    if (byMonth(columns)) R.string.density_by_month else R.string.density_by_day,
                    columns,
                ),
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

/**
 * A day's or a month's header (DayHeader), at least 44 dp. While selecting it is a three-state
 * control for its items, without changing height; otherwise a long-press selects them (C3).
 */
@Composable
private fun GroupHeader(
    label: String,
    count: Int,
    /** How many of its items are selected; null when nothing is being selected. */
    chosen: Int?,
    onToggle: () -> Unit,
    onLongPress: (() -> Unit)?,
) {
    val colors = MaterialTheme.colorScheme
    val toggle =
        when (chosen) {
            null -> null
            0 -> ToggleableState.Off
            count -> ToggleableState.On
            else -> ToggleableState.Indeterminate
        }
    val spoken =
        if (chosen != null && chosen > 0 && chosen < count) {
            stringResource(R.string.selection_day_partial, label, chosen, count)
        } else {
            pluralStringResource(R.plurals.selection_day_items, count, label, count)
        }
    Row(
        Modifier.fillMaxWidth()
            .heightIn(min = 44.dp)
            .then(
                if (toggle != null) {
                    Modifier.triStateToggleable(toggle, onClick = onToggle).semantics {
                        contentDescription = spoken
                    }
                } else if (onLongPress != null) {
                    Modifier.pointerInput(onLongPress) {
                        detectTapGestures(onLongPress = { onLongPress() })
                    }
                } else Modifier
            )
            .padding(start = 14.dp, top = 10.dp, end = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = SettleType.dayHeader,
            color = colors.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (toggle != null) {
            Box(Modifier.padding(end = 6.dp).size(24.dp), contentAlignment = Alignment.Center) {
                Icon(
                    painterResource(
                        when (toggle) {
                            ToggleableState.On -> R.drawable.ic_check_circle_filled
                            ToggleableState.Indeterminate -> R.drawable.ic_do_not_disturb_on_filled
                            ToggleableState.Off -> R.drawable.ic_radio_button_unchecked
                        }
                    ),
                    contentDescription = null,
                    tint =
                        if (toggle == ToggleableState.Off) colors.onSurfaceVariant
                        else colors.primary,
                )
            }
        }
    }
}

/** Headers' and cells' words, formatted once per locale. */
private class DateLabels(
    private val today: LocalDate,
    private val resources: Resources,
    locale: Locale,
) {
    private val days = DayTitles(resources, locale)
    private val monthYear = DayTitles.pattern(locale, "MMMMyyyy")
    private val cellDate = DayTitles.pattern(locale, "dMMMMjm")
    private val zone = ZoneId.systemDefault()

    fun header(row: HeaderRow): String =
        if (row.month) monthYear.format(row.date) else days.of(row.date, today)

    fun month(date: LocalDate): String = monthYear.format(date)

    /** "Photo, Favorite, 29 October, 18:42, Waiting for backup" (B5). */
    fun cell(entry: MediaEntry, backup: CellBackup?): String = buildList {
        add(
            resources.getString(
                when {
                    entry.kind == MediaKind.VIDEO -> R.string.media_video
                    entry.specialFormat == SPECIAL_FORMAT_MOTION_PHOTO ->
                        R.string.media_motion_photo
                    else -> R.string.media_photo
                }
            )
        )
        if (entry.isFavorite) add(resources.getString(R.string.media_favorite))
        add(cellDate.format(Instant.ofEpochMilli(entry.takenAtMs).atZone(zone)))
        statusLabel(backup)?.let(::add)
    }
        .joinToString(", ")

    private fun statusLabel(backup: CellBackup?): String? =
        when (backup?.status) {
            ItemStatus.UPLOADING ->
                resources.getString(
                    R.string.cell_uploading,
                    ((backup.progress ?: 0f) * 100).roundToInt(),
                )
            ItemStatus.WAITING -> resources.getString(R.string.cell_waiting)
            ItemStatus.QUEUED -> resources.getString(R.string.cell_queued)
            ItemStatus.NOT_BACKED_UP -> resources.getString(R.string.cell_not_backed_up)
            ItemStatus.NEEDS_ATTENTION -> resources.getString(R.string.media_needs_attention)
            else -> null
        }
}

@Composable
private fun rememberDateLabels(today: LocalDate): DateLabels {
    val configuration = LocalConfiguration.current
    val resources = LocalResources.current
    return remember(today, configuration, resources) {
        DateLabels(today, resources, configuration.locales[0])
    }
}

/**
 * The draggable thumb on the right edge (FastScroller): shown while the grid scrolls and for 1.5 s
 * after, only when there are several screens to scroll through. Its position maps to item index, so
 * dense months take more of the rail; while dragging, a bubble names the month.
 */
@Composable
private fun BoxScope.FastScroller(
    state: LazyGridState,
    rows: List<GridRow>,
    /** Grid items before [rows], such as the notices. */
    offset: Int,
    top: Dp,
    bottom: Dp,
    label: (LocalDate) -> String,
) {
    // Derived, so the scroller recomposes when the first visible item changes, not every frame.
    val geometry by
        remember(state) {
            derivedStateOf {
                val layout = state.layoutInfo
                ScrollGeometry(
                    total = layout.totalItemsCount,
                    visible = layout.visibleItemsInfo.size,
                    first = state.firstVisibleItemIndex,
                    viewport = layout.viewportSize.height,
                )
            }
        }
    val total = geometry.total
    val visibleCount = geometry.visible
    if (total == 0 || visibleCount == 0 || total < visibleCount * MIN_SCREENS) return
    val haptics = LocalHapticFeedback.current
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    var shown by remember { mutableStateOf(false) }
    val scrolling = state.isScrollInProgress
    LaunchedEffect(scrolling, dragging) {
        if (scrolling || dragging) shown = true
        else {
            delay(FAST_SCROLL_HIDE_MILLIS)
            shown = false
        }
    }
    val fraction =
        if (dragging) dragFraction
        else (geometry.first.toFloat() / (total - visibleCount).coerceAtLeast(1)).coerceIn(0f, 1f)
    val currentFraction by rememberUpdatedState(fraction)
    val density = LocalDensity.current
    val track =
        with(density) {
            (geometry.viewport -
                    top.roundToPx() -
                    bottom.roundToPx() -
                    THUMB_TOUCH_HEIGHT.roundToPx())
                .coerceAtLeast(1)
        }
    val thumbY = with(density) { top.roundToPx() } + (fraction * track).roundToInt()
    var lastMonth by remember { mutableStateOf<LocalDate?>(null) }
    val atIndex = (fraction * (total - 1)).roundToInt() - offset
    val month =
        rows
            .getOrNull(atIndex.coerceIn(0, (rows.size - 1).coerceAtLeast(0)))
            ?.date
            ?.withDayOfMonth(1)
    LaunchedEffect(month, dragging) {
        if (dragging && month != null && lastMonth != null && month != lastMonth) {
            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
        }
        lastMonth = month
    }
    AnimatedVisibility(
        visible = shown,
        enter = fadeIn(SettleMotion.fade()),
        exit = fadeOut(SettleMotion.fade()),
        modifier = Modifier.align(Alignment.TopEnd).offset { IntOffset(0, thumbY) },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (dragging && month != null) {
                Surface(
                    shape = RoundedCornerShape(26.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(end = 8.dp),
                ) {
                    Box(
                        Modifier.height(52.dp).padding(horizontal = 20.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(label(month), style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
            Box(
                Modifier.size(THUMB_TOUCH_WIDTH, THUMB_TOUCH_HEIGHT).pointerInput(total, track) {
                    detectVerticalDragGestures(
                        onDragStart = {
                            dragFraction = currentFraction
                            dragging = true
                        },
                        onDragEnd = { dragging = false },
                        onDragCancel = { dragging = false },
                    ) { change, amount ->
                        change.consume()
                        dragFraction = (dragFraction + amount / track).coerceIn(0f, 1f)
                        state.requestScrollToItem((dragFraction * (total - 1)).roundToInt())
                    }
                },
                contentAlignment = Alignment.CenterEnd,
            ) {
                Box(
                    Modifier.size(40.dp, 52.dp)
                        .background(
                            MaterialTheme.colorScheme.primaryContainer,
                            RoundedCornerShape(topStart = 26.dp, bottomStart = 26.dp),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painterResource(R.drawable.ic_unfold_more),
                        contentDescription = stringResource(R.string.fast_scroll),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
        }
    }
}

private data class ScrollGeometry(
    val total: Int,
    val visible: Int,
    val first: Int,
    val viewport: Int,
)

/** The fast scroller appears only with at least this many screens of items. */
private const val MIN_SCREENS = 3
private const val FAST_SCROLL_HIDE_MILLIS = 1_500L
private val THUMB_TOUCH_WIDTH = 48.dp
private val THUMB_TOUCH_HEIGHT = 64.dp

private val GAP = 2.dp
private val SIDE_INSET = 2.dp
