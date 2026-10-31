package com.iltan.settle.ui.viewer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.iltan.settle.data.GridSource
import com.iltan.settle.data.Library
import com.iltan.settle.data.db.MediaDetails
import com.iltan.settle.data.db.MediaEntry
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What the info sheet shows about one item. */
data class ItemInfo(val details: MediaDetails, val location: Pair<Double, Double>?)

class ViewerViewModel(private val library: Library, source: GridSource) : ViewModel() {

    /** The list the viewer pages through; null until loaded. */
    val entries: StateFlow<List<MediaEntry>?> =
        library.entries(source).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun toggleFavorite(entry: MediaEntry) {
        viewModelScope.launch { library.setFavorite(entry, !entry.isFavorite) }
    }

    suspend fun motionVideoOffset(entry: MediaEntry): Long? = library.motionVideoOffset(entry)

    suspend fun info(entry: MediaEntry): ItemInfo? =
        library.details(entry.id)?.let { ItemInfo(it, library.location(entry)) }
}
