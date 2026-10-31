package com.iltan.settle.domain.backup

/** Why no backup runs at all until something changes (R6). */
enum class BackupPause {
    /** No Google Drive account is connected. */
    NOT_CONNECTED,
    /** The user paused backup. */
    BY_USER,
    /** Google needs the user to sign in again. */
    AUTH_NEEDED,
    /** Drive has no space left. */
    QUOTA_FULL,
}

/** What a run needs from the phone before it may start. */
data class RunConditions(val unmeteredNetwork: Boolean, val charging: Boolean)

/** A run of uploads to schedule. */
data class PlannedRun(
    /** Not before this time; at or before "now" means as soon as [conditions] hold. */
    val notBeforeMillis: Long,
    val conditions: RunConditions,
    /** Bytes the run is expected to send, for the system's scheduling. */
    val estimatedBytes: Long,
)

data class BackupPlan(
    val pause: BackupPause?,
    /** Items to upload now, in order: explicit requests first, then newest first. */
    val queue: List<BackupCandidate>,
    /** The automatic run: now if anything is ready, else when the next grace period ends. */
    val automatic: PlannedRun?,
    /** The run the user asked for, which ignores grace periods and the charger. */
    val requested: PlannedRun?,
    val waiting: Int,
    val needsAttention: Int,
)

/**
 * Decides what to back up and when (ADR-014). Pure, so every combination of settings, grace periods
 * and problems is tested on the JVM; the app turns the result into jobs.
 */
fun planBackup(
    candidates: List<BackupCandidate>,
    settings: BackupSettings,
    pause: BackupPause?,
    nowMillis: Long,
): BackupPlan {
    val ready = mutableListOf<BackupCandidate>()
    var nextWait: Long? = null
    var waiting = 0
    var attention = 0
    for (c in candidates) {
        when (val decision = backupDecision(c, settings, nowMillis)) {
            BackupDecision.Ready -> ready += c
            is BackupDecision.WaitUntil -> {
                waiting++
                nextWait = minOf(nextWait ?: decision.millis, decision.millis)
            }
            BackupDecision.NeedsAttention -> attention++
            BackupDecision.Skip -> {}
        }
    }
    val queue =
        ready.sortedWith(
            compareBy<BackupCandidate> { it.requestedAtMillis == null }
                .thenBy { it.requestedAtMillis }
                .thenByDescending { it.takenAtMillis }
        )
    val effectivePause = pause ?: if (settings.paused) BackupPause.BY_USER else null
    if (effectivePause != null) {
        return BackupPlan(effectivePause, queue, null, null, waiting, attention)
    }

    val unmetered = !settings.useMobileData
    val requested = queue.filter { it.requestedAtMillis != null }
    val automatic =
        when {
            queue.size > requested.size ->
                PlannedRun(
                    notBeforeMillis = nowMillis,
                    conditions = RunConditions(unmetered, settings.onlyWhileCharging),
                    estimatedBytes = queue.sumOf { it.sizeBytes },
                )
            nextWait != null ->
                PlannedRun(
                    notBeforeMillis = nextWait,
                    conditions = RunConditions(unmetered, settings.onlyWhileCharging),
                    estimatedBytes = 0,
                )
            else -> null
        }
    val requestedRun =
        requested
            .takeIf { it.isNotEmpty() }
            ?.let {
                PlannedRun(
                    notBeforeMillis = nowMillis,
                    conditions = RunConditions(unmetered, charging = false),
                    estimatedBytes = it.sumOf { c -> c.sizeBytes },
                )
            }
    return BackupPlan(null, queue, automatic, requestedRun, waiting, attention)
}
