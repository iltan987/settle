package com.iltan.settle.domain.backup

import com.iltan.settle.domain.MediaItemId
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.HexFormat
import kotlinx.coroutines.yield

/**
 * An in-memory Drive that behaves like the resumable upload protocol and can be told to fail. It
 * checks that every chunk starts where the server stopped, as the real server requires.
 */
class FakeDrive : DriveApi {
    class StoredFile(
        val id: String,
        val name: String,
        val parentId: String?,
        val isFolder: Boolean,
        val bytes: ByteArray = ByteArray(0),
        val appProperties: Map<String, String> = emptyMap(),
        val description: String = "",
    )

    private class Session(val file: NewDriveFile, val total: Long) {
        val received = ByteArrayOutputStream()
        var expired = false
        var completed = false
    }

    val files = linkedMapOf<String, StoredFile>()
    /** IDs of files in Drive's trash. */
    val trashed = mutableSetOf<String>()
    private val sessions = mutableMapOf<String, Session>()
    private var nextId = 0
    private var nextSession = 0
    private val faults = mutableListOf<Pair<String, DriveFailure>>()
    private val lostAnswers = mutableListOf<String>()

    /** Bytes that arrived in chunk requests, accepted or not. */
    var bytesSent = 0L
    var startCalls = 0
    var chunkCalls = 0

    /** The server keeps only this many bytes of the next chunk. */
    var keepOfNextChunk: Int? = null
    /** How many more times a file is reported without its SHA-256. */
    var sha256Delays = 0
    /** Store different bytes than were sent, as a broken upload would. */
    var corruptStoredBytes = false
    /** Runs before a chunk is accepted; throw from it to simulate the app being killed. */
    var beforeChunk: (offset: Long) -> Unit = {}
    /** Runs after a chunk is stored, before the answer is sent. */
    var afterChunk: (offset: Long) -> Unit = {}
    /** Every call suspends first, so uploads running at once interleave as on a real network. */
    var interleave = false

    val mediaFiles: List<StoredFile>
        get() = files.values.filter { !it.isFolder }

    val folders: List<StoredFile>
        get() = files.values.filter { it.isFolder }

    fun failNext(call: String, failure: DriveFailure, times: Int = 1) =
        repeat(times) { faults += call to failure }

    /** The next [call] succeeds on the server, but its answer never reaches the app. */
    fun loseNextAnswer(call: String) {
        lostAnswers += call
    }

    fun expireSessions() = sessions.values.forEach { it.expired = true }

    private suspend fun fault(call: String) {
        if (interleave) yield()
        val index = faults.indexOfFirst { it.first == call }
        if (index >= 0) throw DriveException(faults.removeAt(index).second)
    }

    private fun answer(call: String) {
        if (lostAnswers.remove(call)) throw DriveException(DriveFailure.TRANSIENT, "answer lost")
    }

    override suspend fun generateIds(count: Int): List<String> {
        fault("generateIds")
        return List(count) { "id${++nextId}" }
    }

    override suspend fun findFolder(name: String, parentId: String?): String? {
        fault("findFolder")
        return folders.firstOrNull { it.name == name && it.parentId == parentId }?.id
    }

    override suspend fun createFolder(id: String, name: String, parentId: String?) {
        fault("createFolder")
        if (id in files) throw DriveException(DriveFailure.CONFLICT)
        files[id] = StoredFile(id, name, parentId, isFolder = true)
        answer("createFolder")
    }

    override suspend fun startUpload(file: NewDriveFile, sizeBytes: Long): String {
        fault("startUpload")
        startCalls++
        if (file.id in files) throw DriveException(DriveFailure.CONFLICT)
        if (files[file.parentId]?.isFolder != true) throw DriveException(DriveFailure.NOT_FOUND)
        val uri = "session${++nextSession}"
        sessions[uri] = Session(file, sizeBytes)
        answer("startUpload")
        return uri
    }

    override suspend fun uploadChunk(
        sessionUri: String,
        offset: Long,
        buffer: ByteArray,
        length: Int,
        totalBytes: Long,
        onSent: () -> Unit,
    ): ChunkResult {
        fault("uploadChunk")
        val session = live(sessionUri)
        check(offset == session.received.size().toLong()) {
            "Chunk starts at $offset but the server holds ${session.received.size()} bytes"
        }
        check(totalBytes == session.total)
        beforeChunk(offset)
        chunkCalls++
        bytesSent += length
        onSent()
        val keep = keepOfNextChunk?.also { keepOfNextChunk = null }?.coerceAtMost(length) ?: length
        session.received.write(buffer, 0, keep)
        if (session.received.size().toLong() < session.total) {
            afterChunk(offset)
            answer("uploadChunk")
            return ChunkResult.Incomplete(session.received.size().toLong())
        }
        complete(session)
        afterChunk(offset)
        answer("uploadChunk")
        return ChunkResult.Done(remote(files.getValue(session.file.id)))
    }

    override suspend fun uploadStatus(sessionUri: String, totalBytes: Long): ChunkResult {
        fault("uploadStatus")
        val session = live(sessionUri)
        return if (session.completed) ChunkResult.Done(remote(files.getValue(session.file.id)))
        else ChunkResult.Incomplete(session.received.size().toLong())
    }

