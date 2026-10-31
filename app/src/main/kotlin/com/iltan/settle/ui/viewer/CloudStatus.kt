package com.iltan.settle.ui.viewer

import com.iltan.settle.data.backup.UploadProgress
import com.iltan.settle.data.db.UploadEntity
import com.iltan.settle.domain.CloudState
import com.iltan.settle.domain.backup.UploadProblem

/** What the viewer shows about an item's backup. */
sealed interface CloudStatus {
    data object NotConnected : CloudStatus

    /** Never backed up, or an upload that stopped and continues on the next try. */
    data object NotBackedUp : CloudStatus

    /** Queued or uploading; [fraction] is null while queued. */
    data class Uploading(val fraction: Float?) : CloudStatus

    /** The bytes are in Drive; Drive hasn't confirmed every checksum yet. */
    data object Checking : CloudStatus

    data class BackedUp(val driveFileId: String) : CloudStatus

    data class Problem(val problem: UploadProblem) : CloudStatus
}

fun cloudStatus(
    connected: Boolean,
    upload: UploadEntity?,
    progress: UploadProgress?,
    requested: Boolean = false,
): CloudStatus =
    when {
        progress != null ->
            CloudStatus.Uploading(
                progress.totalBytes.takeIf { it > 0 }?.let { progress.sentBytes.toFloat() / it }
            )
        requested && upload?.state != CloudState.VERIFIED -> CloudStatus.Uploading(null)
        upload?.state == CloudState.VERIFIED -> CloudStatus.BackedUp(upload.driveFileId)
        !connected -> CloudStatus.NotConnected
        upload?.problem != null -> CloudStatus.Problem(upload.problem)
        upload?.state == CloudState.UPLOADED -> CloudStatus.Checking
        else -> CloudStatus.NotBackedUp
    }
