package com.iltan.settle.ui.folders

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.iltan.settle.data.GridSource
import com.iltan.settle.data.Library
import com.iltan.settle.data.UiPreferences
import com.iltan.settle.data.db.FolderSummary
import com.iltan.settle.data.db.MediaEntry
import com.iltan.settle.domain.timeline.groupByDay
import com.iltan.settle.ui.grid.MediaGridState
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class FoldersState(
    val folders: List<FolderSummary> = emptyList(),
    /** Newest favourite first. */
    val favorites: List<MediaEntry> = emptyList(),
    val loading: Boolean = true,
)

class FoldersViewModel(
    private val library: Library,
    /** Called after a folder's backup choice changed, so the backup plan follows. */
    private val onBackupChoiceChanged: suspend () -> Unit = {},
) : ViewModel() {
    val state: StateFlow<FoldersState> =
        combine(library.folders(), library.entries(GridSource.Favorites)) { folders, favorites ->
                FoldersState(folders, favorites, loading = false)
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FoldersState())

    fun setHiddenFromTimeline(relativePath: String, hidden: Boolean) {
        viewModelScope.launch { library.setFolderHidden(relativePath, hidden) }
    }

    fun setBackUp(relativePath: String, backUp: Boolean) {
        viewModelScope.launch {
            library.setFolderBackUp(relativePath, backUp)
            onBackupChoiceChanged()
        }
    }
}

/**
 * One folder's or the favourites' items, grouped by day like the timeline, at the density the user
 * pinched to there.
 */
class CollectionViewModel(
    library: Library,
    source: GridSource,
    private val preferences: UiPreferences,
) : ViewModel() {
    val state: StateFlow<MediaGridState> =
        combine(
                library.entries(source),
                preferences.prefs.map { it.gridColumns }.distinctUntilChanged(),
            ) { entries, columns ->
                MediaGridState(
                    sections = groupByDay(entries, ZoneId.systemDefault()) { it.takenAtMs },
                    count = entries.size,
                    loading = false,
                    columns = columns,
                )
            }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MediaGridState())

    /** This folder as the Folders tab lists it; null for the favourites or once it is gone. */
    val folder: StateFlow<FolderSummary?> =
        (if (source is GridSource.Folder) {
                library.folders().map { all ->
                    all.firstOrNull { it.relativePath == source.relativePath }
                }
            } else flowOf(null))
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setColumns(columns: Int) {
        viewModelScope.launch { preferences.setGridColumns(columns) }
    }
}
