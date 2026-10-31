package com.iltan.settle.data.db

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey
import com.iltan.settle.domain.CloudState
import com.iltan.settle.domain.LocalState
import com.iltan.settle.domain.backup.BackupPause
import com.iltan.settle.domain.backup.UploadProblem
import com.iltan.settle.domain.deletion.DriveOpKind
import com.iltan.settle.domain.deletion.ExternalDeletionPolicy
import com.iltan.settle.domain.deletion.RestoreProblem
import com.iltan.settle.domain.deletion.TrashOrigin
import com.iltan.settle.domain.deletion.VanishedReason
import com.iltan.settle.domain.scan.MediaKind

/** One photo or video in the library, wherever its bytes are (ADR-001, ADR-002). */
@Entity(tableName = "media_item", indices = [Index("taken_at_ms")])
data class MediaItemEntity(
    /** ULID, see `MediaItemId`. */
    @PrimaryKey val id: String,
    val kind: MediaKind,
    @ColumnInfo(name = "taken_at_ms") val takenAtMs: Long,
    @ColumnInfo(name = "mime_type") val mimeType: String,
    val width: Int,
    val height: Int,
    val orientation: Int,
    @ColumnInfo(name = "duration_ms") val durationMs: Long,
    @ColumnInfo(name = "special_format") val specialFormat: Int,
    @ColumnInfo(name = "is_favorite") val isFavorite: Boolean,
    @ColumnInfo(name = "first_seen_at_ms") val firstSeenAtMs: Long,
    @ColumnInfo(name = "cloud_state") val cloudState: CloudState,
)

/** The item's original on the phone, as MediaStore last reported it. */
@Entity(
    tableName = "local_file",
    foreignKeys =
        [
            ForeignKey(
                entity = MediaItemEntity::class,
                parentColumns = ["id"],
                childColumns = ["item_id"],
                onDelete = ForeignKey.CASCADE,
            )
        ],
    indices = [Index(value = ["volume", "media_store_id"], unique = true), Index("relative_path")],
)
data class LocalFileEntity(
    @PrimaryKey @ColumnInfo(name = "item_id") val itemId: String,
    val volume: String,
    /** Null while the file is missing (see `KnownFile.mediaStoreId`). */
    @ColumnInfo(name = "media_store_id") val mediaStoreId: Long?,
    @ColumnInfo(name = "relative_path") val relativePath: String,
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long,
    @ColumnInfo(name = "date_modified_s") val dateModifiedS: Long,
    @ColumnInfo(name = "generation_modified") val generationModified: Long,
    @ColumnInfo(name = "local_state") val localState: LocalState,
    /** When the file appeared on the phone; the grace period counts from here (R2). */
    @ColumnInfo(name = "date_added_s", defaultValue = "0") val dateAddedS: Long = 0,
)

/** Per-folder choices. Only folders the user changed have a row; others use the defaults. */
@Entity(tableName = "folder_setting")
data class FolderSettingEntity(
    @PrimaryKey @ColumnInfo(name = "relative_path") val relativePath: String,
    @ColumnInfo(name = "hidden_from_timeline", defaultValue = "0")
    val hiddenFromTimeline: Boolean = false,
    /** The user's backup switch; null means the default (only the camera folder backs up). */
    @ColumnInfo(name = "back_up") val backUp: Boolean? = null,
)

/** Where the last scan of a MediaStore volume stopped. */
@Entity(tableName = "scan_cursor")
data class ScanCursorEntity(
    @PrimaryKey val volume: String,
    @ColumnInfo(name = "media_store_version") val mediaStoreVersion: String,
    val generation: Long,
    @ColumnInfo(name = "last_scan_at_ms") val lastScanAtMs: Long,
)

/** An item's upload to Drive, step by step (ADR-004); see `UploadRecord`. */
@Entity(
    tableName = "upload",
    foreignKeys =
        [
            ForeignKey(
                entity = MediaItemEntity::class,
                parentColumns = ["id"],
                childColumns = ["item_id"],
                onDelete = ForeignKey.CASCADE,
            )
        ],
)
data class UploadEntity(
    @PrimaryKey @ColumnInfo(name = "item_id") val itemId: String,
    @ColumnInfo(name = "drive_file_id") val driveFileId: String,
    val state: CloudState,
    /** The original's size and modification time when the upload started. */
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long,
    @ColumnInfo(name = "date_modified_s") val dateModifiedS: Long,
    @ColumnInfo(name = "session_uri") val sessionUri: String?,
    @ColumnInfo(name = "session_started_at_ms") val sessionStartedAtMs: Long?,
    @ColumnInfo(name = "confirmed_bytes") val confirmedBytes: Long,
    val sha256: String?,
    val md5: String?,
    @ColumnInfo(name = "verified_at_ms") val verifiedAtMs: Long?,
    val problem: UploadProblem?,
)

/**
 * A Drive file a newer upload of the item replaced (see `UploadStore.recordSuperseded`). No foreign
 * key: the file stays in Drive even if the item leaves the library, so the note must too.
 */
@Entity(tableName = "superseded_file", indices = [Index("item_id")])
data class SupersededFileEntity(
    @PrimaryKey @ColumnInfo(name = "drive_file_id") val driveFileId: String,
    @ColumnInfo(name = "item_id") val itemId: String,
)

/** Drive IDs of the folders Settle created, by path such as `Settle/media/2026/10`. */
@Entity(tableName = "drive_folder")
data class DriveFolderEntity(
    @PrimaryKey val path: String,
    @ColumnInfo(name = "drive_id") val driveId: String,
)

