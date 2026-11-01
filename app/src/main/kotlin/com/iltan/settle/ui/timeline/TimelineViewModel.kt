package com.iltan.settle.ui.timeline

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.iltan.settle.data.GridSource
import com.iltan.settle.data.Library
import com.iltan.settle.data.UiPreferences
import com.iltan.settle.data.backup.BackupStatusSource
import com.iltan.settle.data.backup.UploadProgress
import com.iltan.settle.data.db.MediaEntry
import com.iltan.settle.data.media.LibrarySync
import com.iltan.settle.domain.backup.BackupDecision
import com.iltan.settle.domain.backup.cellStatus
import com.iltan.settle.domain.timeline.groupByDay
import com.iltan.settle.ui.grid.CellBackup
import com.iltan.settle.ui.grid.MediaGridState
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class TimelineViewModel(
    library: Library,
    sync: LibrarySync,
    status: BackupStatusSource,
    progress: StateFlow<Map<String, UploadProgress>>,
    breakerCount: Flow<Int>,
    private val preferences: UiPreferences,
) : ViewModel() {
    val state: StateFlow<MediaGridState> =
        combine(
                library.entries(GridSource.Timeline),
                library.lastScanAt(),
                sync.state,
                preferences.prefs.map { it.gridColumns }.distinctUntilChanged(),
            ) { entries, lastScan, scan, columns ->
                MediaGridState(
                    sections = groupByDay(entries, ZoneId.systemDefault()) { it.takenAtMs },
                    count = entries.size,
                    loading = entries.isEmpty() && (lastScan == null || scan.scanning),
                    columns = columns,
                )
            }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MediaGridState())

    val status: StateFlow<TimelineStatus> =
        combine(status.headline, status.accountEmail, breakerCount, preferences.dismissed) {
                headline,
                email,
                breaker,
                dismissed ->
                TimelineStatus(headline, email, breaker, dismissed)
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TimelineStatus())

    /** Backup's decision for every item that isn't verified yet; null until read. */
    val decisions: StateFlow<Map<String, BackupDecision>?> = status.decisions

    /** Uploads going on now, by item. */
    val uploads: StateFlow<Map<String, UploadProgress>> = progress

    init {
        // A dismissal is forgotten once its problem is gone, so the next occurrence shows at once.
        viewModelScope.launch {
            this@TimelineViewModel.status
                .map { it.headline to it.breakerCount }
                .filter { (headline, _) -> headline != null }
                .map { (headline, breaker) ->
                    backupNotices(headline, breaker).mapNotNull { it.key }.toSet()
                }
                .distinctUntilChanged()
                .collect { preferences.noticesNow(it) }
        }
    }

    fun setColumns(columns: Int) {
        viewModelScope.launch { preferences.setGridColumns(columns) }
    }

    fun dismiss(key: String) {
        viewModelScope.launch { preferences.dismiss(key) }
    }

    /** The items backup needs the user for, from the same decisions the chip counts. */
    fun attentionIds(): List<String> =
        decisions.value.orEmpty().filterValues { it == BackupDecision.NeedsAttention }.keys.toList()
}

/** What a cell shows about [entry]'s backup, or null while backup's decisions aren't read yet. */
fun cellBackup(
    entry: MediaEntry,
    decisions: Map<String, BackupDecision>?,
    uploads: Map<String, UploadProgress>,
): CellBackup? {
    if (decisions == null) return null
    val upload = uploads[entry.id]
    return CellBackup(
        cellStatus(entry.cloudState, decisions[entry.id], uploading = upload != null),
        upload?.let { if (it.totalBytes > 0) it.sentBytes.toFloat() / it.totalBytes else 0f },
    )
}
