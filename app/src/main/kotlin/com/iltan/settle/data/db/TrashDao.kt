package com.iltan.settle.data.db

import androidx.room3.ColumnInfo
import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Upsert
import com.iltan.settle.domain.CloudState
import com.iltan.settle.domain.LocalState
import com.iltan.settle.domain.deletion.DriveOpKind
import com.iltan.settle.domain.deletion.RestoreProblem
import com.iltan.settle.domain.deletion.TrashChanges
import com.iltan.settle.domain.deletion.TrashOrigin
import com.iltan.settle.domain.deletion.VanishedReason
import com.iltan.settle.domain.scan.MediaKind
import kotlinx.coroutines.flow.Flow

/** What the trash rules need about one item; see `TrashItem`. */
data class TrashItemRow(
    val id: String,
    @ColumnInfo(name = "local_state") val localState: LocalState,
    @ColumnInfo(name = "media_store_id") val mediaStoreId: Long?,
    @ColumnInfo(name = "cloud_state") val cloudState: CloudState,
    @ColumnInfo(name = "drive_file_id") val driveFileId: String?,
    val origin: TrashOrigin?,
    @ColumnInfo(name = "trashed_at_ms") val trashedAtMs: Long?,
    @ColumnInfo(name = "expires_at_ms") val expiresAtMs: Long?,
    @ColumnInfo(name = "since_ms") val sinceMs: Long?,
    val reason: VanishedReason?,
    val kept: Boolean?,
    @ColumnInfo(name = "pending_ops") val pendingOps: Int,
)

/** An item in the trash as the trash screen shows it. */
data class TrashedEntry(
    val id: String,
    val kind: MediaKind,
    @ColumnInfo(name = "mime_type") val mimeType: String,
    @ColumnInfo(name = "taken_at_ms") val takenAtMs: Long,
    @ColumnInfo(name = "duration_ms") val durationMs: Long,
    val volume: String,
    /** Null once the phone no longer has the file. */
    @ColumnInfo(name = "media_store_id") val mediaStoreId: Long?,
    @ColumnInfo(name = "date_modified_s") val dateModifiedS: Long,
    @ColumnInfo(name = "local_state") val localState: LocalState,
    @ColumnInfo(name = "cloud_state") val cloudState: CloudState,
    /** Null for an item another app trashed while Settle asks first. */
    val origin: TrashOrigin?,
    @ColumnInfo(name = "trashed_at_ms") val trashedAtMs: Long?,
    @ColumnInfo(name = "expires_at_ms") val expiresAtMs: Long?,
    /** Set when the user asked to restore it and it is downloading. */
    @ColumnInfo(name = "restore_requested_at_ms") val restoreRequestedAtMs: Long?,
    @ColumnInfo(name = "restore_problem") val restoreProblem: RestoreProblem?,
)

/** An item that left the phone without Settle and waits for the user's decision. */
data class VanishedEntry(
    val id: String,
    val kind: MediaKind,
    @ColumnInfo(name = "taken_at_ms") val takenAtMs: Long,
    @ColumnInfo(name = "display_name") val displayName: String,
    val reason: VanishedReason,
    @ColumnInfo(name = "since_ms") val sinceMs: Long,
)

private const val TRASH_ITEM_COLUMNS =
    """
    SELECT m.id, f.local_state, f.media_store_id, m.cloud_state, u.drive_file_id,
        t.origin, t.trashed_at_ms, t.expires_at_ms, v.since_ms, v.reason, v.kept,
        (SELECT COUNT(*) FROM drive_op o WHERE o.item_id = m.id) AS pending_ops
    FROM media_item m JOIN local_file f ON f.item_id = m.id
    LEFT JOIN upload u ON u.item_id = m.id
    LEFT JOIN trash_entry t ON t.item_id = m.id
    LEFT JOIN vanished v ON v.item_id = m.id
    """

/** Settle's trash, the items waiting for a decision, and the queue of Drive changes (ADR-015). */
@Dao
interface TrashDao {

    @Query("$TRASH_ITEM_COLUMNS WHERE m.id IN (:ids)")
    suspend fun trashItems(ids: List<String>): List<TrashItemRow>

    @Query("SELECT * FROM superseded_file WHERE item_id IN (:ids)")
    suspend fun supersededOf(ids: List<String>): List<SupersededFileEntity>

    /** Items in Settle's trash whose time is over. */
    @Query("$TRASH_ITEM_COLUMNS WHERE t.expires_at_ms <= :nowMs")
    suspend fun expiredTrash(nowMs: Long): List<TrashItemRow>

    /** Every item in the trash: Settle's entries and anything in the phone's trash. */
    @Query(
        """
        SELECT m.id, m.kind, m.mime_type, m.taken_at_ms, m.duration_ms, f.volume,
            f.media_store_id, f.date_modified_s, f.local_state, m.cloud_state, t.origin,
            t.trashed_at_ms, t.expires_at_ms, r.requested_at_ms AS restore_requested_at_ms,
            r.problem AS restore_problem
        FROM media_item m JOIN local_file f ON f.item_id = m.id
        LEFT JOIN trash_entry t ON t.item_id = m.id
        LEFT JOIN restore_request r ON r.item_id = m.id
        WHERE t.item_id IS NOT NULL OR f.local_state = 'TRASHED'
        ORDER BY COALESCE(t.trashed_at_ms, 0) DESC, m.taken_at_ms DESC
        """
    )
    fun observeTrash(): Flow<List<TrashedEntry>>

    /** Items waiting for the user's decision, newest first. */
    @Query(
        """
        SELECT m.id, m.kind, m.taken_at_ms, f.display_name, v.reason, v.since_ms
        FROM vanished v JOIN media_item m ON m.id = v.item_id
        JOIN local_file f ON f.item_id = m.id
        WHERE NOT v.kept AND f.local_state <> 'PRESENT'
        ORDER BY v.since_ms DESC, m.taken_at_ms DESC
        """
    )
    fun observeUndecided(): Flow<List<VanishedEntry>>

