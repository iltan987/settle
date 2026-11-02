package com.iltan.settle.ui.trash

import android.content.Context
import android.content.res.Resources
import android.text.format.Formatter
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.selection.triStateToggleable
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.iltan.settle.R
import com.iltan.settle.data.db.TrashedEntry
import com.iltan.settle.domain.CloudState
import com.iltan.settle.domain.deletion.RestoreProblem
import com.iltan.settle.domain.deletion.VanishedDecision
import com.iltan.settle.domain.scan.MediaKind
import com.iltan.settle.ui.DayTitles
import com.iltan.settle.ui.SnackbarAbove
import com.iltan.settle.ui.chrome.BackTopBar
import com.iltan.settle.ui.chrome.BarAction
import com.iltan.settle.ui.chrome.BottomBarFrame
import com.iltan.settle.ui.chrome.BottomBarHeight
import com.iltan.settle.ui.chrome.ConfirmDialog
import com.iltan.settle.ui.chrome.EmptyState
import com.iltan.settle.ui.chrome.NoticeAction
import com.iltan.settle.ui.chrome.NoticeCard
import com.iltan.settle.ui.chrome.NoticeContent
import com.iltan.settle.ui.chrome.Tone
import com.iltan.settle.ui.chrome.TopBarHeight
import com.iltan.settle.ui.folders.folderNames
import com.iltan.settle.ui.grid.SelectionTopBar
import com.iltan.settle.ui.grid.gridGestures
import com.iltan.settle.ui.grid.rememberSelection
import com.iltan.settle.ui.theme.SettleMotion
import com.iltan.settle.ui.theme.SettleShape
import com.iltan.settle.ui.theme.SettleType
import java.time.LocalDate
import java.util.Locale

/** A row of the trash's grid, in display order. */
private sealed interface TrashRow {
    val key: String
}

private data class HeaderRow(val group: TrashGroup) : TrashRow {
    override val key = "header-${group.key}"
}

private data class BannerRow(val icon: Int, val text: Int, val group: TrashGroup) : TrashRow {
    override val key = "banner-${group.key}"
}

private data class CellRow(val entry: TrashedEntry) : TrashRow {
    override val key = entry.id
}

private fun rowsOf(groups: List<TrashGroup>): List<TrashRow> = groups.flatMap { group ->
    val banner =
        when {
            group.items.any { it.restoring } ->
                BannerRow(R.drawable.ic_download, R.string.trash_banner_restoring, group)
            group.kind == GroupKind.ELSEWHERE ->
                BannerRow(R.drawable.ic_apps, R.string.trash_banner_elsewhere, group)
            group.kind == GroupKind.DISAPPEARED ->
                BannerRow(R.drawable.ic_hide_image, R.string.trash_banner_disappeared, group)
            else -> null
        }
    listOfNotNull(HeaderRow(group), banner) + group.items.map { CellRow(it) }
}

/** What the trash's confirmation deletes: these items, or the whole trash when [ids] is null. */
private data class ConfirmDelete(val ids: List<String>?) : java.io.Serializable

/**
 * Settle's trash (F, R12–R14): what was deleted here or elsewhere, grouped by when and how, newest
 * first, with how long each stays. Selecting offers Restore and Delete forever; items deleted in
 * another app while Settle asks first wait at the top for the user's answer.
 */
