package com.iltan.settle.ui.trash

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.iltan.settle.data.db.TrashedEntry
import com.iltan.settle.data.trash.Deletion
import com.iltan.settle.data.trash.RestoreProgress
import java.time.ZoneId
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the trash shows (F). */
data class TrashState(
    val groups: List<TrashGroup> = emptyList(),
    val loading: Boolean = true,
    /** The download in progress, for its cell's ring. */
    val progress: RestoreProgress? = null,
    /** A restore from Drive while it runs. */
    val batch: RestoreBatch? = null,
    /** Items just back on the phone, shown with a check for a moment (F5). */
    val justRestored: Set<String> = emptySet(),
    /** How the last restore from Drive ended, for the failure notice (F6). */
    val lastOutcome: RestoreOutcome? = null,
) {
    val entries: List<TrashedEntry>
        get() = groups.flatMap { it.items }

    /** Items in the trash proper, which Empty trash deletes. */
    val inTrash: Int
        get() = groups.sumOf { group -> group.items.count { it.inTrash && it.id !in justRestored } }

    val waiting: List<TrashedEntry>
        get() = groups.filter { it.kind == GroupKind.WAITING }.flatMap { it.items }

    val failed: List<TrashedEntry>
        get() = entries.filter { it.restoreProblem != null }
}

class TrashViewModel(
    deletion: Deletion,
    restoring: Flow<RestoreProgress?>,
    private val zone: ZoneId = ZoneId.systemDefault(),
) : ViewModel() {
    private val tracker = RestoreTracker()
    private val lingering = MutableStateFlow<Map<String, TrashedEntry>>(emptyMap())
    private val tracked = MutableStateFlow(Tracked())
    private val outcomes = Channel<RestoreOutcome>(Channel.BUFFERED)

    private data class Tracked(
        val batch: RestoreBatch? = null,
        val lastOutcome: RestoreOutcome? = null,
    )

    /** Each restore from Drive that brought something back, once, for its message (F6). */
    val finished: Flow<RestoreOutcome> = outcomes.receiveAsFlow()

    private val trash =
        deletion.trash.onEach { entries ->
            val update = tracker.update(entries)
            tracked.update { Tracked(update.batch, update.outcome ?: it.lastOutcome) }
            update.outcome?.let { if (it.restored > 0 && it.failed == 0) outcomes.trySend(it) }
            if (update.justRestored.isNotEmpty()) {
                lingering.update { it + update.justRestored.associateBy { entry -> entry.id } }
                viewModelScope.launch {
                    delay(CHECK_MILLIS)
                    lingering.update { it - update.justRestored.map { entry -> entry.id }.toSet() }
                }
            }
        }

    val state: StateFlow<TrashState> =
        combine(trash, lingering, tracked, restoring) { entries, lingering, tracked, progress ->
                // A restored item keeps its place until its check has been seen.
                val shown =
                    if (lingering.isEmpty()) entries
                    else {
                        (entries + lingering.values.filter { l -> entries.none { it.id == l.id } })
                            .sortedWith(NEWEST_FIRST)
                    }
                TrashState(
                    groups = trashGroups(shown, zone),
                    loading = false,
                    progress = progress,
                    batch = tracked.batch,
                    justRestored = lingering.keys,
                    lastOutcome = tracked.lastOutcome,
                )
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TrashState())

    private companion object {
        /** How long a restored item shows its check before it leaves (F5). */
        const val CHECK_MILLIS = 600L

        val NEWEST_FIRST =
            compareByDescending<TrashedEntry> { it.trashedAtMs ?: 0 }
                .thenByDescending { it.takenAtMs }
    }
}
