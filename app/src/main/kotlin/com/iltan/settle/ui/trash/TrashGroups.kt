package com.iltan.settle.ui.trash

import com.iltan.settle.data.db.TrashedEntry
import com.iltan.settle.domain.deletion.TrashOrigin
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** How a group of the trash came to be there, which decides its title and banner (F1). */
enum class GroupKind {
    /** Deleted in Settle. */
    SETTLE,
    /** Deleted in another app; their Drive copies follow. */
    ELSEWHERE,
    /** Disappeared at once, and the user moved their Drive copies to the trash (G3). */
    DISAPPEARED,
    /** Deleted in another app while Settle asks first: nothing happens until the user chooses. */
    WAITING,
}

/** One group of the trash: its items were deleted the same way, and in Settle on the same [day]. */
data class TrashGroup(val kind: GroupKind, val day: LocalDate?, val items: List<TrashedEntry>) {
    val key: String
        get() = "$kind-$day"
}

/** True while the item downloads from Drive or waits its turn to. */
val TrashedEntry.restoring: Boolean
    get() = restoreRequestedAtMs != null && restoreProblem == null

/**
 * The trash by deletion, newest first (F1): items waiting for the user first, then one group per
 * day for deletions in Settle, and one each for deletions in another app and disappearances, so
 * their banner shows once. [entries] come newest first.
 */
fun trashGroups(entries: List<TrashedEntry>, zone: ZoneId): List<TrashGroup> {
    val (waiting, rest) = entries.partition { it.waitingReason != null }
    val groups = mutableListOf<TrashGroup>()
    if (waiting.isNotEmpty()) groups += TrashGroup(GroupKind.WAITING, null, waiting)
    rest
        .groupBy { entry ->
            val kind =
                when (entry.origin) {
                    TrashOrigin.SETTLE -> GroupKind.SETTLE
                    TrashOrigin.DISAPPEARED -> GroupKind.DISAPPEARED
                    // In the phone's trash without Settle's record: another app put it there.
                    TrashOrigin.ELSEWHERE,
                    null -> GroupKind.ELSEWHERE
                }
            val day =
                entry.trashedAtMs
                    ?.takeIf { kind == GroupKind.SETTLE }
                    ?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
            kind to day
        }
        .mapTo(groups) { (key, items) -> TrashGroup(key.first, key.second, items) }
    return groups
}

/** Whole days left before the trash deletes [entry] for good, rounded up; null without a clock. */
fun daysLeft(entry: TrashedEntry, nowMillis: Long): Int? {
    val expires = entry.expiresAtMs ?: return null
    return ((expires - nowMillis + DAY_MILLIS - 1) / DAY_MILLIS).toInt().coerceAtLeast(0)
}

/** From this many days left, the countdown turns to the warning colour (F1). */
const val SOON_DAYS = 3

private const val DAY_MILLIS = 24L * 60 * 60 * 1000

/** A restore from Drive while it runs: how far along, and what is left (F5). */
data class RestoreBatch(
    /** Items in this batch, done or not. */
    val total: Int,
    /** Back on the phone so far. */
    val restored: Int,
    /** Bytes still to download. */
    val bytesLeft: Long,
    /** Bytes of the whole batch, done or not. */
    val bytesTotal: Long = 0,
)

/** A restore from Drive that ended: how many came back, and where (F6). */
data class RestoreOutcome(
    val restored: Int,
    /** The folder they went back to; null when they went to several. */
    val folder: String?,
    val failed: Int,
)

/**
 * Follows restores from Drive across updates of the trash (F5, F6). An item that was restoring and
 * left the trash is back on the phone: it stays shown with a check for a moment, counts toward "2
 * of 4", and once nothing restores any more the batch ends with an [RestoreOutcome].
 */
class RestoreTracker {
    private var batch = linkedMapOf<String, TrashedEntry>()
    private val restored = linkedMapOf<String, TrashedEntry>()

    /** What changed with the trash now holding [entries]. */
    data class Update(
        /** Items that just came back, to show with a check before they leave. */
        val justRestored: List<TrashedEntry>,
        val batch: RestoreBatch?,
        /** Set once, when the last item of a batch finished. */
        val outcome: RestoreOutcome?,
    )

    fun update(entries: List<TrashedEntry>): Update {
        val byId = entries.associateBy { it.id }
        val running = entries.filter { it.restoring }
        running.forEach { batch[it.id] = it }
        val back = batch.values.filter { it.id !in byId && it.id !in restored }
        back.forEach { restored[it.id] = it }
        if (batch.isEmpty()) return Update(back, null, null)
        if (running.isNotEmpty()) {
            return Update(
                back,
                RestoreBatch(
                    batch.size,
                    restored.size,
                    running.sumOf { it.sizeBytes },
                    batch.values.sumOf { it.sizeBytes },
                ),
                null,
            )
        }
        val failed = batch.keys.count { byId[it]?.restoreProblem != null }
        val outcome =
            RestoreOutcome(
                restored.size,
                restored.values.map { it.relativePath }.distinct().singleOrNull(),
                failed,
            )
        batch = linkedMapOf()
        restored.clear()
        return Update(back, null, outcome.takeIf { it.restored > 0 || it.failed > 0 })
    }
}
