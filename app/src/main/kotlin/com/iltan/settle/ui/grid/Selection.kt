package com.iltan.settle.ui.grid

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import androidx.compose.ui.unit.sp
import com.iltan.settle.R
import com.iltan.settle.data.db.MediaEntry
import com.iltan.settle.domain.CloudState
import com.iltan.settle.ui.LocalAppMessages
import com.iltan.settle.ui.chrome.BarAction
import com.iltan.settle.ui.chrome.BottomBarFrame
import com.iltan.settle.ui.chrome.TopBarHeight
import com.iltan.settle.ui.theme.SettleMotion
import com.iltan.settle.ui.theme.SettleShape
import com.iltan.settle.ui.theme.cardColor
import com.iltan.settle.ui.trash.DeleteRequest
import com.iltan.settle.ui.trash.ItemActions

/** The IDs of the selected items; survives rotation and process death. */
@Composable
fun rememberSelection(): MutableState<Set<String>> =
    rememberSaveable(saver = Saver({ it.value.toList() }, { mutableStateOf(it.toSet()) })) {
        mutableStateOf(emptySet())
    }

/**
 * Taps and selection gestures on a grid's items, handled by the grid itself so a long-press never
 * also counts as a tap on the item under it:
 * - a tap calls [onTap] with the item's key;
 * - a long-press starts selecting, and dragging on selects every item between the first and the one
 *   under the finger, or deselects them when the first was already selected.
 *
 * [ids] are the grid's item keys in display order; other keys (headers) are left to their own
 * handlers.
 */
fun Modifier.gridGestures(
    state: LazyGridState,
    ids: List<String>,
    selection: () -> Set<String>,
    onTap: (String) -> Unit,
    onSelectionChange: ((Set<String>) -> Unit)?,
    /** A buzz when selecting starts and a light tick for each item the drag adds. */
    haptics: HapticFeedback? = null,
): Modifier {
    val index = ids.withIndex().associate { (i, id) -> id to i }
    fun itemAt(point: Offset): Int? {
        val layout = state.layoutInfo
        // Item offsets start inside the top content padding; viewportStartOffset is minus it.
        val inContent = point.round() + IntOffset(0, layout.viewportStartOffset)
        return layout.visibleItemsInfo
            .firstOrNull { IntRect(it.offset, it.size).contains(inContent) }
            ?.key
            ?.let { index[it] }
    }
    // Set when this press became a long-press, so its release isn't also a tap.
    var longPressed = false
    // Built on a fresh Modifier: on the receiver it would repeat the whole chain before it.
    val taps =
        Modifier.pointerInput(ids) {
            detectTapGestures(
                onPress = { longPressed = false },
                onTap = { point -> if (!longPressed) itemAt(point)?.let { onTap(ids[it]) } },
            )
        }
    if (onSelectionChange == null) return then(taps)
    return then(taps).pointerInput(ids) {
        var anchor = -1
        var reached = -1
        var selecting = true
        var before = emptySet<String>()
        detectDragGesturesAfterLongPress(
            onDragStart = { point ->
                val start = itemAt(point) ?: return@detectDragGesturesAfterLongPress
                longPressed = true
                haptics?.performHapticFeedback(HapticFeedbackType.LongPress)
                anchor = start
                reached = start
                before = selection()
                selecting = ids[start] !in before
                onSelectionChange(if (selecting) before + ids[start] else before - ids[start])
            },
            onDragCancel = { anchor = -1 },
            onDragEnd = { anchor = -1 },
            onDrag = { change, _ ->
                if (anchor < 0) return@detectDragGesturesAfterLongPress
                val at = itemAt(change.position) ?: return@detectDragGesturesAfterLongPress
                if (at != reached) {
                    reached = at
                    haptics?.performHapticFeedback(HapticFeedbackType.SegmentTick)
                }
                val range = ids.subList(minOf(anchor, at), maxOf(anchor, at) + 1)
                onSelectionChange(if (selecting) before + range else before - range.toSet())
            },
        )
    }
}

/**
 * The top bar while items are selected (C1): a way out, how many, and Select all. It takes the
 * tab's top bar's place at the same height, so the grid doesn't move under the finger.
 */
