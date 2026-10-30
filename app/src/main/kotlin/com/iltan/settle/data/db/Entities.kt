package com.iltan.settle.data.db

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey
import com.iltan.settle.domain.CloudState
import com.iltan.settle.domain.LocalState
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
)

/** Per-folder choices. Only folders the user changed have a row; others use the defaults. */
@Entity(tableName = "folder_setting")
data class FolderSettingEntity(
    @PrimaryKey @ColumnInfo(name = "relative_path") val relativePath: String,
    @ColumnInfo(name = "hidden_from_timeline") val hiddenFromTimeline: Boolean,
)

/** Where the last scan of a MediaStore volume stopped. */
@Entity(tableName = "scan_cursor")
data class ScanCursorEntity(
    @PrimaryKey val volume: String,
    @ColumnInfo(name = "media_store_version") val mediaStoreVersion: String,
    val generation: Long,
    @ColumnInfo(name = "last_scan_at_ms") val lastScanAtMs: Long,
)
