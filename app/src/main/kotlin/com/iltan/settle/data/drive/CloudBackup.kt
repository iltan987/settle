package com.iltan.settle.data.drive

import android.os.SystemClock
import android.util.Log
import com.iltan.settle.data.db.BackupDao
import com.iltan.settle.domain.MediaItemId
import com.iltan.settle.domain.backup.BackupEngine
import com.iltan.settle.domain.backup.BackupResult
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Bytes sent so far; [totalBytes] is 0 while the upload is queued. */
data class UploadProgress(val sentBytes: Long, val totalBytes: Long)

/** How a requested backup ended, for the UI to report. */
sealed interface BackupOutcome {
    val itemId: String

    data class Done(override val itemId: String, val result: BackupResult) : BackupOutcome

    /** Media location access is missing, so only a redacted copy could be read. */
    data class NeedsOriginalAccess(override val itemId: String) : BackupOutcome
}

/**
 * Runs backups one at a time in the app's process (R3). Uploads left unfinished by a killed process
 * continue when the app starts again (R4); background jobs arrive in Phase 3.
 */
class CloudBackup(
    private val engine: BackupEngine,
    private val dao: BackupDao,
    private val originals: MediaStoreOriginals,
) {
    private val queue = Channel<String>(Channel.UNLIMITED)

    private val _progress = MutableStateFlow<Map<String, UploadProgress>>(emptyMap())
    /** Queued and running uploads by item ID. */
    val progress: StateFlow<Map<String, UploadProgress>> = _progress.asStateFlow()

    private val _outcomes = MutableSharedFlow<BackupOutcome>(extraBufferCapacity = 16)
    val outcomes: SharedFlow<BackupOutcome> = _outcomes.asSharedFlow()

    fun start(scope: CoroutineScope) {
        scope.launch { for (itemId in queue) run(itemId) }
        scope.launch {
            if (dao.accountEmail() != null) dao.unfinishedUploads().forEach(::backUpNow)
        }
    }

    /** Queues the item unless it is already queued or uploading. */
    fun backUpNow(itemId: String) {
        var added = false
        _progress.update {
            if (itemId in it) it
            else {
                added = true
                it + (itemId to UploadProgress(0, 0))
            }
        }
        if (added) queue.trySend(itemId)
    }

    private suspend fun run(itemId: String) {
        try {
            val id = MediaItemId.parseOrNull(itemId) ?: return
            if (!originals.canReadOriginals()) {
                _outcomes.emit(BackupOutcome.NeedsOriginalAccess(itemId))
                return
            }
            val started = SystemClock.elapsedRealtime()
            var total = 0L
            var firstOffset = -1L
            val result =
                engine.backUp(id) { sent, size ->
                    total = size
                    if (firstOffset < 0) firstOffset = sent
                    _progress.update { it + (itemId to UploadProgress(sent, size)) }
                }
            logThroughput(itemId, result, total, firstOffset, started)
            _outcomes.emit(BackupOutcome.Done(itemId, result))
        } finally {
            _progress.update { it - itemId }
        }
    }

    private fun logThroughput(
        itemId: String,
        result: BackupResult,
        total: Long,
        resumedAt: Long,
        started: Long,
    ) {
        val seconds = (SystemClock.elapsedRealtime() - started) / 1000.0
        val sent = (total - resumedAt.coerceAtLeast(0)) / MIB
        Log.i(
            TAG,
            "Backup of $itemId: $result. File %.1f MiB, started at %.1f MiB, sent %.1f MiB in %.1f s (%.1f MiB/s)"
                .format(
                    Locale.ROOT,
                    total / MIB,
                    resumedAt.coerceAtLeast(0) / MIB,
                    sent,
                    seconds,
                    sent / seconds,
                ),
        )
    }

    private companion object {
        const val TAG = "CloudBackup"
        const val MIB = 1024.0 * 1024.0
    }
}
