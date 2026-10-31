package com.iltan.settle.data.media

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.MediaStore.Files.FileColumns
import android.provider.MediaStore.MediaColumns
import com.iltan.settle.domain.scan.MediaKind
import com.iltan.settle.domain.scan.ScanRow

/** Read access to the phone's media index, behind an interface so sync logic can be tested. */
interface MediaSource {
    fun volumes(): Set<String>

    /** Changes when MediaStore was rebuilt; null if the volume isn't mounted. */
    fun version(volume: String): String?

    fun generation(volume: String): Long

    /** Image and video rows, trashed ones included; only those changed after [sinceGeneration]. */
    fun rows(volume: String, sinceGeneration: Long? = null): List<ScanRow>

    /** IDs of every image and video row, trashed ones included. */
    fun ids(volume: String): Set<Long>
}

/**
 * Queries `MediaStore.Files` once per volume: it is the only collection with the special-format
 * column (motion photos) and returns images and videos together (ADR-012).
 */
class MediaStoreSource(private val context: Context) : MediaSource {

    private val resolver: ContentResolver = context.contentResolver

    override fun volumes(): Set<String> = MediaStore.getExternalVolumeNames(context)

    override fun version(volume: String): String? = MediaStore.getVersion(context, volume)

    override fun generation(volume: String): Long = MediaStore.getGeneration(context, volume)

    override fun rows(volume: String, sinceGeneration: Long?): List<ScanRow> {
        val selection = StringBuilder(MEDIA_SELECTION)
        val args = mutableListOf<String>()
        if (sinceGeneration != null) {
            // The docs don't say whether an insert also sets GENERATION_MODIFIED; check both.
            selection.append(
                " AND (${MediaColumns.GENERATION_MODIFIED} > ? OR ${MediaColumns.GENERATION_ADDED} > ?)"
            )
            args += sinceGeneration.toString()
            args += sinceGeneration.toString()
        }
        return query(volume, rowProjection, selection.toString(), args) { cursor ->
            val c = RowColumns(cursor)
            buildList(cursor.count) { while (cursor.moveToNext()) add(c.read(cursor, volume)) }
        }
    }

    override fun ids(volume: String): Set<Long> =
        query(volume, arrayOf(MediaColumns._ID), MEDIA_SELECTION, emptyList()) { cursor ->
            HashSet<Long>(cursor.count * 2).apply {
                while (cursor.moveToNext()) add(cursor.getLong(0))
            }
        }

    private fun <T> query(
        volume: String,
        projection: Array<String>,
        selection: String,
        args: List<String>,
        read: (Cursor) -> T,
    ): T {
        val queryArgs =
            Bundle().apply {
                putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
                putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args.toTypedArray())
                // Trashed rows are hidden by default; without them a trashed file would look
                // deleted. Pending rows (still being written) stay hidden.
                putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
            }
        val cursor =
            resolver.query(MediaStore.Files.getContentUri(volume), projection, queryArgs, null)
                ?: error("MediaStore returned no cursor for $volume")
        return cursor.use(read)
    }

    private val rowProjection: Array<String> = buildList {
        addAll(
            listOf(
                MediaColumns._ID,
                MediaColumns.RELATIVE_PATH,
                MediaColumns.DISPLAY_NAME,
                MediaColumns.MIME_TYPE,
                FileColumns.MEDIA_TYPE,
                MediaColumns.SIZE,
                MediaColumns.DATE_TAKEN,
                MediaColumns.DATE_MODIFIED,
                MediaColumns.DATE_ADDED,
                MediaColumns.WIDTH,
                MediaColumns.HEIGHT,
                MediaColumns.ORIENTATION,
                MediaColumns.DURATION,
                MediaColumns.IS_FAVORITE,
                MediaColumns.IS_TRASHED,
                MediaColumns.GENERATION_MODIFIED,
            )
        )
        SPECIAL_FORMAT_COLUMN?.let(::add)
    }
        .toTypedArray()

    private class RowColumns(cursor: Cursor) {
        private val id = cursor.getColumnIndexOrThrow(MediaColumns._ID)
        private val path = cursor.getColumnIndexOrThrow(MediaColumns.RELATIVE_PATH)
        private val name = cursor.getColumnIndexOrThrow(MediaColumns.DISPLAY_NAME)
        private val mime = cursor.getColumnIndexOrThrow(MediaColumns.MIME_TYPE)
        private val mediaType = cursor.getColumnIndexOrThrow(FileColumns.MEDIA_TYPE)
        private val size = cursor.getColumnIndexOrThrow(MediaColumns.SIZE)
        private val taken = cursor.getColumnIndexOrThrow(MediaColumns.DATE_TAKEN)
        private val modified = cursor.getColumnIndexOrThrow(MediaColumns.DATE_MODIFIED)
        private val added = cursor.getColumnIndexOrThrow(MediaColumns.DATE_ADDED)
        private val width = cursor.getColumnIndexOrThrow(MediaColumns.WIDTH)
        private val height = cursor.getColumnIndexOrThrow(MediaColumns.HEIGHT)
        private val orientation = cursor.getColumnIndexOrThrow(MediaColumns.ORIENTATION)
        private val duration = cursor.getColumnIndexOrThrow(MediaColumns.DURATION)
        private val favorite = cursor.getColumnIndexOrThrow(MediaColumns.IS_FAVORITE)
        private val trashed = cursor.getColumnIndexOrThrow(MediaColumns.IS_TRASHED)
        private val generation = cursor.getColumnIndexOrThrow(MediaColumns.GENERATION_MODIFIED)
        private val specialFormat = SPECIAL_FORMAT_COLUMN?.let(cursor::getColumnIndexOrThrow) ?: -1

        fun read(c: Cursor, volume: String) =
            ScanRow(
                volume = volume,
                mediaStoreId = c.getLong(id),
                relativePath = c.getStringOrNull(path).orEmpty(),
                displayName = c.getStringOrNull(name).orEmpty(),
                mimeType = c.getStringOrNull(mime).orEmpty(),
                kind =
                    if (c.getInt(mediaType) == FileColumns.MEDIA_TYPE_VIDEO) MediaKind.VIDEO
                    else MediaKind.IMAGE,
                sizeBytes = c.getLong(size),
                dateTakenMillis = c.getLongOrNull(taken),
                dateModifiedSeconds = c.getLong(modified),
                width = c.getInt(width),
                height = c.getInt(height),
                orientation = c.getInt(orientation),
                durationMillis = c.getLong(duration),
                specialFormat = if (specialFormat >= 0) c.getInt(specialFormat) else 0,
                isFavorite = c.getInt(favorite) != 0,
                isTrashed = c.getInt(trashed) != 0,
                generationModified = c.getLong(generation),
                dateAddedSeconds = c.getLongOrNull(added) ?: 0,
            )
    }

    private companion object {
        val MEDIA_SELECTION =
            "${FileColumns.MEDIA_TYPE} IN " +
                "(${FileColumns.MEDIA_TYPE_IMAGE}, ${FileColumns.MEDIA_TYPE_VIDEO})"

        // The special-format column (motion photos, GIFs) exists from Android 17.
        val SPECIAL_FORMAT_COLUMN: String? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN)
                FileColumns.SPECIAL_FORMAT
            else null
    }
}

private fun Cursor.getStringOrNull(index: Int): String? =
    if (isNull(index)) null else getString(index)

private fun Cursor.getLongOrNull(index: Int): Long? = if (isNull(index)) null else getLong(index)
