package com.iltan.settle.ui.folders

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.iltan.settle.R
import com.iltan.settle.data.db.FolderSummary
import com.iltan.settle.data.db.MediaEntry
import com.iltan.settle.ui.chrome.BackTopBar
import com.iltan.settle.ui.chrome.BottomBarHeight
import com.iltan.settle.ui.chrome.NoticeAction
import com.iltan.settle.ui.chrome.NoticeCard
import com.iltan.settle.ui.chrome.NoticeContent
import com.iltan.settle.ui.chrome.Tone
import com.iltan.settle.ui.grid.CellBackup
import com.iltan.settle.ui.grid.ItemSelectionBar
import com.iltan.settle.ui.grid.MediaGrid
import com.iltan.settle.ui.grid.MediaGridState
import com.iltan.settle.ui.grid.SelectionTopBar
import com.iltan.settle.ui.grid.rememberSelection
import com.iltan.settle.ui.theme.SettleMotion
import com.iltan.settle.ui.theme.SettleShape
import com.iltan.settle.ui.trash.ItemActions

/** A folder's page also offers its options: the folder as the library has it, and its name. */
@Immutable
data class FolderPage(val folder: FolderSummary, val name: String, val choices: FolderChoices)

/**
 * A folder's page or the favourites (H2, H5): the same grid as the timeline, with its statuses,
 * selection and pinch. A folder's page says whether it backs up and shows in Photos, and its tune
 * button and chips open its options; a hidden folder explains itself on a card instead.
 */
@Composable
fun CollectionScreen(
    title: String,
    state: MediaGridState,
    emptyText: String,
    onOpen: (MediaEntry) -> Unit,
    onBack: () -> Unit,
    subtitle: String? = null,
    /** Delete, back up, share and favourite for a selection; null turns selecting off. */
    actions: ItemActions? = null,
    backup: (MediaEntry) -> CellBackup? = { null },
    connected: Boolean = false,
    onColumnsChange: ((Int) -> Unit)? = null,
    page: FolderPage? = null,
) {
    var selection by rememberSelection()
    val visible = remember(state.sections) { state.sections.flatMap { it.items } }
    val chosen = remember(visible, selection) { visible.filter { it.id in selection } }
    val selecting = actions != null && chosen.isNotEmpty()
    var lastChosen by remember { mutableStateOf(chosen) }
    SideEffect { if (chosen.isNotEmpty()) lastChosen = chosen }
    var options by rememberSaveable { mutableStateOf(false) }
    val bottom =
        WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + BottomBarHeight

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                // The selection bar takes the top bar's place at the same height.
                Crossfade(selecting, animationSpec = SettleMotion.fade(), label = "top bar") {
                    selectingNow ->
                    if (selectingNow) {
                        SelectionTopBar(
                            lastChosen.size,
                            onClear = { selection = emptySet() },
                            onSelectAll = { selection = visible.map { it.id }.toSet() },
                        )
                    } else {
                        BackTopBar(title, onBack, subtitle = subtitle) {
                            if (page != null) {
                                IconButton(onClick = { options = true }) {
                                    Icon(
                                        painterResource(R.drawable.ic_tune),
                                        contentDescription =
                                            stringResource(R.string.folder_options_title),
                                    )
                                }
                            }
                        }
                    }
                }
                val header: (@Composable () -> Unit)? = page?.let {
                    { FolderHeader(it) { options = true } }
                }
                if (state.sections.isNotEmpty()) {
                    MediaGrid(
                        sections = state.sections,
                        onOpen = onOpen,
                        contentPadding = PaddingValues(bottom = bottom),
                        columns = state.columns,
                        onColumnsChange = onColumnsChange,
                        modifier = Modifier.fillMaxSize(),
                        selection = chosen.map { it.id }.toSet(),
                        onSelectionChange = if (actions != null) ({ selection = it }) else null,
                        backup = backup,
                        connected = connected,
                        header = header,
                    )
                } else if (!state.loading) {
                    header?.invoke()
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            emptyText,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(32.dp),
                        )
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
                    ItemSelectionBar(chosen, chosen.ifEmpty { lastChosen }, actions) {
                        selection = emptySet()
                    }
                }
            }
        }
    }
    if (options && page != null) {
        FolderOptionsSheet(page.folder, page.name, page.choices, onDismiss = { options = false })
    }
}

/**
 * What a folder's page says about it above the grid: two state chips, or, when it is hidden, a card
 * that says so and offers to show it again.
 */
@Composable
private fun FolderHeader(page: FolderPage, onOptions: () -> Unit) {
    val folder = page.folder
    if (folder.hiddenFromTimeline) {
        NoticeCard(
            NoticeContent(
                Tone.INFO,
                R.drawable.ic_visibility_off,
                stringResource(R.string.folder_hidden),
                stringResource(
                    if (folder.backsUp) R.string.folder_hidden_body_backs_up
                    else R.string.folder_hidden_body,
                    page.name,
                ),
                NoticeAction(stringResource(R.string.folder_show_in_photos)) {
                    page.choices.hide(folder, page.name, false)
                },
            )
        )
    } else {
        FlowRow(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (folder.backsUp) {
                StateChip(
                    R.drawable.ic_cloud_done_filled,
                    MaterialTheme.colorScheme.primary,
                    stringResource(R.string.folder_backs_up_mark),
                    onOptions,
                )
            } else {
                StateChip(
                    R.drawable.ic_cloud_off,
                    MaterialTheme.colorScheme.onSurfaceVariant,
                    stringResource(R.string.backup_not_backed_up),
                    onOptions,
                )
            }
            StateChip(
                R.drawable.ic_visibility,
                MaterialTheme.colorScheme.onSurfaceVariant,
                stringResource(R.string.folder_shown_in_photos),
                onOptions,
            )
        }
    }
}

/** A folder's state (FolderStateChip): 32 dp, c1; it opens the folder's options. */
@Composable
private fun StateChip(
    icon: Int,
    tint: Color,
    label: String,
    onClick: () -> Unit,
) {
    Row(
        Modifier.height(32.dp)
            .background(MaterialTheme.colorScheme.surfaceContainer, SettleShape.Pill)
            .clip(SettleShape.Pill)
            .clickable(onClick = onClick)
            .padding(start = 8.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            painterResource(icon),
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(18.dp),
        )
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

/** "412 items · DCIM/Camera". */
@Composable
fun folderSubtitle(folder: FolderSummary): String =
    pluralStringResource(R.plurals.item_count, folder.itemCount, folder.itemCount) +
        " · " +
        folder.relativePath.trimEnd('/').ifEmpty { "/" }
