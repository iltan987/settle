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
import com.iltan.settle.domain.backup.UploadProblem
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

/** What the backup rules need about an item on the phone that isn't verified yet. */
data class BackupCandidateRow(
    val id: String,
    @ColumnInfo(name = "local_state") val localState: LocalState,
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long,
    @ColumnInfo(name = "taken_at_ms") val takenAtMs: Long,
    @ColumnInfo(name = "date_added_s") val dateAddedS: Long,
    @ColumnInfo(name = "relative_path") val relativePath: String,
    /** The folder's backup switch, null when the user never set it. */
    @ColumnInfo(name = "back_up") val backUp: Boolean?,
    @ColumnInfo(name = "cloud_state") val cloudState: CloudState,
    val problem: UploadProblem?,
    @ColumnInfo(name = "retry_at_ms") val retryAtMs: Long?,
    val attempts: Int?,
    @ColumnInfo(name = "requested_at_ms") val requestedAtMs: Long?,
)

/** Where an item's original is in MediaStore. */
data class LocalFileRef(
    val kind: MediaKind,
    val volume: String,
    @ColumnInfo(name = "media_store_id") val mediaStoreId: Long,
)

private const val CANDIDATES =
    """
    FROM media_item m JOIN local_file f ON f.item_id = m.id
    LEFT JOIN folder_setting s ON s.relative_path = f.relative_path
    LEFT JOIN upload u ON u.item_id = m.id
    LEFT JOIN upload_retry r ON r.item_id = m.id
    LEFT JOIN backup_request q ON q.item_id = m.id
    WHERE f.local_state = 'PRESENT' AND f.media_store_id IS NOT NULL
        AND m.cloud_state <> 'VERIFIED'
    """

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

    /**
     * The upload record and the item's cloud state change together (ADR-002). A verified item no
     * longer waits for a retry or a request.
     */
    @Transaction
    suspend fun saveUpload(upload: UploadEntity) {
        upsertUpload(upload)
        setCloudState(upload.itemId, upload.state)
        if (upload.state == CloudState.VERIFIED) {
            deleteRetry(upload.itemId)
            deleteRequest(upload.itemId)
        }
    }

    @Upsert suspend fun upsertUpload(upload: UploadEntity)

    @Query("UPDATE media_item SET cloud_state = :state WHERE id = :itemId")
    suspend fun setCloudState(itemId: String, state: CloudState)

    // Automatic backup (ADR-014)

    /** Items on the phone without a verified copy, whatever their folder's choice. */
    @Query(
        """
        SELECT m.id, f.local_state, f.size_bytes, m.taken_at_ms, f.date_added_s, f.relative_path,
            s.back_up, m.cloud_state, u.problem, r.retry_at_ms, r.attempts, q.requested_at_ms
        $CANDIDATES
        """
    )
    suspend fun backupCandidates(): List<BackupCandidateRow>

    @Query(
        """
        SELECT m.id, f.local_state, f.size_bytes, m.taken_at_ms, f.date_added_s, f.relative_path,
            s.back_up, m.cloud_state, u.problem, r.retry_at_ms, r.attempts, q.requested_at_ms
        $CANDIDATES
        """
    )
    fun observeBackupCandidates(): Flow<List<BackupCandidateRow>>

    /** One item's row of [backupCandidates]; null once verified or off the phone. */
    @Query(
        """
        SELECT m.id, f.local_state, f.size_bytes, m.taken_at_ms, f.date_added_s, f.relative_path,
            s.back_up, m.cloud_state, u.problem, r.retry_at_ms, r.attempts, q.requested_at_ms
        $CANDIDATES AND m.id = :itemId
        """
    )
    fun observeBackupCandidate(itemId: String): Flow<BackupCandidateRow?>

    @Query(
        """
        SELECT COUNT(*) FROM media_item m JOIN local_file f ON f.item_id = m.id
        WHERE f.local_state = 'PRESENT' AND m.cloud_state = 'VERIFIED'
        """
    )
    fun backedUpCount(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addRequests(requests: List<BackupRequestEntity>)

    @Query("SELECT * FROM backup_request WHERE item_id = :itemId")
    fun observeRequest(itemId: String): Flow<BackupRequestEntity?>

    @Query("DELETE FROM backup_request WHERE item_id = :itemId")
    suspend fun deleteRequest(itemId: String)

    @Upsert suspend fun saveRetry(retry: UploadRetryEntity)

    @Query("SELECT * FROM upload_retry WHERE item_id = :itemId")
    suspend fun retry(itemId: String): UploadRetryEntity?

    @Query("DELETE FROM upload_retry WHERE item_id = :itemId")
    suspend fun deleteRetry(itemId: String)

    @Query("SELECT * FROM backup_settings WHERE id = 0")
    fun observeSettings(): Flow<BackupSettingsEntity?>

    @Query("SELECT * FROM backup_settings WHERE id = 0")
    suspend fun settings(): BackupSettingsEntity?

    @Upsert suspend fun saveSettings(settings: BackupSettingsEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addSuperseded(file: SupersededFileEntity)

    @Query("SELECT * FROM superseded_file WHERE item_id = :itemId")
    suspend fun superseded(itemId: String): List<SupersededFileEntity>

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
        deleteRetries()
        deleteSuperseded()
        resetCloudStates()
        deleteFolders()
    }

    @Query("DELETE FROM upload") suspend fun deleteUploads()

    @Query("DELETE FROM superseded_file") suspend fun deleteSuperseded()

    @Query("DELETE FROM upload_retry") suspend fun deleteRetries()

    @Query("UPDATE media_item SET cloud_state = 'NONE'") suspend fun resetCloudStates()
}
