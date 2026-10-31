package com.iltan.settle.data.media

import android.util.Log
import com.iltan.settle.data.db.LibraryDao
import com.iltan.settle.data.db.ScanCursorEntity
import com.iltan.settle.domain.MediaItemId
import com.iltan.settle.domain.scan.VolumeScan
import com.iltan.settle.domain.scan.reconcileScan
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Keeps the local index in step with MediaStore (ADR-012). */
class LibrarySync(
    private val source: MediaSource,
    private val dao: LibraryDao,
    private val hasFullAccess: () -> Boolean,
    private val now: () -> Long = System::currentTimeMillis,
    /** Called after a scan that changed the index. */
    private val onChanged: suspend () -> Unit = {},
) {
    data class State(
        val scanning: Boolean = false,
        /** Set when the last scan saw a mass disappearance and forgot nothing (ADR-006). */
        val breakerTripped: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val mutex = Mutex()
    private val requests =
        MutableSharedFlow<Unit>(
            extraBufferCapacity = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    @Volatile private var fullRequested = false

    /** Asks for a scan soon; bursts of requests (e.g. a camera burst) collapse into one. */
    fun requestScan(full: Boolean = false) {
        if (full) fullRequested = true
        requests.tryEmit(Unit)
    }

    /** Runs requested scans one after another for as long as [scope] lives. */
    @OptIn(FlowPreview::class)
    fun start(scope: CoroutineScope) {
        scope.launch {
            requests.debounce(DEBOUNCE).collect {
                val full = fullRequested
                fullRequested = false
                runCatching { scan(full) }.onFailure { Log.e(TAG, "Scan failed", it) }
            }
        }
    }

    /** Scans every volume now. Does nothing without full media access. */
    suspend fun scan(full: Boolean = false) = mutex.withLock {
        if (!hasFullAccess()) return@withLock
        _state.update { it.copy(scanning = true) }
        var changed = false
        try {
            var tripped = false
            for (volume in source.volumes()) {
                val result = scanVolume(volume, full)
                tripped = result.breakerTripped || tripped
                changed = changed || result.changed
            }
            _state.update { it.copy(breakerTripped = tripped) }
        } finally {
            _state.update { it.copy(scanning = false) }
        }
        if (changed) onChanged()
    }

    private data class VolumeResult(val breakerTripped: Boolean, val changed: Boolean)

    private suspend fun scanVolume(volume: String, forceFull: Boolean): VolumeResult {
        val started = now()
        val version = source.version(volume) ?: return VolumeResult(false, false)
        val cursor = dao.cursor(volume)
        val idsTrusted = cursor == null || cursor.mediaStoreVersion == version
        // Missing files can only be found again in a full scan (see VolumeScan.rows).
        val full = forceFull || cursor == null || !idsTrusted || dao.hasMissingFiles(volume)
        // Read the generation before querying, so changes made during the scan are seen next time.
        val generation = source.generation(volume)
        val rows = source.rows(volume, sinceGeneration = if (full) null else cursor.generation)
        val ids = source.ids(volume)
        val known = dao.knownFiles(volume).map { it.toKnownFile() }

        val plan =
            reconcileScan(known, VolumeScan(volume, rows, ids, idsTrusted)) {
                MediaItemId.generate(now())
            }
        dao.applyScan(plan, ScanCursorEntity(volume, version, generation, now()), now())

        Log.i(
            TAG,
            "Scanned $volume (${if (full) "full" else "incremental"}, ${now() - started} ms): " +
                "${plan.inserts.size} new, ${plan.updates.size} updated, " +
                "${plan.forgets.size} forgotten, ${plan.missing.size} missing" +
                if (plan.breakerTripped) ", circuit breaker tripped" else "",
        )
        val changed =
            plan.inserts.isNotEmpty() ||
                plan.updates.isNotEmpty() ||
                plan.forgets.isNotEmpty() ||
                plan.missing.isNotEmpty()
        return VolumeResult(plan.breakerTripped, changed)
    }

    private companion object {
        const val TAG = "LibrarySync"
        val DEBOUNCE = 500.milliseconds
    }
}
