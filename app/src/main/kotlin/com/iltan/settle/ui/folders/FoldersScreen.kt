package com.iltan.settle.ui.folders

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.iltan.settle.R
import com.iltan.settle.data.db.FolderSummary
import com.iltan.settle.data.media.MediaThumbnail
import com.iltan.settle.data.mediaContentUri
import com.iltan.settle.ui.chrome.AccountButton
import com.iltan.settle.ui.chrome.BottomBarHeight
import com.iltan.settle.ui.chrome.ChipState
import com.iltan.settle.ui.chrome.SettleTopBar
import com.iltan.settle.ui.chrome.StatusChip
import com.iltan.settle.ui.theme.SettleShape
import com.iltan.settle.ui.theme.SettleType

/**
 * The Folders tab (H1): Favourites and Trash on top, then every folder on the phone, biggest first,
 * as square covers marked when they back up or are hidden from Photos. Long-press a folder for its
 * options (H3).
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun FoldersScreen(
    state: FoldersState,
    onOpenFolder: (relativePath: String, name: String) -> Unit,
    onOpenFavorites: () -> Unit,
    choices: FolderChoices,
    modifier: Modifier = Modifier,
    /** Items in Settle's trash (R12). */
    trashCount: Int = 0,
    onOpenTrash: () -> Unit = {},
    chip: ChipState? = null,
    onOpenBackup: () -> Unit = {},
    accountEmail: String? = null,
    onOpenSettings: () -> Unit = {},
) {
    val rootName = stringResource(R.string.folder_root)
    val names =
        remember(state.folders, rootName) {
            folderNames(state.folders.map { it.relativePath }, rootName)
        }
    var options by rememberSaveable { mutableStateOf<String?>(null) }
    val bottom =
        WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + BottomBarHeight
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize()) {
            SettleTopBar(stringResource(R.string.folders_title)) {
                chip?.let { StatusChip(it, onClick = onOpenBackup) }
                AccountButton(accountEmail, onOpenSettings)
            }
            if (state.loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    LoadingIndicator()
                }
                return@Column
            }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 150.dp),
                contentPadding =
                    PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = bottom + 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item(key = "favorites") {
                    UtilityTile(
                        R.drawable.ic_favorite_filled,
                        MaterialTheme.colorScheme.primary,
                        stringResource(R.string.favorites_title),
                        state.favorites.size,
                        onOpenFavorites,
                    )
                }
                item(key = "trash") {
                    UtilityTile(
                        R.drawable.ic_delete,
                        MaterialTheme.colorScheme.onSurfaceVariant,
                        stringResource(R.string.trash_title),
                        trashCount,
                        onOpenTrash,
                    )
                }
                if (state.folders.isNotEmpty()) {
                    item(key = "on-phone", span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            stringResource(R.string.folders_on_phone),
                            style = SettleType.dayHeader,
                            modifier =
                                Modifier.padding(start = 4.dp, top = 4.dp).semantics { heading() },
                        )
                    }
                }
                items(state.folders, key = { it.relativePath }) { folder ->
                    val name = names[folder.relativePath].orEmpty()
                    FolderTile(
                        folder = folder,
                        name = name,
                        onClick = { onOpenFolder(folder.relativePath, name) },
                        onLongClick = { options = folder.relativePath },
                    )
                }
            }
        }
    }
    val chosen = state.folders.firstOrNull { it.relativePath == options }
    if (chosen != null) {
        FolderOptionsSheet(
            chosen,
            names[chosen.relativePath].orEmpty(),
            choices,
            onDismiss = { options = null },
        )
    }
}

/** Favourites or Trash (UtilityTile): 64 dp at least, c1, radius 16. */
@Composable
private fun UtilityTile(
    icon: Int,
    tint: Color,
    title: String,
    count: Int,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .heightIn(min = 64.dp)
            .clip(SettleShape.Cover)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .combinedClickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = tint)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge)
            Text(
                pluralStringResource(R.plurals.item_count, count, count),
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = null),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * A folder (FolderTile): its newest item as a square cover, its name, its count and "Backs up" when
 * backup is on. A folder hidden from Photos has a dimmed cover with a Hidden pill.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderTile(
    folder: FolderSummary,
    name: String,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val optionsLabel = stringResource(R.string.folder_options, name)
    Column(
        Modifier.clip(SettleShape.Cover)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
                onLongClickLabel = optionsLabel,
            ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier.fillMaxWidth()
                .aspectRatio(1f)
                .clip(SettleShape.Cover)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
        ) {
            AsyncImage(
                model = folder.cover,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier =
                    Modifier.fillMaxSize().alpha(if (folder.hiddenFromTimeline) 0.55f else 1f),
            )
            if (folder.hiddenFromTimeline) {
                Row(
                    Modifier.align(Alignment.TopStart)
                        .padding(8.dp)
                        .height(26.dp)
                        .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(13.dp))
                        .padding(start = 7.dp, end = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        painterResource(R.drawable.ic_visibility_off),
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(15.dp),
                    )
                    Text(
                        stringResource(R.string.folder_hidden_pill),
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = null),
                        color = Color.White,
                    )
                }
            }
        }
        Column(
            Modifier.padding(horizontal = 4.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                name,
                style = SettleType.itemTitle,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    pluralStringResource(R.plurals.item_count, folder.itemCount, folder.itemCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (folder.backsUp) {
                    Text(
                        " · ",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Icon(
                        painterResource(R.drawable.ic_cloud_done_filled),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(15.dp),
                    )
                    Text(
                        stringResource(R.string.folder_backs_up_mark),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

internal val FolderSummary.cover: MediaThumbnail
    get() =
        MediaThumbnail(
            mediaContentUri(coverKind, coverVolume, coverMediaStoreId),
            coverDateModifiedS,
        )