@Composable
fun TrashScreen(
    state: TrashState,
    nowMillis: Long,
    onBack: () -> Unit,
    actions: TrashActions,
    issues: RestoreIssueHandlers = RestoreIssueHandlers(),
) {
    var selection by rememberSelection()
    var confirm by rememberSaveable { mutableStateOf<ConfirmDelete?>(null) }
    var showIssues by rememberSaveable { mutableStateOf(false) }
    val entries = state.entries.filter { it.id !in state.justRestored }
    val chosen = remember(entries, selection) { entries.filter { it.id in selection } }
    val selecting = chosen.isNotEmpty()
    // The bars fading out still show what was selected, not "0 selected".
    var lastChosen by remember { mutableStateOf(chosen) }
    SideEffect { if (chosen.isNotEmpty()) lastChosen = chosen }
    val barItems = chosen.ifEmpty { lastChosen }

    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + TopBarHeight
    val bottom =
        WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + BottomBarHeight
    SnackbarAbove(if (selecting) BottomBarHeight + 12.dp else 12.dp)

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Box(Modifier.fillMaxSize()) {
            if (entries.isEmpty() && state.justRestored.isEmpty() && !state.loading) {
                EmptyState(
                    R.drawable.ic_delete,
                    stringResource(R.string.trash_empty_title),
                    stringResource(R.string.trash_empty_body),
                    Modifier.padding(top = top).align(Alignment.Center),
                )
            } else if (!state.loading) {
                TrashGrid(
                    state,
                    nowMillis,
                    PaddingValues(top = top, bottom = bottom),
                    selection = chosen.map { it.id }.toSet(),
                    onSelectionChange = { selection = it },
                    notices = {
                        Notices(
                            state,
                            onSeeWhy = { showIssues = true },
                            onDecide = { decision ->
                                actions.decide(
                                    state.waiting.map { it.id },
                                    state.waiting.size,
                                    decision,
                                    null,
                                )
                            },
                        )
                        if (state.inTrash > 0) {
                            TrashExplainer(onEmpty = { confirm = ConfirmDelete(null) })
                        }
                    },
                )
            }

            Crossfade(
                selecting,
                animationSpec = SettleMotion.fade(),
                modifier = Modifier.align(Alignment.TopCenter),
                label = "top bar",
            ) { selectingNow ->
                if (selectingNow) {
                    SelectionTopBar(
                        barItems.size,
                        onClear = { selection = emptySet() },
                        onSelectAll = { selection = entries.map { it.id }.toSet() },
                    )
                } else {
                    BackTopBar(stringResource(R.string.trash_title), onBack)
                }
            }

            AnimatedVisibility(
                visible = selecting,
                enter = fadeIn(SettleMotion.fade()),
                exit = fadeOut(SettleMotion.fade()),
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                TrashSelectionBar(
                    barItems,
                    onRestore = {
                        actions.restore(chosen.filter { it.inTrash })
                        selection = emptySet()
                    },
                    onDeleteForever = {
                        confirm = ConfirmDelete(chosen.filter { it.inTrash }.map { it.id })
                    },
                    onDecide = { decision ->
                        val waiting = chosen.filter { it.waitingReason != null }
                        actions.decide(waiting.map { it.id }, waiting.size, decision, null)
                        selection = emptySet()
                    },
                )
            }
        }
    }

    confirm?.let { request ->
        val targets =
            if (request.ids == null) entries.filter { it.inTrash }
            else entries.filter { it.id in request.ids }
        DeleteForeverDialog(
            targets,
            emptying = request.ids == null,
            onConfirm = {
                confirm = null
                if (request.ids == null) actions.emptyTrash() else actions.deleteForever(targets)
                selection = emptySet()
            },
            onDismiss = { confirm = null },
        )
    }
    if (showIssues && state.failed.isNotEmpty()) {
        RestoreIssueSheet(state.failed, issues, onDismiss = { showIssues = false })
    }
}

@Composable
private fun TrashGrid(
    state: TrashState,
    nowMillis: Long,
    padding: PaddingValues,
    selection: Set<String>,
    onSelectionChange: (Set<String>) -> Unit,
    notices: @Composable () -> Unit,
) {
    val rows = remember(state.groups) { rowsOf(state.groups) }
    val ids = remember(rows) { rows.mapNotNull { (it as? CellRow)?.entry?.id } }
    val current by rememberUpdatedState(selection)
    val selecting = selection.isNotEmpty()
    val haptics = LocalHapticFeedback.current
    val grid = rememberLazyGridState()
    val labels = rememberTrashLabels()
    val progress = state.progress
    LazyVerticalGrid(
        columns = GridCells.Fixed(COLUMNS),
        state = grid,
        contentPadding =
            PaddingValues(
                start = 2.dp,
                end = 2.dp,
                top = padding.calculateTopPadding(),
                bottom = padding.calculateBottomPadding(),
            ),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier =
            Modifier.fillMaxSize()
                .gridGestures(
                    grid,
                    ids,
                    { current },
                    onTap = { id ->
                        if (id !in state.justRestored) {
                            onSelectionChange(if (id in current) current - id else current + id)
                        }
                    },
                    onSelectionChange = onSelectionChange,
                    haptics = haptics,
                ),
    ) {
        item(key = "notices", span = { GridItemSpan(maxLineSpan) }, contentType = "notices") {
            Column { notices() }
        }
        items(
            rows,
            key = { it.key },
            span = { if (it is CellRow) GridItemSpan(1) else GridItemSpan(maxLineSpan) },
            contentType = { it::class.simpleName },
        ) { row ->
            when (row) {
                is HeaderRow -> {
                    val group = row.group
                    val groupIds = group.items.map { it.id }
                    val chosen = if (selecting) groupIds.count { it in selection } else null
                    GroupHeader(
                        title = labels.title(group),
                        sub = labels.sub(group),
                        meta = labels.meta(group, state.batch),
                        count = groupIds.size,
                        chosen = chosen,
                        onToggle = {
                            onSelectionChange(
                                if (chosen == groupIds.size) selection - groupIds.toSet()
                                else selection + groupIds
                            )
                        },
                        onLongPress = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onSelectionChange(current + groupIds)
                        },
                    )
                }
                is BannerRow -> Banner(row.icon, stringResource(row.text))
                is CellRow -> {
                    val entry = row.entry
                    val mark =
                        trashMark(
                            entry,
                            nowMillis,
                            justRestored = entry.id in state.justRestored,
                            downloadingId = progress?.itemId,
                            fraction = progress?.fraction ?: 0f,
                        )
                    TrashCell(
                        entry,
                        mark,
                        selected = if (selecting) entry.id in selection else null,
                        description = labels.cell(entry, mark),
                        onClick = {
                            onSelectionChange(
                                if (entry.id in current) current - entry.id else current + entry.id
                            )
                        },
                    )
                }
            }
        }
    }
}

