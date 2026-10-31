package com.iltan.settle.data.trash

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.system.ErrnoException
import android.system.Os
import androidx.core.net.toUri
import com.iltan.settle.data.mediaContentUri
import com.iltan.settle.domain.backup.BackupItem
import com.iltan.settle.domain.backup.FileSnapshot
import com.iltan.settle.domain.deletion.PendingFile
import com.iltan.settle.domain.deletion.PublishedFile
import com.iltan.settle.domain.deletion.RestoreTarget
import com.iltan.settle.domain.scan.MediaKind
import java.io.FileNotFoundException
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A phone file by its MediaStore row. */
data class PhoneFile(
    val itemId: String,
    val kind: MediaKind,
    val volume: String,
    val mediaStoreId: Long,
) {
    val uri: Uri
        get() = mediaContentUri(kind, volume, mediaStoreId)
}

/** MediaStore's trash and delete requests, as [Deletion] needs them. */
interface PhoneTrash {
    /** True when "Media management" access lets requests run without a prompt. */
    fun canDeleteWithoutAsking(): Boolean

    /**
     * Requests to move [files] into the system trash, or out of it, in batches Android accepts.
     * Each must be started for its result; the system asks the user unless
     * [canDeleteWithoutAsking].
     */
    fun trashRequests(
        files: List<PhoneFile>,
        trashed: Boolean,
    ): List<Pair<List<PhoneFile>, IntentSender>>

    /** Requests to delete [files] for good, in batches Android accepts. */
    fun deleteRequests(files: List<PhoneFile>): List<Pair<List<PhoneFile>, IntentSender>>
}

/**
 * The phone side of deleting and restoring (ADR-015): MediaStore's trash and delete requests, and
 * pending files for restored originals.
 */
class MediaStoreTrash(private val context: Context) : PhoneTrash, RestoreTarget {

    private val resolver = context.contentResolver

    override fun canDeleteWithoutAsking(): Boolean = MediaStore.canManageMedia(context)

    override fun trashRequests(
        files: List<PhoneFile>,
        trashed: Boolean,
    ): List<Pair<List<PhoneFile>, IntentSender>> =
        files.chunked(MAX_URIS_PER_REQUEST).map { batch ->
            batch to
                MediaStore.createTrashRequest(resolver, batch.map { it.uri }, trashed).intentSender
        }

    override fun deleteRequests(files: List<PhoneFile>): List<Pair<List<PhoneFile>, IntentSender>> =
        files.chunked(MAX_URIS_PER_REQUEST).map { batch ->
            batch to MediaStore.createDeleteRequest(resolver, batch.map { it.uri }).intentSender
        }

    // RestoreTarget: a pending item that no other app sees until it is published.

    override suspend fun create(item: BackupItem): String =
        withContext(Dispatchers.IO) {
            val collection =
                when (item.kind) {
                    MediaKind.IMAGE -> MediaStore.Images.Media.getContentUri(VOLUME)
                    MediaKind.VIDEO -> MediaStore.Video.Media.getContentUri(VOLUME)
                }
            fun insert(relativePath: String): Uri? =
                resolver.insert(
                    collection,
                    ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, item.displayName)
                        put(MediaStore.MediaColumns.MIME_TYPE, item.mimeType)
                        put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                        put(MediaStore.MediaColumns.DATE_TAKEN, item.takenAtMillis)
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    },
                )
            val uri =
                try {
                    insert(item.relativePath)
                } catch (e: IllegalArgumentException) {
                    // A folder this media type may not live in, e.g. a photo in Download/.
                    insert(fallbackFolder(item.kind))
                } ?: throw IOException("MediaStore refused a pending file for ${item.id}")
            uri.toString()
        }

    override suspend fun open(handle: String): PendingFile? =
        withContext(Dispatchers.IO) {
            val descriptor =
                try {
                    resolver.openFileDescriptor(handle.toUri(), "rw")
                } catch (e: FileNotFoundException) {
                    null
                } catch (e: IllegalArgumentException) {
                    null
                } catch (e: IllegalStateException) {
                    null
                } ?: return@withContext null
            DescriptorFile(descriptor)
        }

    override suspend fun publish(handle: String): PublishedFile =
        withContext(Dispatchers.IO) {
            val uri = handle.toUri()
            val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            if (resolver.update(uri, values, null, null) != 1) {
                throw IOException("Couldn't publish $uri")
            }
            val snapshot =
                resolver
                    .query(
                        uri,
                        arrayOf(
                            MediaStore.MediaColumns.SIZE,
                            MediaStore.MediaColumns.DATE_MODIFIED,
                        ),
                        null,
                        null,
                    )
                    ?.use {
                        if (it.moveToFirst()) FileSnapshot(it.getLong(0), it.getLong(1)) else null
                    } ?: throw IOException("The published file $uri is gone")
            PublishedFile(ContentUris.parseId(uri), snapshot)
        }

    /** Removes Settle's own pending file of an unfinished or mismatched download. */
    override suspend fun discard(handle: String) {
        withContext(Dispatchers.IO) {
            try {
                resolver.delete(handle.toUri(), null, null)
            } catch (e: Exception) {
                // Already gone: the system drops pending files after a week.
            }
        }
    }

    /** Positional reads and appends on a pending file's descriptor. */
    private class DescriptorFile(private val descriptor: ParcelFileDescriptor) : PendingFile {
        private val fd = descriptor.fileDescriptor

        override val sizeBytes: Long
            get() = io { Os.fstat(fd).st_size }

        override fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int = io {
            val count = Os.pread(fd, buffer, offset, length, position)
            if (count == 0 && length > 0) -1 else count
        }

        override fun append(buffer: ByteArray, offset: Int, length: Int) = io {
            var position = Os.fstat(fd).st_size
            var written = 0
            while (written < length) {
                val count = Os.pwrite(fd, buffer, offset + written, length - written, position)
                written += count
                position += count
            }
        }

        override fun close() = descriptor.close()

        private inline fun <T> io(block: () -> T): T =
            try {
                block()
            } catch (e: ErrnoException) {
                throw IOException(e)
            }
    }

    companion object {
        /** Apps targeting API 36 or higher may send at most this many URIs per request. */
        const val MAX_URIS_PER_REQUEST = 2000
        val VOLUME: String = MediaStore.VOLUME_EXTERNAL_PRIMARY

        private fun fallbackFolder(kind: MediaKind): String =
            when (kind) {
                MediaKind.IMAGE -> Environment.DIRECTORY_PICTURES
                MediaKind.VIDEO -> Environment.DIRECTORY_MOVIES
            } + "/Settle/"
    }
}
