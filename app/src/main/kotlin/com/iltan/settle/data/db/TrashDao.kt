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
    /** The folder it was in, and goes back to. */
    @ColumnInfo(name = "relative_path") val relativePath: String = "",
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long = 0,
    /** Its current Drive copy, if one was ever created. */
    @ColumnInfo(name = "drive_file_id") val driveFileId: String? = null,
    /**
     * Set while it waits for the user's decision (R13 "Ask me first"): no countdown runs. Such an
     * item may not be in the trash at all, only gone from the phone.
     */
    @ColumnInfo(name = "waiting_reason") val waitingReason: VanishedReason? = null,
    /** Its file name, shown when the phone no longer has the file. */
    @ColumnInfo(name = "display_name") val displayName: String = "",
) {
    /** In the trash proper: Delete forever and Empty trash act on it. */
    val inTrash: Boolean
        get() = origin != null || localState == LocalState.TRASHED
}

/** An item that left the phone without Settle and waits for the user's decision. */
data class VanishedEntry(
    val id: String,
    val kind: MediaKind,
    @ColumnInfo(name = "taken_at_ms") val takenAtMs: Long,
    @ColumnInfo(name = "display_name") val displayName: String,
    val reason: VanishedReason,
    @ColumnInfo(name = "since_ms") val sinceMs: Long,
    val volume: String = "",
    @ColumnInfo(name = "relative_path") val relativePath: String = "",
)

/** Where an item's phone file is in MediaStore, trashed or not. */
data class PhoneFileRow(
    val id: String,
    val kind: MediaKind,
    val volume: String,
    @ColumnInfo(name = "media_store_id") val mediaStoreId: Long,
)

