package com.iltan.settle.ui.trash

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.iltan.settle.R
import com.iltan.settle.data.db.TrashedEntry
import com.iltan.settle.data.db.VanishedEntry
import com.iltan.settle.data.media.MediaThumbnail
import com.iltan.settle.data.mediaContentUri
import com.iltan.settle.domain.deletion.RestoreProblem
import com.iltan.settle.domain.deletion.TrashOrigin
import com.iltan.settle.ui.grid.SelectionAction
import com.iltan.settle.ui.grid.SelectionMark
import com.iltan.settle.ui.grid.SelectionTopBar
import com.iltan.settle.ui.grid.gridGestures
import com.iltan.settle.ui.grid.rememberSelection

data class TrashState(
    val items: List<TrashedEntry> = emptyList(),
    /** Deleted in another app; their Drive copies wait for the user's decision. */
    val undecided: List<VanishedEntry> = emptyList(),
    val loading: Boolean = true,
)

private const val DAY_MILLIS = 24L * 60 * 60 * 1000

/**
 * Settle's trash (R12, ADR-015): what was deleted here or in another app, how long it stays, and
 * Restore and Delete forever for one item or a selection. Items deleted in another app while Settle
 * asks first wait at the top for a decision (R13).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun TrashScreen(
    state: TrashState,
    nowMillis: Long,
    onBack: () -> Unit,
    onRestore: (Set<String>) -> Unit,
    onDeleteForever: (Set<String>) -> Unit,
    onEmpty: () -> Unit,
    onMoveDriveCopies: () -> Unit,
    onKeepDriveCopies: () -> Unit,
) {
    var selection by rememberSelection()
    var confirm by rememberSaveable { mutableStateOf<ConfirmDelete?>(null) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            if (selection.isNotEmpty()) {
                SelectionTopBar(
                    selection.size,
                    onClear = { selection = emptySet() },
                    subtitle = null,
                    scrollBehavior = scrollBehavior,
                ) {
                    SelectionAction(R.drawable.ic_restore_from_trash, R.string.trash_restore) {
                        onRestore(selection)
                        selection = emptySet()
                    }
                    SelectionAction(R.drawable.ic_delete_forever, R.string.trash_delete_forever) {
                        confirm = ConfirmDelete(selection.toList())
                    }
                }
            } else {
                LargeFlexibleTopAppBar(
                    title = { Text(stringResource(R.string.trash_title)) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                painterResource(R.drawable.ic_arrow_back),
                                contentDescription = stringResource(R.string.navigate_back),
                            )
                        }
                    },
                    actions = {
                        if (state.items.isNotEmpty()) {
                            TextButton(
                                onClick = { confirm = ConfirmDelete(null, state.items.size) }
                            ) {
                                Text(stringResource(R.string.trash_empty_action))
                            }
                        }
                    },
                    scrollBehavior = scrollBehavior,
                )
            }
        },
    ) { padding ->
        val ids = remember(state.items) { state.items.map { it.id } }
        val current by rememberUpdatedState(selection)
        val grid = rememberLazyGridState()
        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            state = grid,
            contentPadding = padding,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier =
                Modifier.fillMaxSize()
                    .gridGestures(
                        grid,
                        ids,
                        { current },
                        onTap = { id -> selection = toggle(current, id) },
                        onSelectionChange = { selection = it },
                    ),
        ) {
            item(key = "explainer", span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    stringResource(R.string.trash_explainer),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
            if (state.undecided.isNotEmpty()) {
                item(key = "undecided", span = { GridItemSpan(maxLineSpan) }) {
                    UndecidedCard(state.undecided, onMoveDriveCopies, onKeepDriveCopies)
                }
            }
            if (state.items.isEmpty() && !state.loading) {
                item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        stringResource(R.string.trash_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(48.dp),
                    )
                }
            }
            items(state.items, key = { it.id }) { entry ->
                TrashCell(
                    entry,
                    nowMillis,
                    selected = entry.id in selection,
                    onClick = {
                        selection =
                            if (entry.id in selection) selection - entry.id
                            else selection + entry.id
                    },
                )
            }
        }
    }

    confirm?.let { request ->
        val count = request.ids?.size ?: request.count
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(pluralStringResource(R.plurals.trash_confirm_title, count, count)) },
            text = { Text(stringResource(R.string.trash_confirm_text)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirm = null
                        if (request.ids == null) onEmpty() else onDeleteForever(request.ids.toSet())
                        selection = emptySet()
                    },
                    colors =
                        ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        ),
                ) {
                    Text(stringResource(R.string.trash_delete_forever))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirm = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

private fun toggle(selection: Set<String>, id: String) =
    if (id in selection) selection - id else selection + id

/** What the confirmation dialog deletes: these items, or the whole trash when [ids] is null. */
private data class ConfirmDelete(val ids: List<String>?, val count: Int = ids?.size ?: 0) :
    java.io.Serializable

