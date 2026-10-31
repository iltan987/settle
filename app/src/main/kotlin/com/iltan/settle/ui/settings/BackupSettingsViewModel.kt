package com.iltan.settle.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.iltan.settle.data.Library
import com.iltan.settle.data.backup.BackupChoices
import com.iltan.settle.data.backup.BackupNotifications
import com.iltan.settle.data.backup.BackupPreferences
import com.iltan.settle.data.backup.BackupScheduler
import com.iltan.settle.data.backup.CloudBackup
import com.iltan.settle.data.backup.toCandidate
import com.iltan.settle.data.db.BackupDao
import com.iltan.settle.data.db.FolderSummary
import com.iltan.settle.domain.backup.BackupDecision
import com.iltan.settle.domain.backup.BackupPause
import com.iltan.settle.domain.backup.BackupSettings
import com.iltan.settle.domain.backup.backupDecision
import com.iltan.settle.domain.backup.planBackup
import com.iltan.settle.domain.transfer.NetworkUse
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class BackupSettingsState(
    val loaded: Boolean = false,
    val choices: BackupChoices = BackupChoices(),
    /** Why nothing is backed up right now, if so. */
    val pause: BackupPause? = null,
    val backedUp: Int = 0,
    val waiting: Int = 0,
    val queued: Int = 0,
    val uploading: Int = 0,
    val needsAttention: Int = 0,
    val folders: List<FolderSummary> = emptyList(),
)

class BackupSettingsViewModel(
    private val dao: BackupDao,
    private val library: Library,
    private val preferences: BackupPreferences,
    private val scheduler: BackupScheduler,
    private val cloudBackup: CloudBackup,
    private val notifications: BackupNotifications,
    private val canReadOriginals: () -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    /** Bumped when permissions may have changed outside the database's view. */
    private val refreshes = MutableStateFlow(0)

    private val plan =
        combine(
            dao.account(),
            preferences.choices,
            dao.observeBackupCandidates(),
            refreshes,
        ) { account, choices, rows, _ ->
            val pause =
                when {
                    account == null -> BackupPause.NOT_CONNECTED
                    !canReadOriginals() -> BackupPause.NO_ORIGINAL_ACCESS
                    else -> choices.blocked
                }
            choices to
                planBackup(rows.mapNotNull { it.toCandidate() }, choices.settings, pause, clock())
        }

    val state: StateFlow<BackupSettingsState> =
        combine(plan, dao.backedUpCount(), cloudBackup.progress, library.folders()) {
                (choices, plan),
                backedUp,
                progress,
                folders ->
                BackupSettingsState(
                    loaded = true,
                    choices = choices,
                    pause = plan.pause,
                    backedUp = backedUp,
                    waiting = plan.waiting,
                    queued = plan.queue.size,
                    uploading = progress.size,
                    needsAttention = plan.needsAttention,
                    folders = folders,
                )
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BackupSettingsState())

    /** Looks at permissions again and plans again, e.g. after the user granted one. */
    fun refresh() {
        refreshes.update { it + 1 }
        viewModelScope.launch { scheduler.reschedule() }
    }

    fun setAutomatic(on: Boolean) = updateSettings { it.copy(automatic = on) }

    fun setGrace(millis: Long) = updateSettings { it.copy(graceMillis = millis) }

    fun setOnlyWhileCharging(on: Boolean) = updateSettings { it.copy(onlyWhileCharging = on) }

    fun setUseMobileData(on: Boolean) = updateSettings { it.copy(useMobileData = on) }

    fun setNetworkUse(use: NetworkUse) = change { it.copy(networkUse = use) }

    fun setPaused(paused: Boolean) {
        viewModelScope.launch {
            preferences.update { it.copy(settings = it.settings.copy(paused = paused)) }
            if (paused) {
                scheduler.stopUploads()
            } else {
                // Requests made before the pause continue now that Settle is open.
                cloudBackup.backUpNow(emptyList())
            }
            scheduler.reschedule()
        }
    }

    fun setFolderBackUp(folder: FolderSummary, backUp: Boolean) {
        viewModelScope.launch {
            library.setFolderBackUp(folder.relativePath, backUp)
            scheduler.reschedule()
        }
    }

    /** Everything chosen for backup, now: grace periods and the charger are skipped (R3). */
    fun backUpEverythingNow() {
        viewModelScope.launch {
            val choices = preferences.current()
            // Chosen items, as if automatic backup were on and the grace period over.
            val settings = choices.settings.copy(automatic = true, graceMillis = 0)
            val ids =
                dao.backupCandidates()
                    .mapNotNull { it.toCandidate() }
                    .filter {
                        val decision =
                            backupDecision(it.copy(retryAtMillis = null), settings, clock())
                        decision == BackupDecision.Ready
                    }
                    .map { it.itemId.value }
            cloudBackup.backUpNow(ids)
        }
    }

    /** After the user freed space in Drive. */
    fun retryAfterFull() {
        viewModelScope.launch {
            if (preferences.unblock(BackupPause.QUOTA_FULL)) notifications.clearPause()
            scheduler.reschedule()
        }
    }

    private fun updateSettings(edit: (BackupSettings) -> BackupSettings) = change {
        it.copy(settings = edit(it.settings))
    }

    private fun change(edit: (BackupChoices) -> BackupChoices) {
        viewModelScope.launch {
            preferences.update(edit)
            scheduler.reschedule()
        }
    }
}
