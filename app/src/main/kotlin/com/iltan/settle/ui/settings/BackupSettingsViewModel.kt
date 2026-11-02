package com.iltan.settle.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.iltan.settle.data.Library
import com.iltan.settle.data.backup.BackupChoices
import com.iltan.settle.data.backup.BackupCounts
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
import com.iltan.settle.domain.backup.RunProgress
import com.iltan.settle.domain.backup.backupDecision
import com.iltan.settle.domain.transfer.NetworkUse
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class BackupSettingsState(
    val loaded: Boolean = false,
    val choices: BackupChoices = BackupChoices(),
    /** Why nothing is backed up right now, if so. */
    val pause: BackupPause? = null,
    val backedUp: Int = 0,
    val waiting: Int = 0,
    val queued: Int = 0,
    val needsAttention: Int = 0,
    /** The run of uploads under way, if any. */
    val run: RunProgress? = null,
    /** When the newest copy was checked in Drive. */
    val lastBackupAtMs: Long? = null,
    val folders: List<FolderSummary> = emptyList(),
) {
    /** Items still to back up, waiting out their time or queued for the next run. */
    val upcoming: Int
        get() = waiting + queued
}

/**
 * Settings → Backup (I6). Its counts are the app's one backup status (ADR-016), so the page, the
 * Backup tab and the chip never disagree.
 */
class BackupSettingsViewModel(
    private val dao: BackupDao,
    private val library: Library,
    private val preferences: BackupPreferences,
    private val scheduler: BackupScheduler,
    private val cloudBackup: CloudBackup,
    private val notifications: BackupNotifications,
    counts: Flow<BackupCounts?>,
    run: Flow<RunProgress?>,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    val state: StateFlow<BackupSettingsState> =
        combine(preferences.choices, counts, run, dao.lastVerifiedAt(), library.folders()) {
                choices,
                counts,
                run,
                lastBackup,
                folders ->
                BackupSettingsState(
                    loaded = counts != null,
                    choices = choices,
                    pause = counts?.pause,
                    backedUp = counts?.backedUp ?: 0,
                    waiting = counts?.waiting ?: 0,
                    queued = counts?.queued ?: 0,
                    needsAttention = counts?.needsAttention ?: 0,
                    run = run,
                    lastBackupAtMs = lastBackup,
                    folders = folders,
                )
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BackupSettingsState())

    /** Plans again, e.g. after the user granted a permission. */
    fun refresh() {
        viewModelScope.launch { scheduler.reschedule() }
    }

    fun setAutomatic(on: Boolean) = updateSettings { it.copy(automatic = on) }

    fun setGrace(millis: Long) = updateSettings { it.copy(graceMillis = millis) }

    fun setOnlyWhileCharging(on: Boolean) = updateSettings { it.copy(onlyWhileCharging = on) }

    fun setUseMobileData(on: Boolean) = updateSettings { it.copy(useMobileData = on) }

    fun setNetworkUse(use: NetworkUse) = change { it.copy(networkUse = use) }

    fun setPaused(paused: Boolean) {
        viewModelScope.launch { cloudBackup.setPaused(paused) }
    }

    fun setFolderBackUp(folder: FolderSummary, backUp: Boolean) {
        viewModelScope.launch {
            library.setFolderBackUp(folder.relativePath, backUp)
            scheduler.reschedule()
        }
    }

    /** Everything chosen for backup, now: grace periods and the charger are skipped (R3). */
    fun backUpEverythingNow() {
        viewModelScope.launch { cloudBackup.backUpNow(everythingChosen(dao, preferences, clock)) }
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

/**
 * The items "Back up everything now" sends: everything chosen for backup, as if automatic backup
 * were on and every wait were over (R3).
 */
suspend fun everythingChosen(
    dao: BackupDao,
    preferences: BackupPreferences,
    clock: () -> Long,
): List<String> {
    val settings = preferences.current().settings.copy(automatic = true, graceMillis = 0)
    return dao.backupCandidates()
        .mapNotNull { it.toCandidate() }
        .filter {
            backupDecision(it.copy(retryAtMillis = null), settings, clock()) == BackupDecision.Ready
        }
        .map { it.itemId.value }
}
