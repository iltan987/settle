package com.iltan.settle.domain.backup

import com.iltan.settle.domain.MediaItemId
import com.iltan.settle.domain.scan.MediaKind
import java.io.Closeable

/** A file as Drive reports it. Checksums are lowercase hex; null while Drive has none yet. */
data class RemoteFile(
    val id: String,
    val sizeBytes: Long?,
    val md5: String?,
    val sha256: String?,
)

/** Metadata for a file that is about to be created in Drive. */
data class NewDriveFile(
    /** Pre-generated with [DriveApi.generateIds], so a retried create can't make a duplicate. */
    val id: String,
    val name: String,
    val mimeType: String,
    val parentId: String,
    val appProperties: Map<String, String>,
    val description: String,
)

/** What the server answered to one chunk of a resumable upload, or to a status query. */
sealed interface ChunkResult {
    /** The server holds the first [confirmedBytes] bytes; continue from there. */
    data class Incomplete(val confirmedBytes: Long) : ChunkResult

    data class Done(val file: RemoteFile) : ChunkResult
}

/** Why a Drive call failed, as far as the backup rules care. */
enum class DriveFailure {
    /** No usable token; the user has to connect Drive again. */
    AUTH_NEEDED,
    /** The user's storage is full. Retrying won't help. */
    QUOTA_FULL,
    /** Slow down and retry. */
    RATE_LIMITED,
    /** The resumable session expired or is unknown; start a new one. */
    SESSION_GONE,
    /** A file or folder with that pre-generated ID already exists. */
    CONFLICT,
    /** A referenced file, such as a cached parent folder, doesn't exist (any more). */
    NOT_FOUND,
    /** Network or server trouble; retry with backoff. */
    TRANSIENT,
    /** Anything else; retrying the same request won't help. */
    FATAL,
}

class DriveException(val failure: DriveFailure, message: String? = null, cause: Throwable? = null) :
    Exception(message ?: failure.name, cause)

/** The Drive v3 calls the backup rules need (ADR-010). Failures throw [DriveException]. */
interface DriveApi {
    suspend fun generateIds(count: Int): List<String>

    /** A folder this app created with that name under [parentId] (null: My Drive), if any. */
    suspend fun findFolder(name: String, parentId: String?): String?

    suspend fun createFolder(id: String, name: String, parentId: String?)

    /** Starts a resumable upload and returns its session URI. */
    suspend fun startUpload(file: NewDriveFile, sizeBytes: Long): String

    suspend fun uploadChunk(
        sessionUri: String,
        offset: Long,
        buffer: ByteArray,
        length: Int,
        totalBytes: Long,
    ): ChunkResult

    suspend fun uploadStatus(sessionUri: String, totalBytes: Long): ChunkResult

    /** The file with its size and checksums; null if it doesn't exist. */
    suspend fun getFile(id: String): RemoteFile?
}

/**
 * What identifies the bytes of a local original at one moment (ADR-004 condition 3). MediaStore's
 * generation is left out: it also moves on metadata changes such as a favourite, which don't touch
 * the bytes.
 */
data class FileSnapshot(val sizeBytes: Long, val dateModifiedSeconds: Long)

/** Thrown when the original can't be read, e.g. it was deleted or access was withdrawn. */
class OriginalUnavailableException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

/** Reads the original bytes of items on the phone. */
interface OriginalSource {
    /** The original's current snapshot, or null if it is no longer on the phone. */
    suspend fun snapshot(itemId: MediaItemId): FileSnapshot?

    /** Opens the unmodified original (location data included) for reading. */
    suspend fun open(itemId: MediaItemId): OriginalReader
}

interface OriginalReader : Closeable {
    /**
     * Reads up to [length] bytes from file [position] into [buffer] at [bufferOffset]; returns the
     * count, or -1 at the end of the file.
     */
    fun read(position: Long, buffer: ByteArray, bufferOffset: Int, length: Int): Int
}

/** What the backup rules need to know about an item to upload it. */
data class BackupItem(
    val id: MediaItemId,
    val kind: MediaKind,
    val displayName: String,
    val mimeType: String,
    /** Folder on the phone, e.g. `DCIM/Camera/`. */
    val relativePath: String,
    val takenAtMillis: Long,
    val width: Int,
    val height: Int,
    val specialFormat: Int,
)

/** Persistent upload state, so an upload survives the process being killed (ADR-004, R4). */
interface UploadStore {
    suspend fun item(itemId: MediaItemId): BackupItem?

    suspend fun record(itemId: MediaItemId): UploadRecord?

    /** Saves the record and the item's cloud state together. */
    suspend fun save(record: UploadRecord)

    suspend fun folderId(path: String): String?

    suspend fun saveFolderId(path: String, id: String)

    suspend fun forgetFolderIds()
}

fun interface Clock {
    fun nowMillis(): Long
}
