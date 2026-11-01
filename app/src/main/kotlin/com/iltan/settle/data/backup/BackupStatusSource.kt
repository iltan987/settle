package com.iltan.settle.data.backup

import com.iltan.settle.data.db.BackupDao
import com.iltan.settle.data.db.TrashedEntry
import com.iltan.settle.domain.backup.BackupHeadline
import com.iltan.settle.domain.backup.BackupPause
import com.iltan.settle.domain.backup.BackupStatusInputs
import com.iltan.settle.domain.backup.RunProgress
import com.iltan.settle.domain.backup.backupHeadline
import com.iltan.settle.domain.backup.planBackup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Counts from the backup plan, for the headline and the Backup tab. */
data class BackupCounts(
    val pause: BackupPause?,
    val waiting: Int,
    val queued: Int,
    val needsAttention: Int,
    val backedUp: Int,
)

/**
 * The one backup status the whole app shows (ADR-016): the top bar's chip, the notice cards, the
 * Backup tab's header and the navigation bar's dot all read [headline], so they never disagree.
 */
class BackupStatusSource(
    dao: BackupDao,
    preferences: BackupPreferences,
    cloudBackup: CloudBackup,
    trash: Flow<List<TrashedEntry>>,
    scope: CoroutineScope,
    clock: () -> Long = System::currentTimeMillis,
) {
    /** Waiting turns into queued with time alone, so the plan is redone every minute too. */
    private val minutes = flow {
        while (true) {
            emit(Unit)
            delay(MINUTE_MILLIS)
        }
    }

    val counts: StateFlow<BackupCounts?> =
        combine(
                cloudBackup.pause,
                preferences.choices,
                dao.observeBackupCandidates(),
                dao.backedUpCount(),
                minutes,
            ) { pause, choices, rows, backedUp, _ ->
                val plan =
                    planBackup(
                        rows.mapNotNull { it.toCandidate() },
                        choices.settings,
                        pause,
                        clock(),
                    )
                BackupCounts(
                    pause = plan.pause,
                    waiting = plan.waiting,
                    queued = plan.queue.size,
                    needsAttention = plan.needsAttention,
                    backedUp = backedUp,
                )
            }
            .distinctUntilChanged()
            .stateIn(scope, SharingStarted.WhileSubscribed(STOP_MILLIS), null)

    /** The run in progress, with the bytes of the uploads still going; null between runs. */
    val run: Flow<RunProgress?> =
        combine(cloudBackup.totals, cloudBackup.progress) { totals, progress ->
            totals?.let { t ->
                RunProgress(
                    doneItems = t.doneItems,
                    totalItems = t.totalItems,
                    sentBytes = t.doneBytes + progress.values.sumOf { it.sentBytes },
                    totalBytes = t.totalBytes,
                    bytesPerSecond = t.bytesPerSecond,
                )
            }
        }

    private val restoreProblems: Flow<Int> =
        trash.map { entries -> entries.count { it.restoreProblem != null } }.distinctUntilChanged()

    /** Null until the first read, so the chip never flashes a false "All in Drive". */
    val headline: StateFlow<BackupHeadline?> =
        combine(counts, run, restoreProblems) { counts, run, restoreProblems ->
                counts?.let {
                    backupHeadline(
                        BackupStatusInputs(
                            pause = it.pause,
                            needsAttention = it.needsAttention + restoreProblems,
                            backedUp = it.backedUp,
                            waiting = it.waiting,
                            queued = it.queued,
                            run = run,
                        )
                    )
                }
            }
            .stateIn(scope, SharingStarted.WhileSubscribed(STOP_MILLIS), null)

    /** The connected account's address, for the avatar's initial; null when not connected. */
    val accountEmail: Flow<String?> = dao.account().map { it?.email }.distinctUntilChanged()

    private companion object {
        const val MINUTE_MILLIS = 60_000L
        const val STOP_MILLIS = 5_000L
    }
}
