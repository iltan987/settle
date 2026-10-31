package com.iltan.settle.domain.backup

import com.iltan.settle.domain.CloudState
import com.iltan.settle.domain.LocalState
import com.iltan.settle.domain.MediaItemId

const val HOUR_MILLIS = 60L * 60 * 1000

/** How long a new item waits before automatic backup, unless the user changes it (R2). */
const val DEFAULT_GRACE_MILLIS = 24 * HOUR_MILLIS

/** The user's backup choices (ADR-014). */
data class BackupSettings(
    /** Back up new items on their own; "Back up now" works either way. */
    val automatic: Boolean = true,
    val graceMillis: Long = DEFAULT_GRACE_MILLIS,
    /** Automatic backup waits for the charger, which in practice means overnight. */
    val onlyWhileCharging: Boolean = true,
    val useMobileData: Boolean = false,
    /** The user paused all backup. */
    val paused: Boolean = false,
)

/** Only the camera's own folder is backed up until the user picks more (spec defaults). */
fun backsUpByDefault(relativePath: String): Boolean =
    relativePath.equals("DCIM/Camera/", ignoreCase = true)

/** A folder's backup choice: the user's switch if they set one, otherwise the default. */
fun folderBacksUp(relativePath: String, userChoice: Boolean?): Boolean =
    userChoice ?: backsUpByDefault(relativePath)

/** An item that may need backing up, as the library reports it. */
data class BackupCandidate(
    val itemId: MediaItemId,
    val localState: LocalState,
    val sizeBytes: Long,
    val takenAtMillis: Long,
    /** When the file appeared on the phone (MediaStore `DATE_ADDED`). */
    val addedAtMillis: Long,
    val folderBacksUp: Boolean,
    val cloudState: CloudState,
    val problem: UploadProblem? = null,
    /** Set after a failed try: not before this time. */
    val retryAtMillis: Long? = null,
    /** Failed tries since the last success. */
    val attempts: Int = 0,
    /** Set when the user asked to back this item up now. */
    val requestedAtMillis: Long? = null,
)

sealed interface BackupDecision {
    /** Back it up in the next run. */
    data object Ready : BackupDecision

    /** Back it up once this time has come (grace period or retry wait). */
    data class WaitUntil(val millis: Long) : BackupDecision

    /** Not backed up: already done, gone, or not chosen for backup. */
    data object Skip : BackupDecision

    /** Tried and failed in a way only the user can resolve; never retried on its own. */
    data object NeedsAttention : BackupDecision
}

/**
 * Whether and when an item is backed up (R2, R3). An explicit request skips the grace period and
 * any retry wait; automatic backup honours both and only covers folders chosen for backup.
 */
fun backupDecision(
    c: BackupCandidate,
    settings: BackupSettings,
    nowMillis: Long,
): BackupDecision {
    if (c.localState != LocalState.PRESENT || c.cloudState == CloudState.VERIFIED) {
        return BackupDecision.Skip
    }
    if (c.requestedAtMillis != null) return BackupDecision.Ready
    if (!settings.automatic || !c.folderBacksUp) return BackupDecision.Skip
    if (c.problem == UploadProblem.CHECKSUM_MISMATCH && c.attempts > MISMATCH_RETRIES) {
        return BackupDecision.NeedsAttention
    }
    val notBefore = maxOf(c.addedAtMillis + settings.graceMillis, c.retryAtMillis ?: 0)
    return if (nowMillis < notBefore) BackupDecision.WaitUntil(notBefore) else BackupDecision.Ready
}

/** An item whose copy didn't verify is retried once with a fresh upload, then left to the user. */
const val MISMATCH_RETRIES = 1

/**
 * When a failed item may be tried again, or null when it waits for something else: the user
 * (reconnect, Drive full, a copy that failed twice) or the next run anyway.
 */
fun retryAtMillis(problem: UploadProblem, attempts: Int, nowMillis: Long): Long? =
    when (problem) {
        // Backup as a whole pauses until the user acts (R6).
        UploadProblem.AUTH_NEEDED,
        UploadProblem.QUOTA_FULL -> null
        UploadProblem.OFFLINE -> nowMillis + 15 * 60 * 1000
        // Probably still being written or edited.
        UploadProblem.CHANGED_DURING_UPLOAD -> nowMillis + HOUR_MILLIS
        // Usually gone; the next scan forgets it or marks it missing.
        UploadProblem.ORIGINAL_UNAVAILABLE -> nowMillis + 6 * HOUR_MILLIS
        UploadProblem.CHECKSUM_MISMATCH ->
            if (attempts <= MISMATCH_RETRIES) nowMillis + HOUR_MILLIS else null
        // Unexpected answers: back off from an hour up to a day.
        UploadProblem.FAILED ->
            nowMillis + minOf(HOUR_MILLIS shl (attempts - 1).coerceIn(0, 5), 24 * HOUR_MILLIS)
    }
