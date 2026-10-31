package com.iltan.settle.data.db

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Upsert
import com.iltan.settle.domain.CloudState
import com.iltan.settle.domain.scan.ScanPlan
import com.iltan.settle.domain.scan.ScanRow
import kotlinx.coroutines.flow.Flow

/** Columns every grid query selects into [MediaEntry]. */
private const val ENTRY_COLUMNS =
    """
    m.id, m.kind, m.mime_type, m.taken_at_ms, m.duration_ms, m.special_format, m.is_favorite,
    m.width, m.height, f.volume, f.media_store_id, f.date_modified_s, m.cloud_state
    """

private const val ON_PHONE =
    """
    FROM media_item m JOIN local_file f ON f.item_id = m.id
    WHERE f.local_state = 'PRESENT' AND f.media_store_id IS NOT NULL
    """

private const val NEWEST_FIRST = "ORDER BY m.taken_at_ms DESC, m.id DESC"

@Dao
interface LibraryDao {

    // Timeline and collections

    /** Everything on the phone except folders hidden from the timeline, newest first. */
    @Query(
        """
        SELECT $ENTRY_COLUMNS $ON_PHONE
        AND f.relative_path NOT IN
            (SELECT relative_path FROM folder_setting WHERE hidden_from_timeline)
        $NEWEST_FIRST
        """
    )
    fun timeline(): Flow<List<MediaEntry>>

    @Query("SELECT $ENTRY_COLUMNS $ON_PHONE AND f.relative_path = :relativePath $NEWEST_FIRST")
    fun folder(relativePath: String): Flow<List<MediaEntry>>

    @Query("SELECT $ENTRY_COLUMNS $ON_PHONE AND m.is_favorite $NEWEST_FIRST")
    fun favorites(): Flow<List<MediaEntry>>

    /**
     * Folders with their item count and newest item. SQLite takes the bare columns from the row
     * that holds MAX(taken_at_ms), which makes that row the cover.
     */
    @Query(
        """
        SELECT f.relative_path, COUNT(*) AS item_count, MAX(m.taken_at_ms) AS latest_taken_at_ms,
            COALESCE(s.hidden_from_timeline, 0) AS hidden_from_timeline, s.back_up,
            m.kind AS cover_kind, f.volume AS cover_volume,
            f.media_store_id AS cover_media_store_id, f.date_modified_s AS cover_date_modified_s
        $ON_PHONE_LEFT_JOIN_SETTINGS
        GROUP BY f.relative_path
        ORDER BY latest_taken_at_ms DESC
        """
    )
    fun folders(): Flow<List<FolderSummary>>

    @Query("SELECT COUNT(*) $ON_PHONE") fun onPhoneCount(): Flow<Int>

    @Query(
        """
        SELECT m.id, f.display_name, f.relative_path, f.size_bytes, m.mime_type, m.taken_at_ms,
            m.width, m.height
        FROM media_item m JOIN local_file f ON f.item_id = m.id
        WHERE m.id = :id
        """
    )
    suspend fun details(id: String): MediaDetails?

    // User choices

    /** Changes one folder choice and keeps the others. */
    @Transaction
    suspend fun setFolderHidden(relativePath: String, hidden: Boolean) {
        insertFolderSetting(FolderSettingEntity(relativePath))
        updateFolderHidden(relativePath, hidden)
    }

    @Transaction
    suspend fun setFolderBackUp(relativePath: String, backUp: Boolean) {
        insertFolderSetting(FolderSettingEntity(relativePath))
        updateFolderBackUp(relativePath, backUp)
    }

    @Query("SELECT * FROM folder_setting WHERE relative_path = :path")
    suspend fun folderSetting(path: String): FolderSettingEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertFolderSetting(setting: FolderSettingEntity)

    @Query("UPDATE folder_setting SET hidden_from_timeline = :hidden WHERE relative_path = :path")
    suspend fun updateFolderHidden(path: String, hidden: Boolean)

    @Query("UPDATE folder_setting SET back_up = :backUp WHERE relative_path = :path")
    suspend fun updateFolderBackUp(path: String, backUp: Boolean)

    @Query("UPDATE media_item SET is_favorite = :favorite WHERE id = :id")
    suspend fun setFavorite(id: String, favorite: Boolean)

    // Scanning