@Composable
private fun UndecidedCard(
    items: List<VanishedEntry>,
    onMove: () -> Unit,
    onKeep: () -> Unit,
) {
    Card(
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
            ),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                pluralStringResource(R.plurals.undecided_title, items.size, items.size),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                stringResource(R.string.undecided_text),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                items.take(5).joinToString { it.displayName } + if (items.size > 5) ", …" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onMove) {
                    Text(stringResource(R.string.move_drive_copies_to_trash))
                }
                OutlinedButton(onClick = onKeep) { Text(stringResource(R.string.keep_in_drive)) }
            }
        }
    }
}

@Composable
private fun TrashCell(
    entry: TrashedEntry,
    nowMillis: Long,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        Modifier.aspectRatio(1f)
            .background(
                if (selected) MaterialTheme.colorScheme.secondaryContainer
                else MaterialTheme.colorScheme.surfaceContainerHigh
            )
            // Touches are handled by the grid (gridGestures); this serves accessibility services.
            .semantics { onClick { onClick().let { true } } }
            .testTag("trash-${entry.id}")
    ) {
        val inset = if (selected) 12.dp else 0.dp
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
                modifier = Modifier.fillMaxSize().padding(inset).clip(RoundedCornerShape(inset)),
            )
        } else {
            // The phone no longer has the file; its copy is in Drive's trash.
            Icon(
                painterResource(R.drawable.ic_cloud_done),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Center).size(32.dp),
            )
        }
        Box(Modifier.align(Alignment.TopStart).padding(4.dp)) {
            SelectionMark(selected, R.string.selection_item, onPhoto = mediaStoreId != null)
        }
        if (entry.origin == TrashOrigin.ELSEWHERE) {
            Icon(
                painterResource(R.drawable.ic_open_in_new),
                contentDescription = stringResource(R.string.trash_deleted_elsewhere),
                tint = Color.White,
                modifier =
                    Modifier.align(Alignment.TopEnd)
                        .padding(4.dp)
                        .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(50))
                        .padding(3.dp)
                        .size(14.dp),
            )
        }
        Label(entry, nowMillis, Modifier.align(Alignment.BottomStart))
    }
}

/** Restoring, why restoring stopped, or the days left in the trash. */
@Composable
private fun Label(entry: TrashedEntry, nowMillis: Long, modifier: Modifier) {
    val problem = entry.restoreProblem
    val pill =
        modifier
            .padding(4.dp)
            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(50))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    when {
        problem != null ->
            Text(
                stringResource(problem.label),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.errorContainer,
                modifier = pill,
            )
        entry.restoreRequestedAtMs != null ->
            Row(pill, verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    color = Color.White,
                    strokeWidth = 1.5.dp,
                    modifier = Modifier.size(10.dp),
                )
                Text(
                    stringResource(R.string.trash_restoring),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
        entry.expiresAtMs != null -> {
            val days = ((entry.expiresAtMs - nowMillis + DAY_MILLIS - 1) / DAY_MILLIS).toInt()
            Text(
                if (days <= 0) stringResource(R.string.day_today)
                else pluralStringResource(R.plurals.trash_days_left, days, days),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                modifier = pill,
            )
        }
    }
}

private val RestoreProblem.label: Int
    get() =
        when (this) {
            RestoreProblem.AUTH_NEEDED -> R.string.restore_problem_auth
            RestoreProblem.OFFLINE -> R.string.restore_problem_offline
            RestoreProblem.NOT_IN_DRIVE -> R.string.restore_problem_not_in_drive
            RestoreProblem.CHECKSUM_MISMATCH -> R.string.restore_problem_mismatch
            RestoreProblem.NOT_WRITABLE -> R.string.restore_problem_no_space
            RestoreProblem.FAILED -> R.string.restore_problem_failed
        }