/** The connected Google account. At most one row. */
@Entity(tableName = "drive_account")
data class DriveAccountEntity(@PrimaryKey val id: Int = 0, val email: String)

/** Automatic backup's waiting room after a failed try; removed once the item is verified. */
@Entity(
    tableName = "upload_retry",
    foreignKeys =
        [
            ForeignKey(
                entity = MediaItemEntity::class,
                parentColumns = ["id"],
                childColumns = ["item_id"],
                onDelete = ForeignKey.CASCADE,
            )
        ],
)
data class UploadRetryEntity(
    @PrimaryKey @ColumnInfo(name = "item_id") val itemId: String,
    /** Failed tries since the last success. */
    val attempts: Int,
    /** Not before this time; null when only the user can unblock it. */
    @ColumnInfo(name = "retry_at_ms") val retryAtMs: Long?,
)

/**
 * "Back up now" for one item, kept until the item is verified so it survives process death (R3).
 */
@Entity(
    tableName = "backup_request",
    foreignKeys =
        [
            ForeignKey(
                entity = MediaItemEntity::class,
                parentColumns = ["id"],
                childColumns = ["item_id"],
                onDelete = ForeignKey.CASCADE,
            )
        ],
)
data class BackupRequestEntity(
    @PrimaryKey @ColumnInfo(name = "item_id") val itemId: String,
    @ColumnInfo(name = "requested_at_ms") val requestedAtMs: Long,
)

/** The user's backup settings and why backup is paused, if it is. At most one row. */
@Entity(tableName = "backup_settings")
data class BackupSettingsEntity(
    @PrimaryKey val id: Int = 0,
    val automatic: Boolean,
    @ColumnInfo(name = "grace_ms") val graceMs: Long,
    @ColumnInfo(name = "only_while_charging") val onlyWhileCharging: Boolean,
    @ColumnInfo(name = "use_mobile_data") val useMobileData: Boolean,
    val paused: Boolean,
    /** POLITE, LIMIT or UNLIMITED (see `NetworkUse`). */
    @ColumnInfo(name = "network_use") val networkUse: String,
    @ColumnInfo(name = "limit_mbps") val limitMbps: Int,
    /** Set when Drive stopped backup until the user acts: AUTH_NEEDED or QUOTA_FULL. */
    val blocked: BackupPause?,
    /** What happens to the Drive copy of an item deleted in another app (R13). */
    @ColumnInfo(name = "external_deletion", defaultValue = "MOVE_DRIVE_COPY_TO_TRASH")
    val externalDeletion: ExternalDeletionPolicy = ExternalDeletionPolicy.MOVE_DRIVE_COPY_TO_TRASH,
)

/** An item in Settle's trash (ADR-015); see `TrashEntry`. */
@Entity(
    tableName = "trash_entry",
    foreignKeys =
        [
            ForeignKey(
                entity = MediaItemEntity::class,
                parentColumns = ["id"],
                childColumns = ["item_id"],
                onDelete = ForeignKey.CASCADE,
            )
        ],
)
data class TrashEntryEntity(
    @PrimaryKey @ColumnInfo(name = "item_id") val itemId: String,
    val origin: TrashOrigin,
    @ColumnInfo(name = "trashed_at_ms") val trashedAtMs: Long,
    @ColumnInfo(name = "expires_at_ms") val expiresAtMs: Long,
)

/** A backed-up item that left the phone without Settle and waits for the user (R13, R14). */
@Entity(
    tableName = "vanished",
    foreignKeys =
        [
            ForeignKey(
                entity = MediaItemEntity::class,
                parentColumns = ["id"],
                childColumns = ["item_id"],
                onDelete = ForeignKey.CASCADE,
            )
        ],
)
data class VanishedEntity(
    @PrimaryKey @ColumnInfo(name = "item_id") val itemId: String,
    @ColumnInfo(name = "since_ms") val sinceMs: Long,
    val reason: VanishedReason,
    /** The user chose to keep the Drive copy. */
    val kept: Boolean,
)

/**
 * A queued change to one Drive file (ADR-015); see `DriveOp`. No foreign key: a permanent delete
 * must still run after the item left the library.
 */
@Entity(tableName = "drive_op", indices = [Index("item_id"), Index("not_before_ms")])
data class DriveOpEntity(
    @PrimaryKey @ColumnInfo(name = "drive_file_id") val driveFileId: String,
    @ColumnInfo(name = "item_id") val itemId: String,
    val kind: DriveOpKind,
    @ColumnInfo(name = "not_before_ms") val notBeforeMs: Long,
    val attempts: Int,
)

/** "Restore" for an item whose phone file is gone: downloaded from Drive, kept until it is done. */
@Entity(
    tableName = "restore_request",
    foreignKeys =
        [
            ForeignKey(
                entity = MediaItemEntity::class,
                parentColumns = ["id"],
                childColumns = ["item_id"],
                onDelete = ForeignKey.CASCADE,
            )
        ],
)
data class RestoreRequestEntity(
    @PrimaryKey @ColumnInfo(name = "item_id") val itemId: String,
    @ColumnInfo(name = "requested_at_ms") val requestedAtMs: Long,
    /** The unfinished download's pending MediaStore item. */
    @ColumnInfo(name = "pending_uri") val pendingUri: String? = null,
    /** Why the last try stopped; null while it waits or runs. */
    val problem: RestoreProblem? = null,
)
