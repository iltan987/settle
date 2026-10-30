package com.iltan.settle.ui.folders

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.iltan.settle.data.GridSource
import com.iltan.settle.data.Library
import com.iltan.settle.data.db.FolderSummary
import com.iltan.settle.data.db.MediaEntry
import com.iltan.settle.domain.timeline.groupByDay
import com.iltan.settle.ui.grid.MediaGridState
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class FoldersState(
    val folders: List<FolderSummary> = emptyList(),
    /** Newest favourite first; its first item is the Favourites cover. */
    val favorites: List<MediaEntry> = emptyList(),
    val loading: Boolean = true,
)

class FoldersViewModel(private val library: Library) : ViewModel() {
    val state: StateFlow<FoldersState> =
        combine(library.folders(), library.entries(GridSource.Favorites)) { folders, favorites ->
                FoldersState(folders, favorites, loading = false)
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FoldersState())

    fun setHiddenFromTimeline(relativePath: String, hidden: Boolean) {
        viewModelScope.launch { library.setFolderHidden(relativePath, hidden) }
    }
}

/** One folder's or the favourites' items, grouped by day like the timeline. */
class CollectionViewModel(library: Library, source: GridSource) : ViewModel() {
    val state: StateFlow<MediaGridState> =
        library
            .entries(source)
            .map { entries ->
                MediaGridState(
                    sections = groupByDay(entries, ZoneId.systemDefault()) { it.takenAtMs },
                    count = entries.size,
                    loading = false,
                )
            }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MediaGridState())
}
