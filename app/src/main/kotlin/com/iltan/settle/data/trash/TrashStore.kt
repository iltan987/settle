package com.iltan.settle.data.trash

import com.iltan.settle.data.db.DriveOpEntity
import com.iltan.settle.data.db.TrashDao
import com.iltan.settle.data.db.TrashItemRow
import com.iltan.settle.domain.MediaItemId
import com.iltan.settle.domain.deletion.DownloadStore
import com.iltan.settle.domain.deletion.DriveFiles
import com.iltan.settle.domain.deletion.DriveOp
import com.iltan.settle.domain.deletion.DriveOpStore
import com.iltan.settle.domain.deletion.TrashEntry
import com.iltan.settle.domain.deletion.TrashItem
import com.iltan.settle.domain.deletion.Vanished

/** Room behind the trash rules' ports (ADR-015). */
class TrashStore(private val dao: TrashDao) : DriveOpStore, DownloadStore {

    /** The trash rules' view of these items; items no longer in the library are left out. */
    suspend fun items(ids: Collection<MediaItemId>): List<TrashItem> =
        ids.map { it.value }
            .distinct()
            .chunked(QUERY_CHUNK)
            .flatMap { chunk ->
                val superseded = dao.supersededOf(chunk).groupBy({ it.itemId }, { it.driveFileId })
                dao.trashItems(chunk).map { it.toTrashItem(superseded[it.id].orEmpty()) }
            }

    suspend fun expired(nowMillis: Long): List<TrashItem> {
        val rows = dao.expiredTrash(nowMillis)
        val superseded =
            rows
                .map { it.id }
                .chunked(QUERY_CHUNK)
                .flatMap { dao.supersededOf(it) }
                .groupBy({ it.itemId }, { it.driveFileId })
        return rows.map { it.toTrashItem(superseded[it.id].orEmpty()) }
    }

    // DriveOpStore

    override suspend fun due(nowMillis: Long): List<DriveOp> =
        dao.dueOps(nowMillis).map { it.toOp() }

    override suspend fun done(op: DriveOp) =
        dao.deleteOp(op.driveFileId, op.kind, op.notBeforeMillis, op.attempts)

    override suspend fun retry(op: DriveOp, notBeforeMillis: Long) =
        dao.retryOp(op.driveFileId, op.kind, op.notBeforeMillis, op.attempts, notBeforeMillis)

    override suspend fun nextDueAt(): Long? = dao.nextOpDueAt()

    // DownloadStore

    override suspend fun pendingDownload(itemId: MediaItemId): String? =
        dao.pendingUri(itemId.value)

    override suspend fun savePendingDownload(itemId: MediaItemId, handle: String) =
        dao.setPendingUri(itemId.value, handle)

    override suspend fun clearPendingDownload(itemId: MediaItemId) =
        dao.setPendingUri(itemId.value, null)

    private companion object {
        /** Below SQLite's host-parameter limit with room to spare. */
        const val QUERY_CHUNK = 500
    }
}

private fun id(value: String) =
    checkNotNull(MediaItemId.parseOrNull(value)) { "Bad item ID $value" }

private fun TrashItemRow.toTrashItem(superseded: List<String>): TrashItem {
    val itemId = id(id)
    return TrashItem(
        itemId = itemId,
        localState = localState,
        mediaStoreId = mediaStoreId,
        cloudState = cloudState,
        driveFiles = DriveFiles(current = driveFileId, superseded = superseded),
        entry =
            if (origin != null && trashedAtMs != null && expiresAtMs != null) {
                TrashEntry(itemId, origin, trashedAtMs, expiresAtMs)
            } else null,
        vanished =
            if (reason != null && sinceMs != null) {
                Vanished(itemId, sinceMs, reason, kept == true)
            } else null,
        pendingOps = pendingOps,
    )
}

private fun DriveOpEntity.toOp() = DriveOp(driveFileId, id(itemId), kind, notBeforeMs, attempts)