/** A restore that stopped (F6), and items deleted elsewhere waiting for an answer (F8). */
@Composable
private fun Notices(
    state: TrashState,
    onSeeWhy: () -> Unit,
    onDecide: (VanishedDecision) -> Unit,
) {
    val failed = state.failed
    if (failed.isNotEmpty()) {
        NoticeCard(
            NoticeContent(
                Tone.ERR,
                R.drawable.ic_error_filled,
                pluralStringResource(R.plurals.restore_failed_title, failed.size, failed.size),
                failedBody(LocalResources.current, failed, state.lastOutcome),
                NoticeAction(stringResource(R.string.restore_see_why), onSeeWhy),
            )
        )
    }
    val waiting = state.waiting.size
    if (waiting > 0) {
        NoticeCard(
            NoticeContent(
                Tone.INFO,
                R.drawable.ic_help,
                pluralStringResource(R.plurals.waiting_title, waiting, waiting),
                pluralStringResource(R.plurals.waiting_body, waiting, waiting),
                NoticeAction(stringResource(R.string.keep_in_drive)) {
                    onDecide(VanishedDecision.KEEP_DRIVE_COPIES)
                },
                listOf(
                    NoticeAction(stringResource(R.string.move_drive_copies_to_trash)) {
                        onDecide(VanishedDecision.MOVE_DRIVE_COPIES_TO_TRASH)
                    }
                ),
            )
        )
    }
}

/** "2 others are back in Camera. The 2 left here are unchanged and still in Drive." (F6) */
private fun failedBody(
    resources: Resources,
    failed: List<TrashedEntry>,
    outcome: RestoreOutcome?,
): String {
    val back = outcome?.restored ?: 0
    val others =
        when {
            back == 0 -> null
            outcome?.folder != null -> {
                val root = resources.getString(R.string.folder_root)
                val folder = folderNames(listOf(outcome.folder), root).getValue(outcome.folder)
                resources.getQuantityString(R.plurals.restore_failed_others_to, back, back, folder)
            }
            else -> resources.getQuantityString(R.plurals.restore_failed_others, back, back)
        }
    // Drive may no longer have some of them, so "still in Drive" is said only when true.
    val left =
        resources.getQuantityString(
            if (failed.any { it.restoreProblem == RestoreProblem.NOT_IN_DRIVE }) {
                R.plurals.restore_failed_left
            } else R.plurals.restore_failed_left_in_drive,
            failed.size,
            failed.size,
        )
    return listOfNotNull(others, left).joinToString(" ")
}

/**
 * How long items stay, and Empty trash (TrashExplainer, F1): a c1 card, radius 24, with an outlined
 * error button.
 */
