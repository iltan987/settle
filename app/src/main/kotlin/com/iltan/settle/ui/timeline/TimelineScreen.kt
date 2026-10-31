package com.iltan.settle.ui.timeline

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.iltan.settle.R
import com.iltan.settle.data.db.MediaEntry
import com.iltan.settle.ui.grid.MediaGrid
import com.iltan.settle.ui.grid.MediaGridState
import com.iltan.settle.ui.grid.SelectionAction
import com.iltan.settle.ui.grid.SelectionTopBar
import com.iltan.settle.ui.grid.rememberSelection
import com.iltan.settle.ui.trash.ItemActions
import com.iltan.settle.ui.trash.MassDisappearanceNotice

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun TimelineScreen(
    state: MediaGridState,
    onOpen: (MediaEntry) -> Unit,
    onOpenSettings: () -> Unit,
    /** Delete, back up and share for a selection; null turns selecting off. */
    actions: ItemActions? = null,
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
    /** Files that vanished at once and wait for the user (R14); 0 when there are none. */
    breakerCount: Int = 0,
    onBreakerKeep: () -> Unit = {},
    onBreakerMove: () -> Unit = {},
    onCheckAgain: () -> Unit = {},
) {
    var selection by rememberSelection()
    // Items deleted or gone elsewhere leave the selection.
    val visible = remember(state.sections) { state.sections.flatMap { it.items } }
    val chosen = visible.filter { it.id in selection }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (actions != null && chosen.isNotEmpty()) {
                SelectionTopBar(
                    chosen.size,
                    onClear = { selection = emptySet() },
                    subtitle = pluralStringResource(R.plurals.item_count, state.count, state.count),
                    scrollBehavior = scrollBehavior,
                ) {
                    SelectionAction(R.drawable.ic_share, R.string.viewer_share) {
                        actions.share(chosen)
                    }
                    SelectionAction(R.drawable.ic_cloud_upload, R.string.backup_now) {
                        actions.backUp(chosen)
                        selection = emptySet()
                    }
                    SelectionAction(R.drawable.ic_delete, R.string.action_delete) {
                        actions.delete(chosen) { selection = emptySet() }
                    }
                }
            } else {
                TimelineTopBar(state.count, onOpenSettings, scrollBehavior)
            }
        },
    ) { padding ->
        val notice: (@Composable () -> Unit)? =
            if (breakerCount > 0) {
                {
                    MassDisappearanceNotice(
                        breakerCount,
                        onBreakerKeep,
                        onBreakerMove,
                        onCheckAgain,
                    )
                }
            } else null
        when {
            state.sections.isNotEmpty() ->
                MediaGrid(
                    sections = state.sections,
                    onOpen = onOpen,
                    contentPadding = padding,
                    modifier = Modifier.fillMaxSize(),
                    selection = chosen.map { it.id }.toSet(),
                    onSelectionChange = if (actions != null) ({ selection = it }) else null,
                    header = notice,
                )
            notice != null -> Box(Modifier.padding(padding)) { notice() }
            state.loading ->
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    LoadingIndicator()
                }
            else ->
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    EmptyLibrary()
                }
        }
    }
}

@Composable
private fun EmptyLibrary() {
    Column(
        modifier = Modifier.padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(96.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    painterResource(R.drawable.ic_photo_library),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(40.dp),
                )
            }
        }
        Text(
            stringResource(R.string.timeline_empty_title),
            style = MaterialTheme.typography.titleLarge,
        )
        Text(
            stringResource(R.string.timeline_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun TimelineTopBar(
    count: Int,
    onOpenSettings: () -> Unit,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    LargeFlexibleTopAppBar(
        title = { Text(stringResource(R.string.timeline_title)) },
        subtitle =
            if (count > 0) {
                { Text(pluralStringResource(R.plurals.item_count, count, count)) }
            } else {
                null
            },
        actions = {
            IconButton(onClick = onOpenSettings) {
                Icon(
                    painterResource(R.drawable.ic_settings),
                    contentDescription = stringResource(R.string.settings_open),
                )
            }
        },
        scrollBehavior = scrollBehavior,
    )
}
