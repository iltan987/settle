package com.iltan.settle.domain.backup

/**
 * Why no backup may run right now, the one place this is decided (R6).
 *
 * Signing in again and a full Drive need a connected account, so "not connected" comes first; then
 * those two, which the user has to fix in Google; then media location access; then the user's own
 * pause, which is a choice and not a problem. [canReadOriginals] is asked only when it matters.
 */
fun backupPause(
    connected: Boolean,
    blocked: BackupPause?,
    canReadOriginals: () -> Boolean,
    pausedByUser: Boolean,
): BackupPause? =
    when {
        !connected -> BackupPause.NOT_CONNECTED
        blocked == BackupPause.AUTH_NEEDED -> BackupPause.AUTH_NEEDED
        blocked == BackupPause.QUOTA_FULL -> BackupPause.QUOTA_FULL
        !canReadOriginals() -> BackupPause.NO_ORIGINAL_ACCESS
        pausedByUser -> BackupPause.BY_USER
        else -> null
    }

/** A run of uploads in progress, for "Backing up 12 of 40". */
data class RunProgress(
    val doneItems: Int,
    val totalItems: Int,
    val sentBytes: Long,
    val totalBytes: Long,
    /** A smoothed recent speed; null until there is enough to tell. */
    val bytesPerSecond: Long?,
) {
    /** Share of the run sent, by bytes when known, else by items. */
    val fraction: Float
        get() =
            when {
                totalBytes > 0 -> (sentBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
                totalItems > 0 -> (doneItems.toFloat() / totalItems).coerceIn(0f, 1f)
                else -> 0f
            }

    /** Seconds until the run ends at the current speed; null when the speed isn't known yet. */
    val secondsLeft: Long?
        get() {
            val speed = bytesPerSecond?.takeIf { it > 0 } ?: return null
            if (totalBytes <= 0) return null
            val left = (totalBytes - sentBytes).coerceAtLeast(0)
            return (left + speed - 1) / speed
        }
}

/** What Settle's backup status says, everywhere it is shown (the chip, notices, the Backup tab). */
sealed interface BackupHeadline {
    /** Items the user should look at; everything else is fine. */
    data class NeedsAttention(val count: Int) : BackupHeadline

    /** Nothing uploads until [reason] is resolved; [waitingItems] stay safe on the phone. */
    data class Paused(val reason: BackupPause, val waitingItems: Int) : BackupHeadline

    data class BackingUp(val run: RunProgress) : BackupHeadline

    /** Connected, but nothing has been verified in Drive yet: never "All in Drive" before that. */
    data class FirstBackupPending(val upcoming: Int) : BackupHeadline

    /** Every item that backs up is verified, apart from [upcoming] that wait or are queued. */
    data class AllInDrive(val backedUp: Int, val upcoming: Int) : BackupHeadline
}

data class BackupStatusInputs(
    /** From [backupPause]. */
    val pause: BackupPause?,
    val needsAttention: Int,
    /** Items verified in Drive. */
    val backedUp: Int,
    /** Items waiting out their grace period. */
    val waiting: Int,
    /** Items ready for the next run. */
    val queued: Int,
    /** The run in progress, if any. */
    val run: RunProgress?,
)

/**
 * The one sentence about backup, by priority: attention, then a pause (signed out, Drive full, not
 * connected, location access, paused by the user), then a running backup, then the first backup
 * still to come, then all in Drive.
 */
fun backupHeadline(inputs: BackupStatusInputs): BackupHeadline {
    val upcoming = inputs.waiting + inputs.queued
    return when {
        inputs.needsAttention > 0 -> BackupHeadline.NeedsAttention(inputs.needsAttention)
        inputs.pause != null -> BackupHeadline.Paused(inputs.pause, upcoming)
        inputs.run != null -> BackupHeadline.BackingUp(inputs.run)
        inputs.backedUp == 0 -> BackupHeadline.FirstBackupPending(upcoming)
        else -> BackupHeadline.AllInDrive(inputs.backedUp, upcoming)
    }
}
