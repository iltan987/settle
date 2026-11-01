package com.iltan.settle.ui.timeline

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.iltan.settle.R
import com.iltan.settle.data.db.MediaEntry
import com.iltan.settle.domain.CloudState
import com.iltan.settle.domain.backup.BackupHeadline
import com.iltan.settle.ui.LocalAppMessages
import com.iltan.settle.ui.chrome.AccountButton
import com.iltan.settle.ui.chrome.BottomBarHeight
import com.iltan.settle.ui.chrome.HideTabBar
import com.iltan.settle.ui.chrome.NoticeCard
import com.iltan.settle.ui.chrome.SettleTopBar
import com.iltan.settle.ui.chrome.StatusChip
import com.iltan.settle.ui.chrome.StatusPopoverCard
import com.iltan.settle.ui.chrome.TopBarHeight
import com.iltan.settle.ui.chrome.chipState
import com.iltan.settle.ui.grid.CellBackup
import com.iltan.settle.ui.grid.MediaGrid
import com.iltan.settle.ui.grid.MediaGridState
import com.iltan.settle.ui.grid.SelectionBar
import com.iltan.settle.ui.grid.SelectionTopBar
import com.iltan.settle.ui.grid.rememberSelection
import com.iltan.settle.ui.theme.SettleMotion
import com.iltan.settle.ui.theme.SettleShape
import com.iltan.settle.ui.trash.ItemActions

/** What the timeline's bars and notices say about backup. */
@Immutable
data class TimelineStatus(
    /** Null until backup's status is first read, so nothing claims "All in Drive" too early. */
    val headline: BackupHeadline? = null,
    /** The connected Google account; null before Drive is connected. */
    val accountEmail: String? = null,
    /** Files that vanished at once and wait for the user (R14); 0 when there are none. */
    val breakerCount: Int = 0,
    /** Keys of notices the user collapsed into the chip. */
    val dismissed: Set<String> = emptySet(),
)

/**
 * The Photos tab (B, C): the grid under floating bars. Notices scroll with the grid as its first
 * item; selecting swaps the bars for ones of the same height, so no row moves.
 */
@Composable
fun TimelineScreen(
    state: MediaGridState,
    onOpen: (MediaEntry) -> Unit,
    onOpenSettings: () -> Unit,
    /** Delete, back up, share and favourite for a selection; null turns selecting off. */
    actions: ItemActions? = null,
    status: TimelineStatus = TimelineStatus(),
    notices: NoticeHandlers = NoticeHandlers(),
    onOpenBackup: () -> Unit = {},
    /** Each item's backup status, or null while unknown. */
    backup: (MediaEntry) -> CellBackup? = { null },
    onColumnsChange: ((Int) -> Unit)? = null,
    onChooseFolders: () -> Unit = {},
) {
    var selection by rememberSelection()
    // Items deleted or gone elsewhere leave the selection.
    val visible = remember(state.sections) { state.sections.flatMap { it.items } }
    val chosen = remember(visible, selection) { visible.filter { it.id in selection } }
    val selecting = actions != null && chosen.isNotEmpty()
    // The bars fading out still show what was selected, not "0 selected".
    var lastChosen by remember { mutableStateOf(chosen) }
    SideEffect { if (chosen.isNotEmpty()) lastChosen = chosen }
    val barItems = chosen.ifEmpty { lastChosen }
    HideTabBar(selecting)

    val applicable = backupNotices(status.headline, status.breakerCount)
    val cards = applicable.filter { it.key == null || it.key !in status.dismissed }
    val connected = status.accountEmail != null
    val empty = state.sections.isEmpty() && !state.loading
    val messages = LocalAppMessages.current

    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + TopBarHeight
    val bottom =
        WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + BottomBarHeight
    val padding = PaddingValues(top = top, bottom = bottom)
    val gridState = rememberLazyGridState()

    val noticeCards: @Composable () -> Unit = {
        Column {
            for (notice in cards) {
                NoticeCard(
                    noticeContent(notice, notices, popover = false),
                    onDismiss =
                        if (notice.closable) ({ notice.key?.let(notices.dismiss) }) else null,
                )
            }
        }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Box(Modifier.fillMaxSize()) {
            when {
                state.sections.isNotEmpty() ->
                    MediaGrid(
                        sections = state.sections,
                        onOpen = onOpen,
                        contentPadding = padding,
                        state = gridState,
                        columns = state.columns,
                        onColumnsChange = onColumnsChange,
                        modifier = Modifier.fillMaxSize(),
                        selection = chosen.map { it.id }.toSet(),
                        onSelectionChange = if (actions != null) ({ selection = it }) else null,
                        backup = backup,
                        connected = connected,
                        header = if (cards.isNotEmpty()) noticeCards else null,
                    )
                state.loading -> Skeleton(padding)
                else ->
                    Column(
                        Modifier.fillMaxSize()
                            .padding(padding)
                            .verticalScroll(rememberScrollState())
                    ) {
                        noticeCards()
                        EmptyLibrary(onChooseFolders, Modifier.weight(1f, fill = false))
                    }
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
                        onSelectAll = { selection = visible.map { it.id }.toSet() },
                    )
                } else {
                    SettleTopBar(stringResource(R.string.timeline_title)) {
                        // Hidden while a card tells the same, and when there is nothing to report.
                        if (status.headline != null && cards.isEmpty() && !empty) {
                            StatusChipWithPopover(
                                status.headline,
                                applicable,
                                notices,
                                onOpenBackup,
                            )
                        }
                        AccountButton(status.accountEmail, onOpenSettings)
                    }
                }
            }

            AnimatedVisibility(
                visible = selecting,
                enter = fadeIn(SettleMotion.fade()),
                exit = fadeOut(SettleMotion.fade()),
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                if (actions != null) {
                    val alreadyInDrive = stringResource(R.string.selection_already_in_drive)
                    val allFavorite = barItems.all { it.isFavorite }
                    SelectionBar(
                        count = barItems.size,
                        notInDrive = barItems.count { it.cloudState != CloudState.VERIFIED },
                        allFavorite = allFavorite,
                        onShare = { actions.share(chosen) },
                        onFavorite = { actions.favorite(chosen, !allFavorite) },
                        onBackUp = {
                            actions.backUp(chosen.filter { it.cloudState != CloudState.VERIFIED })
                            selection = emptySet()
                        },
                        onDelete = { actions.delete(chosen) { selection = emptySet() } },
                        onBackUpHint = { messages.show(alreadyInDrive) },
                    )
                }
            }
        }
    }
}

