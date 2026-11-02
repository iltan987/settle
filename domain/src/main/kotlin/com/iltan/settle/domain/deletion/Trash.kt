package com.iltan.settle.domain.deletion

import com.iltan.settle.domain.CloudState
import com.iltan.settle.domain.LocalState
import com.iltan.settle.domain.MediaItemId
import com.iltan.settle.domain.backup.HOUR_MILLIS
import com.iltan.settle.domain.scan.ScanPlan

/** How long the phone and Drive keep trashed items (spec defaults, ADR-015). */
const val TRASH_RETENTION_MILLIS = 30 * 24 * HOUR_MILLIS

/** How long a deletion made in another app waits before it reaches Drive (ADR-015). */
const val EXTERNAL_DELETION_WAIT_MILLIS = HOUR_MILLIS

enum class TrashOrigin {
    /** Deleted in Settle. */
    SETTLE,
    /** Deleted in another app, e.g. Samsung Gallery. */
    ELSEWHERE,
    /**
     * Part of a mass disappearance whose Drive copies the user chose to move to the trash (R14).
     * Already confirmed, so it never counts toward another one.
     */
    DISAPPEARED,
}

/** An item in Settle's trash: its phone file in the system trash and its Drive copy in Drive's. */
data class TrashEntry(
    val itemId: MediaItemId,
    val origin: TrashOrigin,
    val trashedAtMillis: Long,
    val expiresAtMillis: Long = trashedAtMillis + TRASH_RETENTION_MILLIS,
)

/** What Settle does with the Drive copy of an item deleted in another app (R13, ADR-006). */
enum class ExternalDeletionPolicy {
    MOVE_DRIVE_COPY_TO_TRASH,
    ASK,
}

enum class VanishedReason {
    /** Part of a mass disappearance; never acted on without the user (R14). */
    BREAKER,
    /** Deleted in another app while the policy is [ExternalDeletionPolicy.ASK]. */
    ASK,
}

/** A backed-up item that left the phone without Settle, waiting for the user's decision. */
data class Vanished(
    val itemId: MediaItemId,
    val sinceMillis: Long,
    val reason: VanishedReason,
    /** The user chose to keep the Drive copy; nothing more is asked. */
    val kept: Boolean = false,
)

enum class DriveOpKind {
    TRASH,
    UNTRASH,
    /** Permanently, skipping Drive's trash. */
    DELETE,
}

/**
 * A change to one Drive file, queued so deleting works offline. Keyed by the Drive file ID: a newer
 * operation on the same file replaces an older one, so restoring before a queued trash ran simply
 * cancels it. Every operation is idempotent.
 */
data class DriveOp(
    val driveFileId: String,
    val itemId: MediaItemId,
    val kind: DriveOpKind,
    /** Not before this time. */
    val notBeforeMillis: Long = 0,
    /** Failed tries so far. */
    val attempts: Int = 0,
)

/** The Drive files of one item. */
data class DriveFiles(
    /** The current upload's file ID; reserved before the file exists, so it may not exist yet. */
    val current: String? = null,
    /** Older copies a newer upload replaced (ADR-013). */
    val superseded: List<String> = emptyList(),
) {
    val all: List<String>
        get() = listOfNotNull(current) + superseded
}

/** What the trash rules need to know about one item. */
data class TrashItem(
    val itemId: MediaItemId,
    val localState: LocalState,
    /** Null while the file isn't on the phone. */
    val mediaStoreId: Long?,
    val cloudState: CloudState,
    val driveFiles: DriveFiles = DriveFiles(),
    val entry: TrashEntry? = null,
    val vanished: Vanished? = null,
    /** Drive operations still queued for this item. */
    val pendingOps: Int = 0,
) {
    /** True when a verified copy can be downloaded back. */
    val restorableFromDrive: Boolean
        get() = cloudState == CloudState.VERIFIED && driveFiles.current != null

    val inTrash: Boolean
        get() = entry != null || localState == LocalState.TRASHED
}

/** Changes to Settle's records, applied in one transaction. */
data class TrashChanges(
    /** Added or replaced. */
    val entries: List<TrashEntry> = emptyList(),
    val removeEntries: List<MediaItemId> = emptyList(),
    /** Added or replaced. */
    val vanished: List<Vanished> = emptyList(),
    val removeVanished: List<MediaItemId> = emptyList(),
    /** Queued, replacing any operation on the same file. */
    val ops: List<DriveOp> = emptyList(),
    /** Drive files whose queued operation is dropped. */
    val dropOps: List<String> = emptyList(),
    /** Items whose phone file Settle just moved into or out of the system trash. */
    val markTrashed: List<MediaItemId> = emptyList(),
    val markPresent: List<MediaItemId> = emptyList(),
    /** Items to drop from the library: nothing of them is left to restore. */
    val purge: List<MediaItemId> = emptyList(),
) {
    val isEmpty: Boolean
        get() =
            entries.isEmpty() &&
                removeEntries.isEmpty() &&
                vanished.isEmpty() &&
                removeVanished.isEmpty() &&
                ops.isEmpty() &&
                dropOps.isEmpty() &&
                markTrashed.isEmpty() &&
                markPresent.isEmpty() &&
                purge.isEmpty()
}

