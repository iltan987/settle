package com.iltan.settle.data.db

import androidx.room3.ColumnInfo
import com.iltan.settle.domain.CloudState
import com.iltan.settle.domain.LocalState
import com.iltan.settle.domain.MediaItemId
import com.iltan.settle.domain.scan.KnownFile
import com.iltan.settle.domain.scan.MediaKind

/** An item on the phone as grids and the viewer need it. */
data class MediaEntry(
    val id: String,
    val kind: MediaKind,
    @ColumnInfo(name = "mime_type") val mimeType: String,
    @ColumnInfo(name = "taken_at_ms") val takenAtMs: Long,
    @ColumnInfo(name = "duration_ms") val durationMs: Long,
    @ColumnInfo(name = "special_format") val specialFormat: Int,
    @ColumnInfo(name = "is_favorite") val isFavorite: Boolean,
    val width: Int,
    val height: Int,
    val volume: String,
    @ColumnInfo(name = "media_store_id") val mediaStoreId: Long,
    @ColumnInfo(name = "date_modified_s") val dateModifiedS: Long,
    @ColumnInfo(name = "cloud_state") val cloudState: CloudState,
)

/** A folder on the phone with its newest item as the cover. */
data class FolderSummary(
    @ColumnInfo(name = "relative_path") val relativePath: String,
    @ColumnInfo(name = "item_count") val itemCount: Int,
    @ColumnInfo(name = "latest_taken_at_ms") val latestTakenAtMs: Long,
    @ColumnInfo(name = "hidden_from_timeline") val hiddenFromTimeline: Boolean,
    @ColumnInfo(name = "cover_kind") val coverKind: MediaKind,
    @ColumnInfo(name = "cover_volume") val coverVolume: String,
    @ColumnInfo(name = "cover_media_store_id") val coverMediaStoreId: Long,
    @ColumnInfo(name = "cover_date_modified_s") val coverDateModifiedS: Long,
)

/** Details shown in the viewer's info sheet. */
data class MediaDetails(
    val id: String,
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "relative_path") val relativePath: String,
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long,
    @ColumnInfo(name = "mime_type") val mimeType: String,
    @ColumnInfo(name = "taken_at_ms") val takenAtMs: Long,
    val width: Int,
    val height: Int,
)

/** Input row for the scan rules; see [KnownFile]. */
data class KnownFileRow(
    @ColumnInfo(name = "item_id") val itemId: String,
    val volume: String,
    @ColumnInfo(name = "media_store_id") val mediaStoreId: Long?,
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long,
    @ColumnInfo(name = "taken_at_ms") val takenAtMs: Long,
    @ColumnInfo(name = "generation_modified") val generationModified: Long,
    @ColumnInfo(name = "local_state") val localState: LocalState,
    @ColumnInfo(name = "cloud_state") val cloudState: CloudState,
) {
    fun toKnownFile() =
        KnownFile(
            itemId = checkNotNull(MediaItemId.parseOrNull(itemId)) { "Bad item ID $itemId" },
            volume = volume,
            mediaStoreId = mediaStoreId,
            displayName = displayName,
            sizeBytes = sizeBytes,
            takenAtMillis = takenAtMs,
            generationModified = generationModified,
            localState = localState,
            cloudState = cloudState,
        )
}