    @Query("SELECT item_id FROM vanished WHERE NOT kept AND reason = :reason")
    suspend fun undecided(reason: VanishedReason): List<String>

    // Applying changes

    /** Applies the trash rules' changes, all or nothing. */
    @Transaction
    suspend fun applyTrashChanges(changes: TrashChanges) {
        val purge = changes.purge.map { it.value }
        for (id in purge) {
            deleteSupersededOf(id)
            deleteItem(id)
        }
        changes.removeEntries.forEach { deleteEntry(it.value) }
        changes.entries.forEach {
            upsertEntry(
                TrashEntryEntity(it.itemId.value, it.origin, it.trashedAtMillis, it.expiresAtMillis)
            )
        }
        changes.removeVanished.forEach { deleteVanished(it.value) }
        changes.vanished.forEach {
            upsertVanished(VanishedEntity(it.itemId.value, it.sinceMillis, it.reason, it.kept))
        }
        changes.ops.forEach {
            upsertOp(
                DriveOpEntity(
                    it.driveFileId,
                    it.itemId.value,
                    it.kind,
                    it.notBeforeMillis,
                    it.attempts,
                )
            )
        }
        changes.markTrashed.forEach { setLocalState(it.value, LocalState.TRASHED) }
        changes.markPresent.forEach { setLocalState(it.value, LocalState.PRESENT) }
    }

    @Upsert suspend fun upsertEntry(entry: TrashEntryEntity)

    @Query("DELETE FROM trash_entry WHERE item_id = :itemId")
    suspend fun deleteEntry(itemId: String)

    @Upsert suspend fun upsertVanished(vanished: VanishedEntity)

    @Query("DELETE FROM vanished WHERE item_id = :itemId")
    suspend fun deleteVanished(itemId: String)

    @Upsert suspend fun upsertOp(op: DriveOpEntity)

    @Query("UPDATE local_file SET local_state = :state WHERE item_id = :itemId")
    suspend fun setLocalState(itemId: String, state: LocalState)

    @Query("DELETE FROM superseded_file WHERE item_id = :itemId")
    suspend fun deleteSupersededOf(itemId: String)

    @Query("DELETE FROM media_item WHERE id = :id") suspend fun deleteItem(id: String)

    // The Drive change queue

    @Query("SELECT * FROM drive_op WHERE not_before_ms <= :nowMs ORDER BY not_before_ms, rowid")
    suspend fun dueOps(nowMs: Long): List<DriveOpEntity>

    /** Removes the operation only if it wasn't replaced while it ran. */
    @Query(
        """
        DELETE FROM drive_op WHERE drive_file_id = :driveFileId AND kind = :kind
            AND not_before_ms = :notBeforeMs AND attempts = :attempts
        """
    )
    suspend fun deleteOp(driveFileId: String, kind: DriveOpKind, notBeforeMs: Long, attempts: Int)

    /** Schedules the next try, only if the operation wasn't replaced while it ran. */
    @Query(
        """
        UPDATE drive_op SET not_before_ms = :nextMs, attempts = attempts + 1
        WHERE drive_file_id = :driveFileId AND kind = :kind AND not_before_ms = :notBeforeMs
            AND attempts = :attempts
        """
    )
    suspend fun retryOp(
        driveFileId: String,
        kind: DriveOpKind,
        notBeforeMs: Long,
        attempts: Int,
        nextMs: Long,
    )

    @Query("SELECT MIN(not_before_ms) FROM drive_op") suspend fun nextOpDueAt(): Long?

    // Restoring

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addRestoreRequests(requests: List<RestoreRequestEntity>)

    @Query("SELECT * FROM restore_request ORDER BY requested_at_ms, rowid")
    suspend fun restoreRequests(): List<RestoreRequestEntity>

    @Query("SELECT pending_uri FROM restore_request WHERE item_id = :itemId")
    suspend fun pendingUri(itemId: String): String?

    @Query("UPDATE restore_request SET pending_uri = :uri WHERE item_id = :itemId")
    suspend fun setPendingUri(itemId: String, uri: String?)

    @Query("UPDATE restore_request SET problem = :problem WHERE item_id = :itemId")
    suspend fun setRestoreProblem(itemId: String, problem: RestoreProblem?)

    /**
     * A restored file is back on the phone: the item points at its new MediaStore row and leaves
     * the trash, in one step, so a scan can't take the new row for a new item.
     */
    @Transaction
    suspend fun restored(itemId: String, mediaStoreId: Long, sizeBytes: Long, dateModifiedS: Long) {
        clearMediaStoreId(mediaStoreId)
        linkRestored(itemId, mediaStoreId, sizeBytes, dateModifiedS)
        deleteEntry(itemId)
        deleteVanished(itemId)
        deleteRestoreRequest(itemId)
    }

    @Query("UPDATE local_file SET media_store_id = NULL WHERE media_store_id = :mediaStoreId")
    suspend fun clearMediaStoreId(mediaStoreId: Long)

    @Query(
        """
        UPDATE local_file SET media_store_id = :mediaStoreId, size_bytes = :sizeBytes,
            date_modified_s = :dateModifiedS, generation_modified = 0, local_state = 'PRESENT'
        WHERE item_id = :itemId
        """
    )
    suspend fun linkRestored(
        itemId: String,
        mediaStoreId: Long,
        sizeBytes: Long,
        dateModifiedS: Long,
    )

    @Query("DELETE FROM restore_request WHERE item_id = :itemId")
    suspend fun deleteRestoreRequest(itemId: String)
}