private fun ops(itemId: MediaItemId, files: List<String>, kind: DriveOpKind, notBefore: Long = 0) =
    files.map {
        DriveOp(it, itemId, kind, notBefore)
    }

/**
 * Follows the phone after a scan (ADR-015, ADR-006). The phone decides:
 * - an item out of the trash again, restored here or in another app, leaves Settle's trash and its
 *   Drive copy is taken out of Drive's trash;
 * - an item another app trashed, or whose file vanished, is a deletion made elsewhere: with the
 *   default policy it enters Settle's trash and its Drive copies follow after [externalWaitMillis];
 *   with [ExternalDeletionPolicy.ASK] it waits for the user;
 * - a mass disappearance touches nothing in Drive and waits for the user (R14).
 *
 * Live scans can take a large deletion in slices of a few dozen files, each below the breaker's
 * limit. So the wait doubles as the breaker's window: this scan's disappearances count together
 * with [recentElsewhere], the deletions elsewhere whose Drive copies haven't been touched yet. When
 * together they pass the limit, all of them become one mass disappearance and their queued Drive
 * changes are dropped.
 *
 * [items] holds the state before the scan of every item the plan mentions.
 */
fun afterScan(
    plan: ScanPlan,
    items: Map<MediaItemId, TrashItem>,
    policy: ExternalDeletionPolicy,
    nowMillis: Long,
    externalWaitMillis: Long = EXTERNAL_DELETION_WAIT_MILLIS,
    recentElsewhere: List<TrashItem> = emptyList(),
): TrashChanges {
    val entries = mutableListOf<TrashEntry>()
    val removeEntries = mutableListOf<MediaItemId>()
    val vanished = mutableListOf<Vanished>()
    val removeVanished = mutableListOf<MediaItemId>()
    val queued = mutableListOf<DriveOp>()

    fun deletedElsewhere(item: TrashItem) {
        if (item.entry != null || item.vanished != null) return
        when {
            // Nothing in Drive to decide about.
            item.cloudState == CloudState.NONE ->
                entries += TrashEntry(item.itemId, TrashOrigin.ELSEWHERE, nowMillis)
            policy == ExternalDeletionPolicy.ASK ->
                vanished += Vanished(item.itemId, nowMillis, VanishedReason.ASK)
            else -> {
                entries += TrashEntry(item.itemId, TrashOrigin.ELSEWHERE, nowMillis)
                queued +=
                    ops(
                        item.itemId,
                        item.driveFiles.all,
                        DriveOpKind.TRASH,
                        nowMillis + externalWaitMillis,
                    )
            }
        }
    }

    val recent = recentElsewhere.filter { it.itemId !in items }
    val massDisappearance =
        plan.breakerTripped ||
            (plan.missing.isNotEmpty() && plan.missing.size + recent.size > plan.breakerLimit)
    val dropOps = mutableListOf<String>()
    if (massDisappearance) {
        for (item in recent) {
            val entry = item.entry ?: continue
            removeEntries += item.itemId
            vanished += Vanished(item.itemId, entry.trashedAtMillis, VanishedReason.BREAKER)
            dropOps += item.driveFiles.all
        }
    }

    for (id in plan.missing) {
        val item = items[id] ?: continue
        if (massDisappearance) {
            if (item.vanished == null && item.entry == null) {
                vanished += Vanished(id, nowMillis, VanishedReason.BREAKER)
            }
        } else {
            deletedElsewhere(item)
        }
    }

    for ((id, row) in plan.updates) {
        val item = items[id] ?: continue
        val nowTrashed = row.localState == LocalState.TRASHED
        val wasGone = item.localState != LocalState.PRESENT
        when {
            nowTrashed && item.localState != LocalState.TRASHED -> deletedElsewhere(item)
            !nowTrashed && wasGone -> {
                // Back on the phone: whatever happened to it elsewhere is undone.
                if (item.entry != null) {
                    removeEntries += id
                    queued += ops(id, listOfNotNull(item.driveFiles.current), DriveOpKind.UNTRASH)
                }
                if (item.vanished != null) removeVanished += id
            }
        }
    }

    return TrashChanges(
        entries = entries,
        removeEntries = removeEntries,
        vanished = vanished,
        removeVanished = removeVanished,
        ops = queued,
        dropOps = dropOps,
    )
}

/** The phone files to move into the system trash for a deletion in Settle. */
fun phoneFilesToTrash(items: List<TrashItem>): List<Long> =
    items.filter { it.localState == LocalState.PRESENT }.mapNotNull { it.mediaStoreId }

/**
 * Settle's records once the phone files of [items] are in the system trash (R12): each enters
 * Settle's trash, and every Drive file of it, superseded copies included, goes to Drive's trash.
 * The upload record stays, so restoring needs no new upload.
 *
 * [items] must be the state from before the trash request: a scan that ran in between may already
 * have taken the trashed files for a deletion made elsewhere, and these changes replace that.
 */