@Composable
private fun TrashExplainer(onEmpty: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = SettleShape.Notice,
        color = colors.surfaceContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Column(
            Modifier.padding(start = 16.dp, top = 14.dp, end = 8.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                Modifier.padding(end = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    painterResource(R.drawable.ic_auto_delete),
                    contentDescription = null,
                    tint = colors.onSurfaceVariant,
                    modifier = Modifier.size(22.dp),
                )
                Text(
                    stringResource(R.string.trash_explainer),
                    style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 20.sp),
                )
            }
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                OutlinedButton(
                    onClick = onEmpty,
                    border = BorderStroke(1.dp, colors.error),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.error),
                    contentPadding = PaddingValues(start = 14.dp, end = 16.dp),
                    modifier = Modifier.heightIn(min = 40.dp),
                ) {
                    Icon(
                        painterResource(R.drawable.ic_delete_forever),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        stringResource(R.string.trash_empty_action),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }
    }
}

/**
 * A group's header (F1): its title and date, and on the right its count or how its restore is
 * going. While selecting, a three-state control for its items takes the right side (F3).
 */
@Composable
private fun GroupHeader(
    title: String,
    sub: String?,
    meta: String,
    count: Int,
    chosen: Int?,
    onToggle: () -> Unit,
    onLongPress: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val toggle =
        when (chosen) {
            null -> null
            0 -> ToggleableState.Off
            count -> ToggleableState.On
            else -> ToggleableState.Indeterminate
        }
    val spoken = pluralStringResource(R.plurals.selection_day_items, count, title, count)
    Row(
        Modifier.fillMaxWidth()
            .heightIn(min = 44.dp)
            .then(
                if (toggle != null) {
                    Modifier.triStateToggleable(toggle, onClick = onToggle).semantics {
                        contentDescription = spoken
                    }
                } else {
                    Modifier.pointerLongPress(onLongPress)
                }
            )
            .padding(start = 14.dp, top = 10.dp, end = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(title, style = SettleType.dayHeader, color = colors.onSurface)
            if (sub != null) {
                Text(
                    sub,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                    color = colors.onSurfaceVariant,
                )
            }
        }
        if (toggle == null) {
            Text(
                meta,
                style = MaterialTheme.typography.labelMedium,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(end = 8.dp),
            )
        } else {
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

private fun Modifier.pointerLongPress(onLongPress: () -> Unit) =
    pointerInput(onLongPress) { detectTapGestures(onLongPress = { onLongPress() }) }

/** What a group is about, under its header: a c1 strip, radius 16 (F1, F5). */
@Composable
private fun Banner(icon: Int, text: String) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth()
            .padding(start = 8.dp, end = 8.dp, bottom = 8.dp)
            .background(colors.surfaceContainer, SettleShape.Cover)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            painterResource(icon),
            contentDescription = null,
            tint = colors.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text,
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, lineHeight = 19.sp),
            color = colors.onSurfaceVariant,
        )
    }
}

/**
 * Restore and Delete forever for the selection (F3), in the navigation bar's slot. Items waiting
 * for an answer get that answer's two choices instead (F8).
 */
@Composable
private fun TrashSelectionBar(
    items: List<TrashedEntry>,
    onRestore: () -> Unit,
    onDeleteForever: () -> Unit,
    onDecide: (VanishedDecision) -> Unit,
) {
    BottomBarFrame {
        if (items.all { it.waitingReason != null }) {
            BarAction(
                R.drawable.ic_cloud_done,
                stringResource(R.string.keep_in_drive),
                { onDecide(VanishedDecision.KEEP_DRIVE_COPIES) },
            )
            BarAction(
                R.drawable.ic_delete,
                stringResource(R.string.move_drive_copies_short),
                { onDecide(VanishedDecision.MOVE_DRIVE_COPIES_TO_TRASH) },
            )
        } else {
            BarAction(R.drawable.ic_restore, stringResource(R.string.trash_restore), onRestore)
            BarAction(
                R.drawable.ic_delete_forever,
                stringResource(R.string.trash_delete_forever),
                onDeleteForever,
                destructive = true,
            )
        }
    }
}

/**
 * Delete forever, or Empty trash, asked once more (F4): it can't be undone, and items never backed
 * up exist nowhere else, which is said.
 */
