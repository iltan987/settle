package com.iltan.settle.ui.folders

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.iltan.settle.R
import com.iltan.settle.data.db.FolderSummary
import com.iltan.settle.data.media.MediaThumbnail
import com.iltan.settle.data.mediaContentUri
import com.iltan.settle.data.thumbnail

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun FoldersScreen(
    state: FoldersState,
    onOpenFolder: (relativePath: String, name: String) -> Unit,
    onOpenFavorites: () -> Unit,
    onSetHidden: (relativePath: String, hidden: Boolean) -> Unit,
    onSetBackUp: (relativePath: String, backUp: Boolean) -> Unit = { _, _ -> },
    /** Items in Settle's trash (R12). */
    trashCount: Int = 0,
    onOpenTrash: () -> Unit = {},
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val rootName = stringResource(R.string.folder_root)
    val names =
        remember(state.folders, rootName) {
            folderNames(state.folders.map { it.relativePath }, rootName)
        }
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.folders_title)) },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        if (state.loading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                LoadingIndicator()
            }
            return@Scaffold
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 150.dp),
            contentPadding =
                PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = padding.calculateTopPadding() + 8.dp,
                    bottom = padding.calculateBottomPadding() + 16.dp,
                ),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(key = "favorites") {
                Tile(
                    name = stringResource(R.string.favorites_title),
                    count = state.favorites.size,
                    cover = state.favorites.firstOrNull()?.thumbnail,
                    placeholderIcon = R.drawable.ic_favorite,
                    onClick = onOpenFavorites,
                )
            }
            item(key = "trash") {
                Tile(
                    name = stringResource(R.string.trash_title),
                    count = trashCount,
                    cover = null,
                    placeholderIcon = R.drawable.ic_delete,
                    onClick = onOpenTrash,
                )
            }
            items(state.folders, key = { it.relativePath }) { folder ->
                val name = names[folder.relativePath].orEmpty()
                Tile(
                    name = name,
                    count = folder.itemCount,
                    cover = folder.cover,
                    placeholderIcon = R.drawable.ic_folder,
                    hidden = folder.hiddenFromTimeline,
                    onClick = { onOpenFolder(folder.relativePath, name) },
                    menu = { expanded, onDismiss ->
                        DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.folder_show_in_photos)) },
                                trailingIcon = {
                                    Switch(
                                        checked = !folder.hiddenFromTimeline,
                                        onCheckedChange = null,
                                    )
                                },
                                onClick = {
                                    onSetHidden(folder.relativePath, !folder.hiddenFromTimeline)
                                    onDismiss()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.folder_back_up)) },
                                trailingIcon = {
                                    Switch(checked = folder.backsUp, onCheckedChange = null)
                                },
                                onClick = {
                                    onSetBackUp(folder.relativePath, !folder.backsUp)
                                    onDismiss()
                                },
                            )
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun Tile(
    name: String,
    count: Int,
    cover: MediaThumbnail?,
    placeholderIcon: Int,
    onClick: () -> Unit,
    hidden: Boolean = false,
    menu: (@Composable (expanded: Boolean, onDismiss: () -> Unit) -> Unit)? = null,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Column(Modifier.clip(RoundedCornerShape(20.dp)).clickable(onClick = onClick)) {
        Box(
            Modifier.fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            if (cover != null) {
                AsyncImage(
                    model = cover,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    painterResource(placeholderIcon),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(40.dp),
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(start = 4.dp, top = 8.dp, bottom = 4.dp)) {
                Text(
                    name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (hidden) {
                        Icon(
                            painterResource(R.drawable.ic_visibility_off),
                            contentDescription = stringResource(R.string.folder_hidden),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(14.dp).padding(end = 2.dp),
                        )
                    }
                    Text(
                        pluralStringResource(R.plurals.item_count, count, count),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (menu != null) {
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(
                            painterResource(R.drawable.ic_more_vert),
                            contentDescription = stringResource(R.string.folder_options, name),
                        )
                    }
                    menu(menuOpen) { menuOpen = false }
                }
            }
        }
    }
}

private val FolderSummary.cover: MediaThumbnail
    get() =
        MediaThumbnail(
            mediaContentUri(coverKind, coverVolume, coverMediaStoreId),
            coverDateModifiedS,
        )