@Composable
fun SelectionTopBar(count: Int, onClear: () -> Unit, onSelectAll: () -> Unit) {
    BackHandler(onBack = onClear)
    // A surface, so text and icons on it take the matching content colour in both themes.
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(Modifier.windowInsetsPadding(WindowInsets.statusBars)) {
            Row(
                Modifier.fillMaxWidth().height(TopBarHeight).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onClear) {
                    Icon(
                        painterResource(R.drawable.ic_close),
                        contentDescription = stringResource(R.string.selection_clear),
                    )
                }
                Text(
                    pluralStringResource(R.plurals.selected_count, count, count),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f).padding(start = 4.dp),
                )
                IconButton(onClick = onSelectAll) {
                    Icon(
                        painterResource(R.drawable.ic_select_all),
                        contentDescription = stringResource(R.string.selection_select_all),
                    )
                }
            }
        }
    }
}

/**
 * What the selected items can be given to (C1, C4), in the navigation bar's slot and height: share,
 * favourite, back up the ones not in Drive yet, and delete, rightmost and in error red. When some
 * aren't in Drive, a strip above says what Back up and Delete will do with them.
 */
@Composable
fun SelectionBar(
    count: Int,
    /** Selected items without a verified copy in Drive. */
    notInDrive: Int,
    allFavorite: Boolean,
    onShare: () -> Unit,
    onFavorite: () -> Unit,
    onBackUp: () -> Unit,
    onDelete: () -> Unit,
    /** Long-pressing Back up while there's nothing to back up says why. */
    onBackUpHint: () -> Unit,
) {
    Column {
        AnimatedVisibility(
            visible = notInDrive > 0,
            enter = fadeIn(SettleMotion.fade()),
            exit = fadeOut(SettleMotion.fade()),
        ) {
            InfoStrip(
                R.drawable.ic_cloud_off,
                pluralStringResource(
                    R.plurals.selection_not_in_drive,
                    notInDrive,
                    notInDrive,
                    count,
                ),
            )
        }
        BottomBarFrame {
            BarAction(R.drawable.ic_share, stringResource(R.string.viewer_share), onShare)
            BarAction(
                if (allFavorite) R.drawable.ic_favorite_filled else R.drawable.ic_favorite,
                stringResource(
                    if (allFavorite) R.string.selection_unfavorite else R.string.selection_favorite
                ),
                onFavorite,
            )
            BarAction(
                R.drawable.ic_cloud_upload,
                stringResource(R.string.selection_back_up),
                onBackUp,
                enabled = notInDrive > 0,
                badge = notInDrive.takeIf { it > 0 && it < count },
                onLongClick = if (notInDrive == 0) onBackUpHint else null,
            )
            BarAction(
                R.drawable.ic_delete,
                stringResource(R.string.action_delete),
                onDelete,
                destructive = true,
            )
        }
    }
}

/**
 * The selection bar acting on [chosen] through [actions]; [shown] is what the bar counts, which
 * stays the last selection while the bar fades out. [clear] ends selecting.
 */
@Composable
fun ItemSelectionBar(
    chosen: List<MediaEntry>,
    shown: List<MediaEntry>,
    actions: ItemActions,
    clear: () -> Unit,
) {
    val messages = LocalAppMessages.current
    val alreadyInDrive = stringResource(R.string.selection_already_in_drive)
    val allFavorite = shown.all { it.isFavorite }
    SelectionBar(
        count = shown.size,
        notInDrive = shown.count { it.cloudState != CloudState.VERIFIED },
        allFavorite = allFavorite,
        onShare = { actions.share(chosen) },
        onFavorite = { actions.favorite(chosen, !allFavorite) },
        onBackUp = {
            actions.backUp(chosen.filter { it.cloudState != CloudState.VERIFIED })
            clear()
        },
        onDelete = { actions.delete(DeleteRequest(chosen, onDeleted = clear)) },
        onBackUpHint = { messages.show(alreadyInDrive) },
    )
}

/** A floating note above a bottom bar (InfoStrip): the card surface, radius 16. */
@Composable
fun InfoStrip(icon: Int, text: String) {
    Surface(
        shape = SettleShape.Cover,
        color = MaterialTheme.cardColor,
        shadowElevation = 3.dp,
        modifier = Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, bottom = 8.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                painterResource(icon),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.5.sp),
                modifier = Modifier.weight(1f),
            )
        }
    }
}
