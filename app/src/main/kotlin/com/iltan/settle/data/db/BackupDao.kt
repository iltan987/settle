package com.iltan.settle.data.db

import androidx.room3.ColumnInfo
import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Upsert
import com.iltan.settle.domain.CloudState
import com.iltan.settle.domain.scan.MediaKind
import kotlinx.coroutines.flow.Flow

/** What an upload needs to know about an item; see `BackupItem`. */
data class BackupItemRow(
    val id: String,
    val kind: MediaKind,
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "mime_type") val mimeType: String,
    @ColumnInfo(name = "relative_path") val relativePath: String,
    @ColumnInfo(name = "taken_at_ms") val takenAtMs: Long,
    val width: Int,
    val height: Int,
    @ColumnInfo(name = "special_format") val specialFormat: Int,
)

/** Where an item's original is in MediaStore. */
data class LocalFileRef(
    val kind: MediaKind,
    val volume: String,
    @ColumnInfo(name = "media_store_id") val mediaStoreId: Long,
)

@Dao
interface BackupDao {

    @Query(
        """
        SELECT m.kind, f.volume, f.media_store_id
        FROM media_item m JOIN local_file f ON f.item_id = m.id
        WHERE m.id = :itemId AND f.local_state = 'PRESENT' AND f.media_store_id IS NOT NULL
        """
    )
    suspend fun localFile(itemId: String): LocalFileRef?

    @Query(
        """
        SELECT m.id, m.kind, f.display_name, m.mime_type, f.relative_path, m.taken_at_ms,
            m.width, m.height, m.special_format
        FROM media_item m JOIN local_file f ON f.item_id = m.id
        WHERE m.id = :itemId
        """
    )
    suspend fun item(itemId: String): BackupItemRow?

    @Query("SELECT * FROM upload WHERE item_id = :itemId")
    suspend fun upload(itemId: String): UploadEntity?

    @Query("SELECT item_id FROM local_file WHERE media_store_id = :mediaStoreId")
    suspend fun itemIdForMediaStoreId(mediaStoreId: Long): String?

    @Query("SELECT * FROM upload WHERE item_id = :itemId")
    fun observeUpload(itemId: String): Flow<UploadEntity?>

    /** Uploads that stopped before verification, oldest first. */
    @Query(
        """
        SELECT item_id FROM upload
        WHERE state IN ('RESERVED', 'UPLOADING', 'UPLOADED') AND problem IS NULL
        ORDER BY rowid
        """
    )
    suspend fun unfinishedUploads(): List<String>

    /** The upload record and the item's cloud state change together (ADR-002). */
    @Transaction
    suspend fun saveUpload(upload: UploadEntity) {
        upsertUpload(upload)
        setCloudState(upload.itemId, upload.state)
    }

    @Upsert suspend fun upsertUpload(upload: UploadEntity)

    @Query("UPDATE media_item SET cloud_state = :state WHERE id = :itemId")
    suspend fun setCloudState(itemId: String, state: CloudState)

    @Query("SELECT drive_id FROM drive_folder WHERE path = :path")
    suspend fun folderId(path: String): String?

    @Upsert suspend fun upsertFolder(folder: DriveFolderEntity)

    @Query("DELETE FROM drive_folder") suspend fun deleteFolders()

    @Query("SELECT * FROM drive_account WHERE id = 0") fun account(): Flow<DriveAccountEntity?>

    @Query("SELECT email FROM drive_account WHERE id = 0") suspend fun accountEmail(): String?

    @Upsert suspend fun setAccount(account: DriveAccountEntity)

    @Query("DELETE FROM drive_account") suspend fun deleteAccount()

    /**
     * Forgets every upload and folder, as if nothing had been backed up. Only for the debug sandbox
     * reset (ADR-008), after its Drive folder went to the trash.
     */
    @Transaction
    suspend fun forgetAllUploads() {
        deleteUploads()
        resetCloudStates()
        deleteFolders()
    }

    @Query("DELETE FROM upload") suspend fun deleteUploads()

    @Query("UPDATE media_item SET cloud_state = 'NONE'") suspend fun resetCloudStates()
}