    override suspend fun getFile(id: String): RemoteFile? {
        fault("getFile")
        return files[id]?.let(::remote)
    }

    override suspend fun setTrashed(id: String, trashed: Boolean) {
        fault("setTrashed")
        if (id !in files) throw DriveException(DriveFailure.NOT_FOUND)
        if (trashed) this.trashed += id else this.trashed -= id
        answer("setTrashed")
    }

    override suspend fun delete(id: String) {
        fault("delete")
        if (files.remove(id) == null) throw DriveException(DriveFailure.NOT_FOUND)
        trashed -= id
        answer("delete")
    }

    /** Downloads end after this many bytes, as a dropped connection would. */
    var cutDownloadAfter: Int? = null
    var downloadCalls = 0
    val downloadOffsets = mutableListOf<Long>()

    override suspend fun download(id: String, fromByte: Long): DownloadStream {
        fault("download")
        val bytes = files[id]?.bytes ?: throw DriveException(DriveFailure.NOT_FOUND)
        downloadCalls++
        downloadOffsets += fromByte
        val cut = cutDownloadAfter?.also { cutDownloadAfter = null }
        return object : DownloadStream {
            var position = fromByte.toInt()
            var sent = 0

            override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (cut != null && sent >= cut) {
                    throw DriveException(DriveFailure.TRANSIENT, "connection dropped")
                }
                if (position >= bytes.size) return -1
                val limit = if (cut != null) minOf(length, cut - sent) else length
                val count = minOf(limit, bytes.size - position)
                bytes.copyInto(buffer, offset, position, position + count)
                position += count
                sent += count
                return count
            }

            override fun close() {}
        }
    }

    private fun live(uri: String): Session {
        val session = sessions[uri]
        if (session == null || session.expired) throw DriveException(DriveFailure.SESSION_GONE)
        return session
    }

    private fun complete(session: Session) {
        if (session.file.id in files) throw DriveException(DriveFailure.CONFLICT)
        val bytes = session.received.toByteArray()
        if (corruptStoredBytes) bytes[0] = (bytes[0] + 1).toByte()
        session.completed = true
        files[session.file.id] =
            StoredFile(
                session.file.id,
                session.file.name,
                session.file.parentId,
                isFolder = false,
                bytes,
                session.file.appProperties,
                session.file.description,
            )
    }

    private fun remote(file: StoredFile): RemoteFile {
        val sha256 =
            if (sha256Delays > 0) {
                sha256Delays--
                null
            } else sha256(file.bytes)
        return RemoteFile(file.id, file.bytes.size.toLong(), md5(file.bytes), sha256)
    }

    /** The folder path of a stored file, e.g. `Settle/media/2026/10`. */
    fun pathOf(file: StoredFile): String {
        val names = mutableListOf<String>()
        var parent = file.parentId
        while (parent != null) {
            val folder = files.getValue(parent)
            names += folder.name
            parent = folder.parentId
        }
        return names.reversed().joinToString("/")
    }
}

/** Originals on a fake phone. */
class FakeOriginals : OriginalSource {
    val bytes = mutableMapOf<MediaItemId, ByteArray>()
    val modifiedSeconds = mutableMapOf<MediaItemId, Long>()
    var bytesRead = 0L

    override suspend fun snapshot(itemId: MediaItemId): FileSnapshot? =
        bytes[itemId]?.let { FileSnapshot(it.size.toLong(), modifiedSeconds[itemId] ?: 1) }

    override suspend fun open(itemId: MediaItemId): OriginalReader {
        if (itemId !in bytes) throw OriginalUnavailableException("gone")
        return object : OriginalReader {
            // Like a file descriptor, reads see the file as it is now.
            override fun read(
                position: Long,
                buffer: ByteArray,
                bufferOffset: Int,
                length: Int,
            ): Int {
                val content = bytes[itemId] ?: return -1
                if (position >= content.size) return -1
                val count = minOf(length.toLong(), content.size - position).toInt()
                content.copyInto(buffer, bufferOffset, position.toInt(), position.toInt() + count)
                bytesRead += count
                return count
            }

            override fun close() {}
        }
    }
}

class FakeUploadStore : UploadStore {
    val items = mutableMapOf<MediaItemId, BackupItem>()
    val records = mutableMapOf<MediaItemId, UploadRecord>()
    val folderIds = mutableMapOf<String, String>()
    /** Replaced Drive files, by Drive file ID. */
    val superseded = mutableMapOf<String, MediaItemId>()

    override suspend fun item(itemId: MediaItemId) = items[itemId]

    override suspend fun recordSuperseded(itemId: MediaItemId, driveFileId: String) {
        superseded[driveFileId] = itemId
    }

    override suspend fun record(itemId: MediaItemId) = records[itemId]

    override suspend fun save(record: UploadRecord) {
        records[record.itemId] = record
    }

    override suspend fun folderId(path: String) = folderIds[path]

    override suspend fun saveFolderId(path: String, id: String) {
        folderIds[path] = id
    }

    override suspend fun forgetFolderIds() = folderIds.clear()
}

fun sha256(bytes: ByteArray): String =
    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

fun md5(bytes: ByteArray): String =
    HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(bytes))