private const val IN_TRASH =
    """
    FROM media_item m JOIN local_file f ON f.item_id = m.id
    LEFT JOIN trash_entry t ON t.item_id = m.id
    WHERE (t.item_id IS NOT NULL OR f.local_state = 'TRASHED')
    """

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

    @Query(
        """
        SELECT m.id, m.kind, f.volume, f.media_store_id
        FROM media_item m JOIN local_file f ON f.item_id = m.id
        WHERE m.id IN (:ids) AND f.media_store_id IS NOT NULL
        """
    )
    suspend fun phoneFiles(ids: List<String>): List<PhoneFileRow>

    /** IDs of every item in the trash, for "Empty trash". */
    @Query("SELECT m.id $IN_TRASH") suspend fun trashIds(): List<String>

    @Query("SELECT EXISTS(SELECT 1 $IN_TRASH AND m.id = :itemId)")
    suspend fun isInTrash(itemId: String): Boolean

    /** The item's current Drive file, if Drive has created it. */
    @Query(
        """
        SELECT drive_file_id FROM upload
        WHERE item_id = :itemId AND state IN ('UPLOADED', 'VERIFIED', 'SUSPECT')
        """
    )
    suspend fun createdDriveFile(itemId: String): String?

    /**
     * Deletions made elsewhere whose Drive copies are still waiting for their trash: the window in
     * which a mass disappearance taken in slices is caught (see `afterScan`).
     */
    @Query(
        """
        $TRASH_ITEM_COLUMNS WHERE t.origin = 'ELSEWHERE'
            AND EXISTS(SELECT 1 FROM drive_op o WHERE o.item_id = m.id AND o.kind = 'TRASH')
        """
    )
    suspend fun pendingElsewhere(): List<TrashItemRow>

    /**
     * Items in Settle's trash that the phone no longer has and that no restore is under way for:
     * the ones whose Drive copies may since have gone for good.
     */
    @Query(
        """
        $TRASH_ITEM_COLUMNS WHERE t.item_id IS NOT NULL AND f.media_store_id IS NULL
            AND f.local_state NOT IN ('PRESENT', 'TRASHED')
            AND NOT EXISTS(SELECT 1 FROM restore_request r WHERE r.item_id = m.id)
        """
    )
    suspend fun phonelessTrash(): List<TrashItemRow>

    /** Items in Settle's trash whose time is over. */
    @Query("$TRASH_ITEM_COLUMNS WHERE t.expires_at_ms <= :nowMs")
    suspend fun expiredTrash(nowMs: Long): List<TrashItemRow>

    /**
     * Every item in the trash, Settle's entries and anything in the phone's trash, and the items
     * deleted in another app that wait for the user (R13 "Ask me first").
     */
    @Query(
        """
        SELECT m.id, m.kind, m.mime_type, m.taken_at_ms, m.duration_ms, f.volume,
            f.media_store_id, f.date_modified_s, f.local_state, m.cloud_state, t.origin,
            t.trashed_at_ms, t.expires_at_ms, r.requested_at_ms AS restore_requested_at_ms,
            r.problem AS restore_problem, f.relative_path, f.size_bytes, u.drive_file_id,
            f.display_name,
            CASE WHEN v.kept THEN NULL ELSE v.reason END AS waiting_reason
        FROM media_item m JOIN local_file f ON f.item_id = m.id
        LEFT JOIN trash_entry t ON t.item_id = m.id
        LEFT JOIN restore_request r ON r.item_id = m.id
        LEFT JOIN upload u ON u.item_id = m.id
        LEFT JOIN vanished v ON v.item_id = m.id
        WHERE (t.item_id IS NOT NULL OR f.local_state = 'TRASHED'
            OR (v.reason = 'ASK' AND NOT v.kept AND f.local_state <> 'PRESENT'))
        ORDER BY COALESCE(t.trashed_at_ms, v.since_ms, 0) DESC, m.taken_at_ms DESC
        """
    )
    fun observeTrash(): Flow<List<TrashedEntry>>

    /** Items waiting for the user's decision, newest first. */
    @Query(
        """
        SELECT m.id, m.kind, m.taken_at_ms, f.display_name, v.reason, v.since_ms, f.volume,
            f.relative_path
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
        changes.dropOps.forEach { deleteOpFor(it) }
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

    @Query("DELETE FROM drive_op WHERE drive_file_id = :driveFileId")
    suspend fun deleteOpFor(driveFileId: String)

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

    /** Asks for these downloads; a request that stopped before is tried again, resuming. */
    @Transaction
    suspend fun requestRestore(itemIds: List<String>, nowMs: Long) {
        addRestoreRequests(itemIds.map { RestoreRequestEntity(it, nowMs) })
        itemIds.forEach { setRestoreProblem(it, null) }
    }

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addRestoreRequests(requests: List<RestoreRequestEntity>)

    @Query("SELECT * FROM restore_request ORDER BY requested_at_ms, rowid")
    suspend fun restoreRequests(): List<RestoreRequestEntity>

    @Query("SELECT item_id FROM restore_request WHERE problem IN (:problems)")
    suspend fun restoresStoppedBy(problems: List<RestoreProblem>): List<String>

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
    suspend fun restored(
        itemId: String,
        volume: String,
        mediaStoreId: Long,
        sizeBytes: Long,
        dateModifiedS: Long,
    ) {
        clearMediaStoreId(volume, mediaStoreId)
        linkRestored(itemId, volume, mediaStoreId, sizeBytes, dateModifiedS)
        deleteEntry(itemId)
        deleteVanished(itemId)
        deleteRestoreRequest(itemId)
    }

    @Query(
        """
        UPDATE local_file SET media_store_id = NULL
        WHERE volume = :volume AND media_store_id = :mediaStoreId
        """
    )
    suspend fun clearMediaStoreId(volume: String, mediaStoreId: Long)

    @Query(
        """
        UPDATE local_file SET volume = :volume, media_store_id = :mediaStoreId,
            size_bytes = :sizeBytes, date_modified_s = :dateModifiedS, generation_modified = 0,
            local_state = 'PRESENT'
        WHERE item_id = :itemId
        """
    )
    suspend fun linkRestored(
        itemId: String,
        volume: String,
        mediaStoreId: Long,
        sizeBytes: Long,
        dateModifiedS: Long,
    )

    @Query("DELETE FROM restore_request WHERE item_id = :itemId")
    suspend fun deleteRestoreRequest(itemId: String)

    /**
     * A copy that couldn't be taken out of Drive's trash because Drive no longer has it: the item
     * isn't backed up any more, and backup takes it again.
     */
    @Transaction
    suspend fun forgetLostCopy(itemId: String, driveFileId: String) {
        if (deleteUploadOf(itemId, driveFileId) > 0) setCloudStateNone(itemId)
    }

    @Query("DELETE FROM upload WHERE item_id = :itemId AND drive_file_id = :driveFileId")
    suspend fun deleteUploadOf(itemId: String, driveFileId: String): Int

    @Query("UPDATE media_item SET cloud_state = 'NONE' WHERE id = :itemId")
    suspend fun setCloudStateNone(itemId: String)
}