/**
 * The chip: it opens the Backup tab when all is well, and explains a problem in a popover with its
 * fix when not (B8).
 */
@Composable
private fun StatusChipWithPopover(
    headline: BackupHeadline,
    applicable: List<BackupNotice>,
    on: NoticeHandlers,
    onOpenBackup: () -> Unit,
) {
    val chip = chipState(headline)
    val problem = applicable.firstOrNull { it.key != null }
    var open by remember { mutableStateOf(false) }
    Box {
        StatusChip(
            chip,
            onClick = { if (chip.isProblem && problem != null) open = true else onOpenBackup() },
        )
        if (open && problem != null) {
            Popup(
                popupPositionProvider = BelowAtEnd,
                onDismissRequest = { open = false },
                properties = PopupProperties(focusable = true),
            ) {
                // Whatever the popover offers, it closes once acted on.
                val close: (() -> Unit) -> () -> Unit = { action ->
                    {
                        open = false
                        action()
                    }
                }
                StatusPopoverCard(
                    noticeContent(
                        problem,
                        on.copy(
                            review = close(on.review),
                            connect = close(on.connect),
                            signIn = close(on.signIn),
                            manageStorage = close(on.manageStorage),
                            allowLocation = close(on.allowLocation),
                            resume = close(on.resume),
                            details = close(on.details),
                        ),
                        popover = true,
                    )
                )
            }
        }
    }
}

/** Under the chip, 12 dp from the screen's end. */
private object BelowAtEnd : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val margin = (windowSize.width * 0.03f).toInt()
        val x =
            if (layoutDirection == LayoutDirection.Ltr) {
                windowSize.width - popupContentSize.width - margin
            } else margin
        return IntOffset(x.coerceAtLeast(0), anchorBounds.bottom + margin / 2)
    }
}

/** c2 tiles in the grid's geometry, pulsing slowly while the library loads (B10). */
@Composable
private fun Skeleton(padding: PaddingValues) {
    val pulse by
        rememberInfiniteTransition(label = "skeleton")
            .animateFloat(
                initialValue = 0.6f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
                label = "pulse",
            )
    val loading = stringResource(R.string.timeline_loading)
    val tile = MaterialTheme.colorScheme.surfaceContainerHigh
    Column(
        Modifier.fillMaxSize()
            .padding(padding)
            .padding(horizontal = 2.dp)
            .graphicsLayer { alpha = pulse }
            .semantics { contentDescription = loading },
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for (count in listOf(3, 8, 8)) {
            Box(Modifier.height(44.dp).padding(start = 14.dp, top = 16.dp, bottom = 16.dp)) {
                Box(Modifier.width(96.dp).height(12.dp).background(tile, RoundedCornerShape(6.dp)))
            }
            for (row in 0 until (count + 3) / 4) {
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    for (column in 0 until 4) {
                        val filled = row * 4 + column < count
                        Box(
                            Modifier.weight(1f)
                                .aspectRatio(1f)
                                .then(
                                    if (filled) Modifier.background(tile, SettleShape.Cell)
                                    else Modifier
                                )
                        )
                    }
                }
            }
        }
    }
}

/** Nothing to show yet (B11): a 96 dp tile, a title, why, and where to choose folders. */
@Composable
private fun EmptyLibrary(onChooseFolders: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(96.dp)
                .background(MaterialTheme.colorScheme.surfaceContainer, SettleShape.EmptyTile),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painterResource(R.drawable.ic_photo_library),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(44.dp),
            )
        }
        Text(
            stringResource(R.string.timeline_empty_title),
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 20.dp),
        )
        Text(
            stringResource(R.string.timeline_empty_body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 300.dp).padding(top = 8.dp),
        )
        FilledTonalButton(onClick = onChooseFolders, modifier = Modifier.padding(top = 24.dp)) {
            Text(stringResource(R.string.timeline_choose_folders))
        }
    }
}
