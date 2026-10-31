package com.iltan.settle.domain.scan

import com.iltan.settle.domain.LocalState

enum class MediaKind {
    IMAGE,
    VIDEO,
}

/** One image or video row read from MediaStore, as plain data so the scan rules stay pure. */
data class ScanRow(
    val volume: String,
    val mediaStoreId: Long,
    /** Folder relative to the volume root, with a trailing slash, e.g. `DCIM/Camera/`. */
    val relativePath: String,
    val displayName: String,
    val mimeType: String,
    val kind: MediaKind,
    val sizeBytes: Long,
    /** Capture time from EXIF or video metadata; null or 0 when the file doesn't carry one. */
    val dateTakenMillis: Long?,
    val dateModifiedSeconds: Long,
    val width: Int,
    val height: Int,
    val orientation: Int,
    val durationMillis: Long,
    /** MediaStore's `_special_format`, e.g. motion photo; 0 when none. */
    val specialFormat: Int,
    val isFavorite: Boolean,
    val isTrashed: Boolean,
    val generationModified: Long,
    /** When the file appeared on the phone (MediaStore `DATE_ADDED`); 0 when unknown. */
    val dateAddedSeconds: Long = 0,
) {
    /** The time the timeline sorts by: capture time, else the file's modification time. */
    val takenAtMillis: Long
        get() = dateTakenMillis?.takeIf { it > 0 } ?: (dateModifiedSeconds * 1000)

    val localState: LocalState
        get() = if (isTrashed) LocalState.TRASHED else LocalState.PRESENT
}

/**
 * The folder a trashed file belongs to. Samsung's One UI moves trashed files under
 * `.trash-storage/` and reports that as their folder; stock Android keeps the folder. Kept as the
 * original so a restore puts the file back where it was (seen on the S24 FE, 2026-10-31).
 */
fun folderBeforeTrash(relativePath: String): String =
    relativePath.removePrefix(SAMSUNG_TRASH_FOLDER)

private const val SAMSUNG_TRASH_FOLDER = ".trash-storage/"
