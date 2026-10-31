package com.iltan.settle.ui.viewer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.iltan.settle.data.GridSource
import com.iltan.settle.data.Library
import com.iltan.settle.data.backup.BackupOutcome
import com.iltan.settle.data.backup.CloudBackup
import com.iltan.settle.data.db.BackupDao
import com.iltan.settle.data.db.MediaDetails
import com.iltan.settle.data.db.MediaEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What the info sheet shows about one item. */
data class ItemInfo(val details: MediaDetails, val location: Pair<Double, Double>?)

class ViewerViewModel(
    private val library: Library,
    source: GridSource,
    private val backupDao: BackupDao,
    private val cloudBackup: CloudBackup,
) : ViewModel() {

    /** The list the viewer pages through; null until loaded. */
    val entries: StateFlow<List<MediaEntry>?> =
        library.entries(source).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** How backups requested anywhere ended, for a short message. */
    val outcomes: Flow<BackupOutcome> = cloudBackup.outcomes

    fun toggleFavorite(entry: MediaEntry) {
        viewModelScope.launch { library.setFavorite(entry, !entry.isFavorite) }
    }

    fun cloudStatus(itemId: String): Flow<CloudStatus> =
        combine(
                backupDao.account().map { it != null },
                backupDao.observeUpload(itemId),
                cloudBackup.progress.map { it[itemId] },
                cloudBackup.requested(itemId),
                cloudBackup.decision(itemId),
                ::cloudStatus,
            )
            .distinctUntilChanged()

    fun backUp(entry: MediaEntry) {
        viewModelScope.launch { cloudBackup.backUpNow(listOf(entry.id)) }
    }

    suspend fun motionVideoOffset(entry: MediaEntry): Long? = library.motionVideoOffset(entry)

    suspend fun info(entry: MediaEntry): ItemInfo? =
        library.details(entry.id)?.let { ItemInfo(it, library.location(entry)) }
}
