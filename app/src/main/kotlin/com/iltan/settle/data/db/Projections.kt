package com.iltan.settle.data.db

import androidx.room3.ColumnInfo
import androidx.room3.Embedded
import com.iltan.settle.domain.CloudState
import com.iltan.settle.domain.LocalState
import com.iltan.settle.domain.MediaItemId
import com.iltan.settle.domain.backup.UploadProblem
import com.iltan.settle.domain.backup.folderBacksUp
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
    /** Degrees clockwise the stored pixels are turned for display (EXIF orientation). */
    val orientation: Int = 0,
    val volume: String,
    @ColumnInfo(name = "media_store_id") val mediaStoreId: Long,
    @ColumnInfo(name = "date_modified_s") val dateModifiedS: Long,
    @ColumnInfo(name = "cloud_state") val cloudState: CloudState,
)

/** An item on the phone without a verified copy in Drive, for the Backup tab (J). */
data class PendingEntry(
    @Embedded val entry: MediaEntry,
    @ColumnInfo(name = "relative_path") val relativePath: String,
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long,
    /** Why its last upload stopped, if it did. */
    val problem: UploadProblem?,
)

/** Width over height as the photo is shown, or null when MediaStore didn't know its size. */
val MediaEntry.displayAspectRatio: Float?
    get() {
        if (width <= 0 || height <= 0) return null
        val turned = orientation % 180 != 0
        return if (turned) height.toFloat() / width else width.toFloat() / height
    }

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
    /** The user's backup switch for the folder; null means the default. */
    @ColumnInfo(name = "back_up") val backUp: Boolean? = null,
    /** Items with a verified copy in Drive. */
    @ColumnInfo(name = "backed_up_count") val backedUpCount: Int = 0,
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long = 0,
) {
    /** Whether new items in this folder are backed up (only the camera's by default). */
    val backsUp: Boolean
        get() = folderBacksUp(relativePath, backUp)
}

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
