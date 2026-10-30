package com.iltan.settle.domain.scan

import com.iltan.settle.domain.CloudState
import com.iltan.settle.domain.LocalState
import com.iltan.settle.domain.MediaItemId

/** What the local index already knows about one file on the phone. */
data class KnownFile(
    val itemId: MediaItemId,
    val volume: String,
    val mediaStoreId: Long,
    val displayName: String,
    val sizeBytes: Long,
    val takenAtMillis: Long,
    val generationModified: Long,
    val localState: LocalState,
    val cloudState: CloudState,
)

/** What one scan of a MediaStore volume found. */
data class VolumeScan(
    val volume: String,
    /**
     * Rows added or changed since the last scan. Must be every row when [idsTrusted] is false or
     * when the index holds missing files on this volume, so those can be found again.
     */
    val rows: List<ScanRow>,
    /** IDs of every image and video row on the volume, trashed ones included. */
    val presentIds: Set<Long>,
    /**
     * False after the MediaStore version changed: generations were reset and stored IDs may now
     * point at other files, so files are matched by name, size and date instead.
     */
    val idsTrusted: Boolean = true,
)

/** Changes to apply to the local index, in one transaction. */
data class ScanPlan(
    /** Files seen for the first time, each with a new item ID. */
    val inserts: List<IndexedRow>,
    /** Known items whose file changed or moved; the item keeps its ID. */
    val updates: List<IndexedRow>,
    /** Items to drop from the index: they vanished before anything reached the cloud. */
    val forgets: List<MediaItemId>,
    /** Items whose file vanished but must not be forgotten. */
    val missing: List<MediaItemId>,
    /** True when so many files vanished at once that nothing was forgotten (ADR-006). */
    val breakerTripped: Boolean,
) {
    val isEmpty: Boolean
        get() = inserts.isEmpty() && updates.isEmpty() && forgets.isEmpty() && missing.isEmpty()
}

data class IndexedRow(val itemId: MediaItemId, val row: ScanRow)

/** At least this many files must vanish in one scan before the circuit breaker can trip. */
const val BREAKER_MIN_FILES = 200

/**
 * Turns a MediaStore scan into changes to the local index (ADR-006, ADR-012).
 * - A row whose ID is known updates that item when its generation changed.
 * - A new row with the same name, size and date taken as a vanished or missing file is that file
 *   after a move or a MediaStore rebuild, and keeps its item ID.
 * - Any other new row becomes a new item.
 * - A vanished file is forgotten if it never reached the cloud, otherwise marked missing.
 * - If more than max([BREAKER_MIN_FILES], 10 %) of the files on the phone vanish at once (a
 *   permission or storage problem, not the user deleting), nothing is forgotten: all are marked
 *   missing.
 */
fun reconcileScan(
    known: List<KnownFile>,
    scan: VolumeScan,
    newId: () -> MediaItemId,
): ScanPlan {
    // Evicted files have no MediaStore row by design; the scan has nothing to say about them.
    val candidates = known.filter {
        it.volume == scan.volume && it.localState != LocalState.EVICTED
    }
    val byMediaStoreId =
        if (scan.idsTrusted) candidates.associateBy { it.mediaStoreId } else emptyMap()

    val updates = mutableListOf<IndexedRow>()
    val unmatchedRows = mutableListOf<ScanRow>()
    val matched = mutableSetOf<MediaItemId>()
    for (row in scan.rows) {
        val file = byMediaStoreId[row.mediaStoreId]
        if (file == null) {
            unmatchedRows += row
            continue
        }
        matched += file.itemId
        if (
            file.generationModified != row.generationModified || file.localState != row.localState
        ) {
            updates += IndexedRow(file.itemId, row)
        }
    }

    val seenIds = scan.presentIds + scan.rows.map { it.mediaStoreId }
    val unaccounted = candidates.filter {
        it.itemId !in matched && !(scan.idsTrusted && it.mediaStoreId in seenIds)
    }
    val pool = unaccounted.groupByTo(mutableMapOf()) { it.matchKey() }

    val inserts = mutableListOf<IndexedRow>()
    for (row in unmatchedRows) {
        val sameFile = pool[row.matchKey()]?.removeFirstOrNull()
        if (sameFile != null) {
            matched += sameFile.itemId
            updates += IndexedRow(sameFile.itemId, row)
        } else {
            inserts += IndexedRow(newId(), row)
        }
    }

    // Files that were already missing stay missing; only files that were here are newly lost.
    val lost = unaccounted.filter { it.itemId !in matched && it.localState != LocalState.MISSING }
    val onPhone = candidates.count { it.localState != LocalState.MISSING }
    val breakerTripped = lost.size > maxOf(BREAKER_MIN_FILES, onPhone / 10)
    val (forgets, missing) =
        if (breakerTripped) emptyList<KnownFile>() to lost
        else lost.partition { it.cloudState == CloudState.NONE }

    return ScanPlan(
        inserts = inserts,
        updates = updates,
        forgets = forgets.map { it.itemId },
        missing = missing.map { it.itemId },
        breakerTripped = breakerTripped,
    )
}

private data class MatchKey(val displayName: String, val sizeBytes: Long, val takenAtMillis: Long)

private fun KnownFile.matchKey() = MatchKey(displayName, sizeBytes, takenAtMillis)

private fun ScanRow.matchKey() = MatchKey(displayName, sizeBytes, takenAtMillis)
