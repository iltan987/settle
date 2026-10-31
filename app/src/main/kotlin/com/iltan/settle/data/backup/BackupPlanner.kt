package com.iltan.settle.data.backup

import com.iltan.settle.data.db.BackupCandidateRow
import com.iltan.settle.data.db.BackupDao
import com.iltan.settle.domain.MediaItemId
import com.iltan.settle.domain.backup.BackupCandidate
import com.iltan.settle.domain.backup.BackupPause
import com.iltan.settle.domain.backup.BackupPlan
import com.iltan.settle.domain.backup.folderBacksUp
import com.iltan.settle.domain.backup.planBackup

/** Feeds the library's current state to the pure backup rules (ADR-014). */
class BackupPlanner(
    private val dao: BackupDao,
    private val preferences: BackupPreferences,
    private val canReadOriginals: () -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun plan(): BackupPlan {
        val choices = preferences.current()
        return planBackup(
            candidates = dao.backupCandidates().mapNotNull { it.toCandidate() },
            settings = choices.settings,
            pause = pause(choices.blocked),
            nowMillis = clock(),
        )
    }

    /** Why nothing may run, apart from the user's own pause, which the rules read from settings. */
    suspend fun pause(blocked: BackupPause?): BackupPause? =
        when {
            dao.accountEmail() == null -> BackupPause.NOT_CONNECTED
            !canReadOriginals() -> BackupPause.NO_ORIGINAL_ACCESS
            else -> blocked
        }
}

fun BackupCandidateRow.toCandidate(): BackupCandidate? =
    MediaItemId.parseOrNull(id)?.let {
        BackupCandidate(
            itemId = it,
            localState = localState,
            sizeBytes = sizeBytes,
            takenAtMillis = takenAtMs,
            addedAtMillis = dateAddedS * 1000,
            folderBacksUp = folderBacksUp(relativePath, backUp),
            cloudState = cloudState,
            problem = problem,
            retryAtMillis = retryAtMs,
            attempts = attempts ?: 0,
            requestedAtMillis = requestedAtMs,
        )
    }