    @Query(
        """
        SELECT f.item_id, f.volume, f.media_store_id, f.display_name, f.size_bytes,
            m.taken_at_ms, f.generation_modified, f.local_state, m.cloud_state
        FROM local_file f JOIN media_item m ON m.id = f.item_id
        WHERE f.volume = :volume
        """
    )
    suspend fun knownFiles(volume: String): List<KnownFileRow>

    @Query(
        "SELECT EXISTS(SELECT 1 FROM local_file WHERE volume = :volume AND local_state = 'MISSING')"
    )
    suspend fun hasMissingFiles(volume: String): Boolean

    @Query("SELECT * FROM scan_cursor WHERE volume = :volume")
    suspend fun cursor(volume: String): ScanCursorEntity?

    @Query("SELECT MAX(last_scan_at_ms) FROM scan_cursor") fun lastScanAt(): Flow<Long?>

    /** Applies one volume's scan result and moves its cursor, all or nothing. */
    @Transaction
    suspend fun applyScan(plan: ScanPlan, cursor: ScanCursorEntity, nowMs: Long) {
        plan.forgets.forEach { deleteItem(it.value) }
        // Release MediaStore IDs before reassigning them: after a MediaStore rebuild two files can
        // swap IDs, which would break the unique index halfway through.
        (plan.updates.map { it.itemId } + plan.missing).forEach { releaseMediaStoreId(it.value) }
        plan.missing.forEach { markMissing(it.value) }
        for ((itemId, row) in plan.updates) {
            updateItem(
                id = itemId.value,
                kind = row.kind.name,
                takenAtMs = row.takenAtMillis,
                mimeType = row.mimeType,
                width = row.width,
                height = row.height,
                orientation = row.orientation,
                durationMs = row.durationMillis,
                specialFormat = row.specialFormat,
                isFavorite = row.isFavorite,
            )
            upsertLocalFile(row.toLocalFile(itemId.value))
        }
        for ((itemId, row) in plan.inserts) {
            insertItem(row.toMediaItem(itemId.value, nowMs))
            upsertLocalFile(row.toLocalFile(itemId.value))
        }
        upsertCursor(cursor)
    }

    @Query("DELETE FROM media_item WHERE id = :id") suspend fun deleteItem(id: String)

    @Query("UPDATE local_file SET media_store_id = NULL WHERE item_id = :id")
    suspend fun releaseMediaStoreId(id: String)

    @Query("UPDATE local_file SET local_state = 'MISSING' WHERE item_id = :id")
    suspend fun markMissing(id: String)

    @Query(
        """
        UPDATE media_item SET kind = :kind, taken_at_ms = :takenAtMs, mime_type = :mimeType,
            width = :width, height = :height, orientation = :orientation,
            duration_ms = :durationMs, special_format = :specialFormat, is_favorite = :isFavorite
        WHERE id = :id
        """
    )
    suspend fun updateItem(
        id: String,
        kind: String,
        takenAtMs: Long,
        mimeType: String,
        width: Int,
        height: Int,
        orientation: Int,
        durationMs: Long,
        specialFormat: Int,
        isFavorite: Boolean,
    )

    @Insert suspend fun insertItem(item: MediaItemEntity)

    @Upsert suspend fun upsertLocalFile(file: LocalFileEntity)

    @Upsert suspend fun upsertCursor(cursor: ScanCursorEntity)
}

private const val ON_PHONE_LEFT_JOIN_SETTINGS =
    """
    FROM media_item m JOIN local_file f ON f.item_id = m.id
    LEFT JOIN folder_setting s ON s.relative_path = f.relative_path
    WHERE f.local_state = 'PRESENT' AND f.media_store_id IS NOT NULL
    """

private fun ScanRow.toMediaItem(id: String, nowMs: Long) =
    MediaItemEntity(
        id = id,
        kind = kind,
        takenAtMs = takenAtMillis,
        mimeType = mimeType,
        width = width,
        height = height,
        orientation = orientation,
        durationMs = durationMillis,
        specialFormat = specialFormat,
        isFavorite = isFavorite,
        firstSeenAtMs = nowMs,
        cloudState = CloudState.NONE,
    )

private fun ScanRow.toLocalFile(itemId: String) =
    LocalFileEntity(
        itemId = itemId,
        volume = volume,
        mediaStoreId = mediaStoreId,
        relativePath = relativePath,
        displayName = displayName,
        sizeBytes = sizeBytes,
        dateModifiedS = dateModifiedSeconds,
        generationModified = generationModified,
        localState = localState,
        dateAddedS = dateAddedSeconds,
    )