@Composable
private fun DeleteForeverDialog(
    targets: List<TrashedEntry>,
    emptying: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val count = targets.size
    val nowhereElse = targets.count { it.cloudState != CloudState.VERIFIED }
    ConfirmDialog(
        title =
            if (emptying) stringResource(R.string.trash_empty_confirm_title)
            else pluralStringResource(R.plurals.trash_confirm_title, count, count),
        text =
            listOfNotNull(
                if (emptying) pluralStringResource(R.plurals.trash_empty_confirm_text, count, count)
                else stringResource(R.string.trash_confirm_text),
                when (nowhereElse) {
                    0 -> null
                    // "It was never backed up": no number to repeat when it is all of them.
                    count ->
                        pluralStringResource(R.plurals.trash_confirm_none_backed_up, count, count)
                    else ->
                        pluralStringResource(
                            R.plurals.trash_confirm_nowhere_else,
                            nowhereElse,
                            nowhereElse,
                        )
                },
            ),
        confirm = stringResource(R.string.trash_delete_forever),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
        destructive = true,
    )
}

/** Headers' and cells' words, formatted once per locale. */
private class TrashLabels(
    private val resources: Resources,
    private val context: Context,
    locale: Locale,
    private val today: LocalDate,
) {
    private val dayMonth = DayTitles.pattern(locale, "dMMMM")
    private val dayMonthYear = DayTitles.pattern(locale, "dMMMMyyyy")
    private val shortDay = DayTitles.pattern(locale, "dMMM")

    fun title(group: TrashGroup): String =
        when (group.kind) {
            GroupKind.SETTLE -> {
                val day = group.day
                when {
                    day == null -> resources.getString(R.string.trash_group_deleted)
                    day == today -> resources.getString(R.string.trash_group_today)
                    day == today.minusDays(1) -> resources.getString(R.string.trash_group_yesterday)
                    else ->
                        resources.getString(
                            R.string.trash_group_day,
                            (if (day.year == today.year) dayMonth else dayMonthYear).format(day),
                        )
                }
            }
            GroupKind.ELSEWHERE,
            GroupKind.WAITING -> resources.getString(R.string.trash_deleted_elsewhere)
            GroupKind.DISAPPEARED -> resources.getString(R.string.trash_group_disappeared)
        }

    fun sub(group: TrashGroup): String? =
        when (group.kind) {
            GroupKind.SETTLE -> null
            GroupKind.WAITING -> resources.getString(R.string.trash_waiting)
            GroupKind.ELSEWHERE,
            GroupKind.DISAPPEARED -> group.day?.let(shortDay::format)
        }

    /** The count, how its restore is going (F5), or how many weren't restored (F6). */
    fun meta(group: TrashGroup, batch: RestoreBatch?): String {
        val restoring = group.items.filter { it.restoring }
        val failed = group.items.count { it.restoreProblem != null }
        return when {
            restoring.isNotEmpty() && batch != null ->
                resources.getString(
                    R.string.trash_restoring_meta,
                    minOf(batch.restored + 1, batch.total),
                    batch.total,
                    Formatter.formatShortFileSize(context, batch.bytesLeft),
                )
            failed > 0 -> resources.getQuantityString(R.plurals.trash_not_restored, failed, failed)
            else -> group.items.size.toString()
        }
    }

    /** "Photo, 28 days left", "Video, couldn't be restored: Phone is full". */
    fun cell(entry: TrashedEntry, mark: TrashMark): String = buildList {
        add(
            resources.getString(
                if (entry.kind == MediaKind.VIDEO) R.string.media_video else R.string.media_photo
            )
        )
        if (entry.cloudState != CloudState.VERIFIED) {
            add(resources.getString(R.string.cell_not_backed_up))
        }
        add(
            when (mark) {
                is TrashMark.DaysLeft ->
                    resources.getQuantityString(R.plurals.trash_cell_days, mark.days, mark.days)
                is TrashMark.Restoring -> resources.getString(R.string.trash_restoring)
                TrashMark.Restored -> resources.getString(R.string.trash_restored)
                TrashMark.Failed ->
                    resources.getString(
                        R.string.trash_cell_failed,
                        resources.getString(checkNotNull(entry.restoreProblem).label),
                    )
                TrashMark.Waiting -> resources.getString(R.string.trash_waiting)
            }
        )
    }
        .joinToString(", ")
}

@Composable
private fun rememberTrashLabels(): TrashLabels {
    val configuration = LocalConfiguration.current
    val resources = LocalResources.current
    val context = LocalContext.current
    val today = remember { LocalDate.now() }
    return remember(configuration, resources, today) {
        TrashLabels(resources, context, configuration.locales[0], today)
    }
}

private const val COLUMNS = 4