fun trashedInSettle(items: List<TrashItem>, nowMillis: Long): TrashChanges {
    val deleted = items.filter { it.localState == LocalState.PRESENT }
    return TrashChanges(
        entries = deleted.map { TrashEntry(it.itemId, TrashOrigin.SETTLE, nowMillis) },
        ops = deleted.flatMap { ops(it.itemId, it.driveFiles.all, DriveOpKind.TRASH) },
        markTrashed = deleted.map { it.itemId },
    )
}

/** How each item in the trash comes back. */
data class RestorePlan(
    /** Phone files to take out of the system trash. */
    val phoneUntrash: List<Long>,
    /** Records once [phoneUntrash] succeeded. */
    val untrashed: TrashChanges,
    /**
     * Items whose phone file is gone: their Drive copy is taken out of the trash and downloaded.
     */
    val downloads: List<MediaItemId>,
    /** Drive copies to take out of Drive's trash before downloading. */
    val downloadOps: List<DriveOp>,
    /** In the trash, but nothing is left to restore from. */
    val unrestorable: List<MediaItemId>,
)

fun planRestore(items: List<TrashItem>): RestorePlan {
    val inTrash = items.filter { it.inTrash }
    val onPhone = inTrash.filter { it.localState == LocalState.TRASHED && it.mediaStoreId != null }
    val rest = inTrash - onPhone.toSet()
    val (download, unrestorable) = rest.partition { it.restorableFromDrive }
    return RestorePlan(
        phoneUntrash = onPhone.mapNotNull { it.mediaStoreId },
        untrashed =
            TrashChanges(
                removeEntries = onPhone.filter { it.entry != null }.map { it.itemId },
                removeVanished = onPhone.filter { it.vanished != null }.map { it.itemId },
                ops =
                    onPhone.flatMap {
                        ops(it.itemId, listOfNotNull(it.driveFiles.current), DriveOpKind.UNTRASH)
                    },
                markPresent = onPhone.map { it.itemId },
            ),
        downloads = download.map { it.itemId },
        downloadOps =
            download.flatMap {
                ops(it.itemId, listOfNotNull(it.driveFiles.current), DriveOpKind.UNTRASH)
            },
        unrestorable = unrestorable.map { it.itemId },
    )
}

/** Permanent deletion of items in the trash (ADR-015). Anything outside the trash is left alone. */
data class DeleteForeverPlan(
    /** Phone files to delete; they are in the system trash. */
    val phoneDelete: List<Long>,
    /** Records once [phoneDelete] succeeded. */
    val deleted: TrashChanges,
)

fun planDeleteForever(items: List<TrashItem>): DeleteForeverPlan {
    val inTrash = items.filter { it.inTrash }
    return DeleteForeverPlan(
        phoneDelete =
            inTrash.filter { it.localState == LocalState.TRASHED }.mapNotNull { it.mediaStoreId },
        deleted =
            TrashChanges(
                ops = inTrash.flatMap { ops(it.itemId, it.driveFiles.all, DriveOpKind.DELETE) },
                purge = inTrash.map { it.itemId },
            ),
    )
}

enum class VanishedDecision {
    MOVE_DRIVE_COPIES_TO_TRASH,
    KEEP_DRIVE_COPIES,
}

/** The user's answer for items that left the phone without Settle (R13 Ask, R14). */
fun decideVanished(
    items: List<TrashItem>,
    decision: VanishedDecision,
    nowMillis: Long,
): TrashChanges {
    val waiting = items.filter { it.vanished != null && it.localState != LocalState.PRESENT }
    return when (decision) {
        VanishedDecision.MOVE_DRIVE_COPIES_TO_TRASH ->
            TrashChanges(
                entries =
                    waiting.map {
                        val origin =
                            if (it.vanished?.reason == VanishedReason.BREAKER) {
                                TrashOrigin.DISAPPEARED
                            } else TrashOrigin.ELSEWHERE
                        TrashEntry(it.itemId, origin, nowMillis)
                    },
                removeVanished = waiting.map { it.itemId },
                ops = waiting.flatMap { ops(it.itemId, it.driveFiles.all, DriveOpKind.TRASH) },
            )
        VanishedDecision.KEEP_DRIVE_COPIES ->
            TrashChanges(vanished = waiting.map { it.vanished!!.copy(kept = true) })
    }
}

/**
 * Settle forgets an item once its trash time is over and nothing of it is left: the phone has
 * deleted its file, and no Drive change for it is still queued (a trash that never ran must not
 * leave a Drive file Settle no longer knows about).
 */
fun expiredItems(items: List<TrashItem>, nowMillis: Long): List<MediaItemId> =
    items
        .filter { item ->
            val entry = item.entry ?: return@filter false
            entry.expiresAtMillis <= nowMillis &&
                item.localState != LocalState.PRESENT &&
                item.localState != LocalState.TRASHED &&
                item.pendingOps == 0
        }
        .map { it.itemId }
