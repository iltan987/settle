package com.iltan.settle.domain.deletion

import com.iltan.settle.domain.backup.Clock
import com.iltan.settle.domain.backup.DriveApi
import com.iltan.settle.domain.backup.DriveException
import com.iltan.settle.domain.backup.DriveFailure
import com.iltan.settle.domain.backup.HOUR_MILLIS

/** The queue of Drive changes (ADR-015). */
interface DriveOpStore {
    /** Operations whose time has come, oldest first. */
    suspend fun due(nowMillis: Long): List<DriveOp>

    /** Removes [op] if it is still the queued operation for its file (not replaced meanwhile). */
    suspend fun done(op: DriveOp)

    /** Replaces [op] with its next try, if it is still the queued operation for its file. */
    suspend fun retry(op: DriveOp, notBeforeMillis: Long)

    /** When the next queued operation is due, or null when the queue is empty. */
    suspend fun nextDueAt(): Long?
}

sealed interface DriveChangesResult {
    /** Every due operation ran; [nextDueAtMillis] is when to run again, null when nothing waits. */
    data class Done(val nextDueAtMillis: Long?, val lostCopies: List<DriveOp>) : DriveChangesResult

    /** Google needs the user to sign in again; nothing more runs until then. */
    data object AuthNeeded : DriveChangesResult
}

/**
 * Runs the queued Drive changes that are due. Each is idempotent: a file that is already gone
 * counts as trashed or deleted, and a failed change waits and is tried again (1 minute, doubling up
 * to 6 hours). A copy that can't be untrashed because it no longer exists is reported, so the item
 * can say so.
 */
class DriveChanges(
    private val drive: DriveApi,
    private val store: DriveOpStore,
    private val clock: Clock,
    private val log: (String) -> Unit = {},
) {
    suspend fun run(): DriveChangesResult {
        val lost = mutableListOf<DriveOp>()
        for (op in store.due(clock.nowMillis())) {
            try {
                when (op.kind) {
                    DriveOpKind.TRASH -> drive.setTrashed(op.driveFileId, true)
                    DriveOpKind.UNTRASH -> drive.setTrashed(op.driveFileId, false)
                    DriveOpKind.DELETE -> drive.delete(op.driveFileId)
                }
                store.done(op)
            } catch (e: DriveException) {
                log("${op.kind} ${op.driveFileId}: ${e.failure}: ${e.message}")
                when (e.failure) {
                    DriveFailure.NOT_FOUND -> {
                        if (op.kind == DriveOpKind.UNTRASH) lost += op
                        store.done(op)
                    }
                    DriveFailure.AUTH_NEEDED -> return DriveChangesResult.AuthNeeded
                    else -> store.retry(op, clock.nowMillis() + retryDelayMillis(op.attempts + 1))
                }
            }
        }
        return DriveChangesResult.Done(store.nextDueAt(), lost)
    }

    companion object {
        fun retryDelayMillis(attempt: Int): Long =
            minOf(60_000L shl (attempt - 1).coerceIn(0, 10), 6 * HOUR_MILLIS)
    }
}
