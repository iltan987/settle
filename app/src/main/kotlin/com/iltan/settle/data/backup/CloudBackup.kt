package com.iltan.settle.data.backup

import com.iltan.settle.data.db.BackupDao
import com.iltan.settle.data.drive.MediaStoreOriginals
import com.iltan.settle.domain.backup.BackupDecision
import com.iltan.settle.domain.backup.BackupPause
import com.iltan.settle.domain.backup.backupDecision
import com.iltan.settle.domain.backup.backupPause
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge

/** What screens need from backup: progress, outcomes, and "Back up now" (R3). */
class CloudBackup(
    private val runner: BackupRunner,
    private val scheduler: BackupScheduler,
    private val dao: BackupDao,
    private val preferences: BackupPreferences,
    private val originals: MediaStoreOriginals,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Uploads under way, by item ID. */
    val progress: StateFlow<Map<String, UploadProgress>> = runner.progress

    /** Whether a run of uploads is going, so a request is about to be taken. */
    val running: StateFlow<Boolean> = runner.running

    /**
     * Why no backup runs at all right now, the user's own pause included (R6); null when it may.
     * Media location access is read on each change of the account or the settings.
     */
    val pause: Flow<BackupPause?> =
        combine(dao.account(), preferences.choices) { account, choices ->
            backupPause(
                connected = account != null,
                blocked = choices.blocked,
                canReadOriginals = originals::canReadOriginals,
                pausedByUser = choices.settings.paused,
            )
        }

    private val refused = MutableSharedFlow<BackupOutcome>(extraBufferCapacity = 4)

    /** How backups the user asked for ended, or why they couldn't start. */
    val outcomes: Flow<BackupOutcome> = merge(runner.outcomes, refused)

    /** Whether the user asked to back this item up and it hasn't finished yet. */
    fun requested(itemId: String): Flow<Boolean> = dao.observeRequest(itemId).map { it != null }

    /** What automatic backup will do with this item; null once it is verified or gone. */
    fun decision(itemId: String): Flow<BackupDecision?> =
        combine(dao.observeBackupCandidate(itemId), preferences.choices) { row, choices ->
            row?.toCandidate()?.let { backupDecision(it, choices.settings, clock()) }
        }

    /** Starts backing these items up now; call while Settle is visible. */
    suspend fun backUpNow(itemIds: Collection<String>) {
        if (!originals.canReadOriginals()) {
            itemIds.forEach { refused.emit(BackupOutcome.NeedsOriginalAccess(it)) }
            return
        }
        if (scheduler.backUpNow(itemIds) == BackupScheduler.NowResult.DEFERRED) {
            // The automatic job takes requests too; make sure it is planned, and say so.
            scheduler.reschedule()
            itemIds.forEach { refused.emit(BackupOutcome.Deferred(it)) }
        }
    }
}
