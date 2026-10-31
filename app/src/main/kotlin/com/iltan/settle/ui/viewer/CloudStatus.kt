package com.iltan.settle.ui.viewer

import com.iltan.settle.data.backup.UploadProgress
import com.iltan.settle.data.db.UploadEntity
import com.iltan.settle.domain.CloudState
import com.iltan.settle.domain.backup.BackupDecision
import com.iltan.settle.domain.backup.BackupPause
import com.iltan.settle.domain.backup.UploadProblem

/** What the viewer shows about an item's backup. */
sealed interface CloudStatus {
    data object NotConnected : CloudStatus

    /** Never backed up, or an upload that stopped and continues on the next try. */
    data object NotBackedUp : CloudStatus

    /** Its folder isn't chosen for backup, or automatic backup is off. */
    data object NotChosen : CloudStatus

    /** In its grace period (R2), or waiting to retry; backs up on its own after [untilMillis]. */
    data class Waiting(val untilMillis: Long) : CloudStatus

    /** Ready; the next automatic run takes it. */
    data object Queued : CloudStatus

    /** Queued or uploading; [fraction] is null while queued. */
    data class Uploading(val fraction: Float?) : CloudStatus

    /** The bytes are in Drive; Drive hasn't confirmed every checksum yet. */
    data object Checking : CloudStatus

    data class BackedUp(val driveFileId: String) : CloudStatus

    data class Problem(val problem: UploadProblem) : CloudStatus

    /** Backup as a whole waits for the user (R6): paused, reconnect, Drive full, or access. */
    data class Paused(val reason: BackupPause) : CloudStatus
}

fun cloudStatus(
    connected: Boolean,
    upload: UploadEntity?,
    progress: UploadProgress?,
    requested: Boolean = false,
    /** What automatic backup will do with it; null when unknown. */
    decision: BackupDecision? = null,
    /** Why no backup runs at all, if so. */
    pause: BackupPause? = null,
    /** Whether a run of uploads is going, which takes a request soon. */
    running: Boolean = true,
): CloudStatus =
    when {
        progress != null ->
            CloudStatus.Uploading(
                progress.totalBytes.takeIf { it > 0 }?.let { progress.sentBytes.toFloat() / it }
            )
        upload?.state == CloudState.VERIFIED -> CloudStatus.BackedUp(upload.driveFileId)
        !connected || pause == BackupPause.NOT_CONNECTED -> CloudStatus.NotConnected
        // A request waits for the user here too, so no spinner that never ends.
        pause != null -> CloudStatus.Paused(pause)
        // Without a run, a request waits for the next automatic backup.
        requested -> if (running) CloudStatus.Uploading(null) else CloudStatus.Queued
        upload?.problem != null -> CloudStatus.Problem(upload.problem)
        upload?.state == CloudState.UPLOADED -> CloudStatus.Checking
        decision is BackupDecision.WaitUntil -> CloudStatus.Waiting(decision.millis)
        decision == BackupDecision.Ready -> CloudStatus.Queued
        decision == BackupDecision.Skip -> CloudStatus.NotChosen
        else -> CloudStatus.NotBackedUp
    }
