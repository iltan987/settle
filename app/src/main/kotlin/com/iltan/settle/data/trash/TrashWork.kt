package com.iltan.settle.data.trash

import com.iltan.settle.data.backup.BackupPreferences
import com.iltan.settle.data.db.TrashDao
import com.iltan.settle.domain.MediaItemId
import com.iltan.settle.domain.backup.BackupPause
import com.iltan.settle.domain.deletion.DriveChanges
import com.iltan.settle.domain.deletion.DriveChangesResult
import com.iltan.settle.domain.deletion.RestoreDownload
import com.iltan.settle.domain.deletion.RestoreProblem
import com.iltan.settle.domain.deletion.RestoreResult
import com.iltan.settle.domain.deletion.TrashChanges
import com.iltan.settle.domain.deletion.expiredItems
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Progress of the restore job: items left, and the current download's item and bytes. */
data class RestoreProgress(
    val itemsLeft: Int,
    val receivedBytes: Long,
    val totalBytes: Long,
    val itemId: String = "",
) {
    val fraction: Float
        get() = if (totalBytes > 0) (receivedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
}

/** What the trash's background jobs do (ADR-015). */
class TrashWork(
    private val dao: TrashDao,
    private val store: TrashStore,
    private val driveChanges: DriveChanges,
    private val restoreDownload: RestoreDownload,
    private val preferences: BackupPreferences,
    private val betweenScans: suspend (suspend () -> Unit) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
) {
    // The change job and the restore job both run the queue; one at a time, so no change is
    // sent twice at once.
    private val queueLock = Mutex()

    private val _restoring = MutableStateFlow<RestoreProgress?>(null)

    /** The restore download in progress, for the trash and the Backup tab; null when none runs. */
    val restoring: StateFlow<RestoreProgress?> = _restoring.asStateFlow()

    /** Runs the queued Drive changes that are due. */
    suspend fun runDriveChanges() = queueLock.withLock {
        when (val result = driveChanges.run()) {
            DriveChangesResult.AuthNeeded -> preferences.block(BackupPause.AUTH_NEEDED)
            is DriveChangesResult.Done ->
                for (op in result.lostCopies) {
                    log("${op.itemId}: Drive no longer has ${op.driveFileId}; backing it up again")
                    dao.forgetLostCopy(op.itemId.value, op.driveFileId)
                }
        }
    }

    /** Downloads every requested restore that hasn't stopped, one after another. */
    suspend fun runRestores(onProgress: (RestoreProgress) -> Unit = {}) {
        try {
            restoreAll(onProgress)
        } finally {
            _restoring.value = null
        }
    }

    private suspend fun restoreAll(onProgress: (RestoreProgress) -> Unit) {
        // A copy must be out of Drive's trash before it comes back.
        runDriveChanges()
        while (true) {
            val waiting = dao.restoreRequests().filter { it.problem == null }
            val request = waiting.firstOrNull() ?: return
            val itemId = checkNotNull(MediaItemId.parseOrNull(request.itemId))
            val result =
                restoreDownload.restore(itemId) { received, total ->
                    val progress = RestoreProgress(waiting.size, received, total, request.itemId)
                    _restoring.value = progress
                    onProgress(progress)
                }
            log("$itemId: restore $result")
            when (result) {
                is RestoreResult.Restored ->
                    betweenScans {
                        dao.restored(
                            request.itemId,
                            MediaStoreTrash.VOLUME,
                            result.file.mediaStoreId,
                            result.file.snapshot.sizeBytes,
                            result.file.snapshot.dateModifiedSeconds,
                        )
                    }
                is RestoreResult.Stopped -> {
                    dao.setRestoreProblem(request.itemId, result.problem)
                    if (result.problem == RestoreProblem.AUTH_NEEDED) {
                        preferences.block(BackupPause.AUTH_NEEDED)
                        return
                    }
                }
            }
        }
    }

    /** Forgets items whose trash time is over and of which nothing is left (ADR-015). */
    suspend fun expire() {
        val now = clock()
        val gone = expiredItems(store.expired(now), now)
        if (gone.isNotEmpty()) {
            log("Forgetting ${gone.size} items whose trash time is over")
            betweenScans { dao.applyTrashChanges(TrashChanges(purge = gone)) }
        }
    }
}
