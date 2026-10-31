package com.iltan.settle.ui.grid

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.round
import com.iltan.settle.R

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
    val taps =
        pointerInput(ids) {
            detectTapGestures(
                onPress = { longPressed = false },
                onTap = { point -> if (!longPressed) itemAt(point)?.let { onTap(ids[it]) } },
            )
        }
    if (onSelectionChange == null) return then(taps)
    return then(taps).pointerInput(ids) {
        var anchor = -1
        var selecting = true
        var before = emptySet<String>()
        detectDragGesturesAfterLongPress(
            onDragStart = { point ->
                val start = itemAt(point) ?: return@detectDragGesturesAfterLongPress
                longPressed = true
                anchor = start
                before = selection()
                selecting = ids[start] !in before
                onSelectionChange(if (selecting) before + ids[start] else before - ids[start])
            },
            onDragCancel = { anchor = -1 },
            onDragEnd = { anchor = -1 },
            onDrag = { change, _ ->
                if (anchor < 0) return@detectDragGesturesAfterLongPress
                val at = itemAt(change.position) ?: return@detectDragGesturesAfterLongPress
                val range = ids.subList(minOf(anchor, at), maxOf(anchor, at) + 1)
                onSelectionChange(if (selecting) before + range else before - range.toSet())
            },
        )
    }
}

/** The top bar while items are selected: how many, a way out, and what can be done with them. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectionTopBar(count: Int, onClear: () -> Unit, actions: @Composable () -> Unit) {
    BackHandler(onBack = onClear)
    TopAppBar(
        title = { Text(pluralStringResource(R.plurals.selected_count, count, count)) },
        navigationIcon = {
            IconButton(onClick = onClear) {
                Icon(
                    painterResource(R.drawable.ic_close),
                    contentDescription = stringResource(R.string.selection_clear),
                )
            }
        },
        actions = { actions() },
        colors =
            TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer
            ),
    )
}

/** An action in [SelectionTopBar]. */
@Composable
fun SelectionAction(icon: Int, label: Int, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(painterResource(icon), contentDescription = stringResource(label))
    }
}
