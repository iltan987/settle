package com.iltan.settle.ui.backup

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.iltan.settle.data.backup.BackupChoices
import com.iltan.settle.data.backup.BackupCounts
import com.iltan.settle.data.db.PendingEntry
import com.iltan.settle.data.db.TrashedEntry
import com.iltan.settle.data.trash.RestoreProgress
import com.iltan.settle.domain.backup.BackupDecision
import com.iltan.settle.domain.backup.BackupHeadline
import com.iltan.settle.domain.backup.RunProgress
import com.iltan.settle.ui.trash.RestoreBatch
import com.iltan.settle.ui.trash.RestoreTracker
import com.iltan.settle.ui.trash.restoring
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** The Backup tab's item sections, in the order they show (J). */
@Immutable
data class BackupSections(
    /** Uploads that stopped in a way only the user can resolve. */
    val attention: List<PendingEntry> = emptyList(),
    /** While a run goes: what it is uploading now, then what it still has to do. */
    val inRun: List<PendingEntry> = emptyList(),
    /** Waiting out their time on the phone. */
    val waiting: List<PendingEntry> = emptyList(),
    /** When the first waiting item may go. */
    val waitingUntilMs: Long? = null,
    /** Ready for the next run. */
    val queued: List<PendingEntry> = emptyList(),
) {
    val isEmpty: Boolean
        get() = attention.isEmpty() && inRun.isEmpty() && waiting.isEmpty() && queued.isEmpty()
}

/**
 * Sorts the items without a verified copy into the tab's sections by what automatic backup will do
 * with them. Items not chosen for backup aren't listed: nothing is coming for them.
 */
fun backupSections(
    pending: List<PendingEntry>,
    decisions: Map<String, BackupDecision>,
    uploading: Set<String>,
    running: Boolean,
): BackupSections {
    val attention = mutableListOf<PendingEntry>()
    val now = mutableListOf<PendingEntry>()
    val waiting = mutableListOf<PendingEntry>()
    val queued = mutableListOf<PendingEntry>()
    var until: Long? = null
    pending.forEach { item ->
        val decision = decisions[item.entry.id]
        when {
            item.entry.id in uploading -> now += item
            decision == BackupDecision.NeedsAttention -> attention += item
            decision is BackupDecision.WaitUntil -> {
                waiting += item
                until = minOf(until ?: decision.millis, decision.millis)
            }
            decision == BackupDecision.Ready -> queued += item
        }
    }
    return if (running) {
        BackupSections(attention, now + queued, waiting, until, emptyList())
    } else {
        BackupSections(attention, now, waiting, until, queued)
    }
}

/** A restore from the trash while it runs, as the tab's card shows it (J5). */
@Immutable
data class RestoreCardState(
    val batch: RestoreBatch,
    /** The items still coming, for the card's thumbnails. */
    val items: List<TrashedEntry>,
    val receivedBytes: Long,
)

@Immutable
data class BackupTabState(
    val loaded: Boolean = false,
    val headline: BackupHeadline? = null,
    val counts: BackupCounts? = null,
    val run: RunProgress? = null,
    val choices: BackupChoices = BackupChoices(),
    val lastBackupAtMs: Long? = null,
    val sections: BackupSections = BackupSections(),
    /** Restores from the trash that stopped; they are answered in the trash. */
    val restoreProblems: Int = 0,
    val restore: RestoreCardState? = null,
    /** Files that vanished at once and wait for the user's answer (G). */
    val massCount: Int = 0,
    val checkingMass: Boolean = false,
)

/** The Backup tab (J): the one status, then what needs the user, then what is coming. */
class BackupTabViewModel(
    pending: Flow<List<PendingEntry>>,
    decisions: Flow<Map<String, BackupDecision>?>,
    uploading: Flow<Set<String>>,
    headline: Flow<BackupHeadline?>,
    counts: Flow<BackupCounts?>,
    run: Flow<RunProgress?>,
    choices: Flow<BackupChoices>,
    lastBackupAt: Flow<Long?>,
    trash: Flow<List<TrashedEntry>>,
    restoring: Flow<RestoreProgress?>,
    breakerCount: Flow<Int>,
    checkingMass: Flow<Boolean>,
) : ViewModel() {

    private val sections: Flow<BackupSections?> =
        combine(pending, decisions, uploading, run) { pending, decisions, uploading, run ->
                decisions?.let { backupSections(pending, it, uploading, run != null) }
            }
            .flowOn(Dispatchers.Default)

    private val tracker = RestoreTracker()

    private val restore: Flow<Pair<Int, RestoreCardState?>> =
        combine(trash, restoring) { entries, progress ->
            val batch = tracker.update(entries).batch
            val problems = entries.count { it.restoreProblem != null }
            problems to
                batch?.let {
                    RestoreCardState(
                        it,
                        entries.filter { e -> e.restoring },
                        (it.bytesTotal - it.bytesLeft + (progress?.receivedBytes ?: 0)).coerceIn(
                            0,
                            it.bytesTotal,
                        ),
                    )
                }
        }

    private val status =
        combine(headline, counts, run, choices, lastBackupAt) { headline, counts, run, choices, last
            ->
            BackupTabState(
                headline = headline,
                counts = counts,
                run = run,
                choices = choices,
                lastBackupAtMs = last,
            )
        }

    private val mass = combine(breakerCount, checkingMass) { count, checking -> count to checking }

    val state: StateFlow<BackupTabState> =
        combine(status, sections, restore, mass) { status, sections, restore, mass ->
                status.copy(
                    loaded = status.headline != null && sections != null,
                    sections = sections ?: BackupSections(),
                    restoreProblems = restore.first,
                    restore = restore.second,
                    massCount = mass.first,
                    checkingMass = mass.second,
                )
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BackupTabState())
}

/** The uploads under way, by item ID, as a set. */
fun Flow<Map<String, *>>.ids(): Flow<Set<String>> = map { it.keys }.distinctUntilChanged()
