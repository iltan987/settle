package com.iltan.settle.ui.timeline

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.iltan.settle.data.GridSource
import com.iltan.settle.data.Library
import com.iltan.settle.data.media.LibrarySync
import com.iltan.settle.domain.timeline.groupByDay
import com.iltan.settle.ui.grid.MediaGridState
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn

class TimelineViewModel(library: Library, sync: LibrarySync) : ViewModel() {
    val state: StateFlow<MediaGridState> =
        combine(library.entries(GridSource.Timeline), library.lastScanAt(), sync.state) {
                entries,
                lastScan,
                scan ->
                MediaGridState(
                    sections = groupByDay(entries, ZoneId.systemDefault()) { it.takenAtMs },
                    count = entries.size,
                    loading = entries.isEmpty() && (lastScan == null || scan.scanning),
                )
            }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MediaGridState())
}
