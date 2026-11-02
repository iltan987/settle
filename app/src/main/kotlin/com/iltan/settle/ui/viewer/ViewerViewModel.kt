package com.iltan.settle.ui.viewer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.iltan.settle.data.GridSource
import com.iltan.settle.data.Library
import com.iltan.settle.data.backup.BackupOutcome
import com.iltan.settle.data.backup.BackupPreferences
import com.iltan.settle.data.backup.CloudBackup
import com.iltan.settle.data.db.BackupDao
import com.iltan.settle.data.db.MediaDetails
import com.iltan.settle.data.db.MediaEntry
import com.iltan.settle.data.db.UploadEntity
import com.iltan.settle.data.media.Place
import com.iltan.settle.data.media.Places
import com.iltan.settle.domain.backup.BackupPause
import java.util.Locale
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What the viewer knows about one item beyond its grid entry; [place] arrives last. */
data class ItemFacts(
    val details: MediaDetails?,
    val location: Pair<Double, Double>? = null,
    val place: Place? = null,
)

class ViewerViewModel(
    private val library: Library,
    source: GridSource,
    private val backupDao: BackupDao,
    private val cloudBackup: CloudBackup,
    preferences: BackupPreferences,
    private val places: Places,
    /** Replans backup after a folder's choice changed. */
    private val reschedule: suspend () -> Unit,
) : ViewModel() {

    /** The list the viewer pages through; null until loaded. */
    val entries: StateFlow<List<MediaEntry>?> =
        library.entries(source).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** How backups requested anywhere ended, for a short message. */
    val outcomes: Flow<BackupOutcome> = cloudBackup.outcomes

    /** Whether automatic backup waits for the charger: "Backs up tonight". */
    val chargingOnly: StateFlow<Boolean> =
        preferences.choices
            .map { it.settings.onlyWhileCharging }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    /** Facts already found, so swiping back doesn't read or ask again. */
    private val known = mutableMapOf<String, ItemFacts>()

    fun toggleFavorite(entry: MediaEntry) {
        viewModelScope.launch { library.setFavorite(entry, !entry.isFavorite) }
    }

    fun cloudStatus(itemId: String): Flow<CloudStatus> =
        combine(
                cloudBackup.pause,
                backupDao.observeUpload(itemId),
                cloudBackup.progress.map { it[itemId] },
                combine(cloudBackup.requested(itemId), cloudBackup.running, ::Pair),
                cloudBackup.decision(itemId),
            ) { pause, upload, progress, (requested, running), decision ->
                cloudStatus(
                    connected = pause != BackupPause.NOT_CONNECTED,
                    upload = upload,
                    progress = progress,
                    requested = requested,
                    decision = decision,
                    pause = pause,
                    running = running,
                )
            }
            .distinctUntilChanged()

    /** The item's Drive copy, for Details' technical rows. */
    fun upload(itemId: String): Flow<UploadEntity?> = backupDao.observeUpload(itemId)

    fun backUp(entry: MediaEntry) {
        viewModelScope.launch { cloudBackup.backUpNow(listOf(entry.id)) }
    }

    /** Chooses the item's folder for backup, which takes this item along (D4 "Back up folder"). */
    fun backUpFolder(entry: MediaEntry) {
        viewModelScope.launch {
            val folder = library.details(entry.id)?.relativePath ?: return@launch
            library.setFolderBackUp(folder, true)
            reschedule()
        }
    }

    fun resume() {
        viewModelScope.launch { cloudBackup.setPaused(false) }
    }

    suspend fun motionVideoOffset(entry: MediaEntry): Long? = library.motionVideoOffset(entry)

    /**
     * The item's file details and where it was taken: the place is named only for the item on
     * screen, after the rest is shown, and nearby items share one answer (see [Places]).
     */
    fun facts(entry: MediaEntry, locale: Locale): Flow<ItemFacts> = flow {
        known[entry.id]
            ?.takeIf { it.place != null || it.location == null }
            ?.let {
                emit(it)
                return@flow
            }
        val found =
            known[entry.id]
                ?: ItemFacts(library.details(entry.id), library.location(entry)).also {
                    known[entry.id] = it
                }
        emit(found)
        val (latitude, longitude) = found.location ?: return@flow
        val place = places.place(latitude, longitude, locale) ?: return@flow
        found.copy(place = place).also { known[entry.id] = it }.let { emit(it) }
    }
}
