package com.iltan.settle.domain.deletion

import com.iltan.settle.domain.CloudState
import com.iltan.settle.domain.MediaItemId
import com.iltan.settle.domain.backup.BackupItem
import com.iltan.settle.domain.backup.DEFAULT_CHUNK_BYTES
import com.iltan.settle.domain.backup.DriveApi
import com.iltan.settle.domain.backup.DriveException
import com.iltan.settle.domain.backup.DriveFailure
import com.iltan.settle.domain.backup.FileSnapshot
import com.iltan.settle.domain.backup.UploadRecord
import com.iltan.settle.domain.backup.UploadStore
import com.iltan.settle.domain.backup.backoffMillis
import java.io.Closeable
import java.io.IOException
import java.security.MessageDigest
import java.util.HexFormat
import kotlin.random.Random
import kotlinx.coroutines.delay

/** Where a restored original is written: a pending media item, hidden until it is verified. */
interface RestoreTarget {
    /** Creates an empty pending file for [item] in its original folder and returns its handle. */
    suspend fun create(item: BackupItem): String

    /** The pending file, or null once it is gone (the system drops pending files after a week). */
    suspend fun open(handle: String): PendingFile?

    /** Makes the pending file visible to every app. */
    suspend fun publish(handle: String): PublishedFile

    suspend fun discard(handle: String)
}

interface PendingFile : Closeable {
    val sizeBytes: Long

    /** Reads what was written so far; returns the count, or -1 at the end. */
    fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int

    /** Writes at the end of the file. Throws [IOException] when the phone can't take it. */
    fun append(buffer: ByteArray, offset: Int, length: Int)
}

/** A restored original, now a normal file on the phone. */
data class PublishedFile(val mediaStoreId: Long, val snapshot: FileSnapshot)

/** Remembers each unfinished download's pending file, so a killed download resumes. */
interface DownloadStore {
    suspend fun pendingDownload(itemId: MediaItemId): String?

    suspend fun savePendingDownload(itemId: MediaItemId, handle: String)

    suspend fun clearPendingDownload(itemId: MediaItemId)
}

enum class RestoreProblem {
    AUTH_NEEDED,
    /** Network or Drive kept failing; trying again continues where it stopped. */
    OFFLINE,
    /** No verified copy in Drive, or Drive no longer has it. */
    NOT_IN_DRIVE,
    /** The downloaded bytes differ from what was backed up; nothing was published. */
    CHECKSUM_MISMATCH,
    /** The phone couldn't store the file, e.g. it is full. */
    NOT_WRITABLE,
    FAILED,
}

sealed interface RestoreResult {
    data class Restored(val file: PublishedFile) : RestoreResult

    data class Stopped(val problem: RestoreProblem) : RestoreResult
}

/**
 * Downloads an item's verified Drive copy back onto the phone (ADR-015):
 * - into a pending media item that no other app sees, in the original's folder;
 * - resuming a killed download from the bytes already written, whose digests are rebuilt by reading
 *   them back;
 * - published only when size, SHA-256 and MD5 equal the upload record, so a restored file is always
 *   the file that was backed up. The record then takes the new file's snapshot: the bytes are
 *   proven equal, so no new upload follows.
 */
class RestoreDownload(
    private val drive: DriveApi,
    private val target: RestoreTarget,
    private val uploads: UploadStore,
    private val downloads: DownloadStore,
    private val bufferBytes: Int = DEFAULT_CHUNK_BYTES,
    private val maxRetries: Int = 6,
    private val jitterMillis: () -> Long = { Random.nextLong(0, 1_001) },
    private val log: (String) -> Unit = {},
) {
    private val buffer by lazy { ByteArray(bufferBytes) }

    suspend fun restore(
        itemId: MediaItemId,
        onProgress: (receivedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): RestoreResult {
        val record = uploads.record(itemId)
        val item = uploads.item(itemId)
        if (
            record == null ||
                item == null ||
                record.state != CloudState.VERIFIED ||
                record.sha256 == null ||
                record.md5 == null
        ) {
            return RestoreResult.Stopped(RestoreProblem.NOT_IN_DRIVE)
        }
        var failures = 0
        var progressMark = -1L
        while (true) {
            try {
                return attempt(record, item, onProgress)
            } catch (e: DriveException) {
                log("$itemId: Drive ${e.failure}: ${e.message}")
                when (e.failure) {
                    DriveFailure.NOT_FOUND -> {
                        discardPending(itemId)
                        return RestoreResult.Stopped(RestoreProblem.NOT_IN_DRIVE)
                    }
                    DriveFailure.AUTH_NEEDED ->
                        return RestoreResult.Stopped(RestoreProblem.AUTH_NEEDED)
                    DriveFailure.RATE_LIMITED,
                    DriveFailure.TRANSIENT -> {
                        val written = writtenBytes(itemId)
                        if (written > progressMark) {
                            progressMark = written
                            failures = 0
                        }
                        if (++failures > maxRetries) {
                            return RestoreResult.Stopped(RestoreProblem.OFFLINE)
                        }
                        delay(backoffMillis(failures, jitterMillis()))
                    }
                    else -> return RestoreResult.Stopped(RestoreProblem.FAILED)
                }
            } catch (e: IOException) {
                log("$itemId: writing failed: $e")
                return RestoreResult.Stopped(RestoreProblem.NOT_WRITABLE)
            }
        }
    }

    private suspend fun attempt(
        record: UploadRecord,
        item: BackupItem,
        onProgress: (Long, Long) -> Unit,
    ): RestoreResult {
        val itemId = record.itemId
        val total = record.snapshot.sizeBytes
        val (handle, file) = pendingFile(item, total)
        val sha256 = MessageDigest.getInstance("SHA-256")
        val md5 = MessageDigest.getInstance("MD5")
        file.use {
            var have = it.sizeBytes
            // What an earlier try wrote is hashed again from the phone, as uploads do.
            digest(it, have, sha256, md5)
            onProgress(have, total)
            if (have < total) {
                drive.download(record.driveFileId, have).use { stream ->
                    while (have < total) {
                        val count =
                            stream.read(
                                buffer,
                                0,
                                minOf(bufferBytes.toLong(), total - have).toInt(),
                            )
                        if (count < 0) break
                        it.append(buffer, 0, count)
                        sha256.update(buffer, 0, count)
                        md5.update(buffer, 0, count)
                        have += count
                        onProgress(have, total)
                    }
                    // Drive holding more bytes than were backed up is a different file.
                    if (have == total && stream.read(buffer, 0, 1) > 0) {
                        return mismatch(itemId, handle)
                    }
                }
            }
            if (have < total) {
                throw DriveException(DriveFailure.TRANSIENT, "Download ended at $have of $total")
            }
        }
        if (
            hex.formatHex(sha256.digest()) != record.sha256 ||
                hex.formatHex(md5.digest()) != record.md5
        ) {
            return mismatch(itemId, handle)
        }
        val published = target.publish(handle)
        downloads.clearPendingDownload(itemId)
        uploads.save(record.copy(snapshot = published.snapshot))
        return RestoreResult.Restored(published)
    }

    /** The unfinished download's file if it is still there and not too long, else a new one. */
    private suspend fun pendingFile(item: BackupItem, total: Long): Pair<String, PendingFile> {
        downloads.pendingDownload(item.id)?.let { handle ->
            val file = target.open(handle)
            if (file != null && file.sizeBytes <= total) return handle to file
            file?.close()
            target.discard(handle)
        }
        val handle = target.create(item)
        downloads.savePendingDownload(item.id, handle)
        val file = target.open(handle) ?: throw IOException("The new pending file is gone")
        return handle to file
    }

    private suspend fun mismatch(itemId: MediaItemId, handle: String): RestoreResult {
        log("$itemId: downloaded bytes don't match the backup")
        target.discard(handle)
        downloads.clearPendingDownload(itemId)
        return RestoreResult.Stopped(RestoreProblem.CHECKSUM_MISMATCH)
    }

    private suspend fun discardPending(itemId: MediaItemId) {
        downloads.pendingDownload(itemId)?.let { target.discard(it) }
        downloads.clearPendingDownload(itemId)
    }

    private suspend fun writtenBytes(itemId: MediaItemId): Long =
        downloads.pendingDownload(itemId)?.let { handle ->
            target.open(handle)?.use { it.sizeBytes }
        } ?: 0

    private fun digest(file: PendingFile, length: Long, vararg digests: MessageDigest) {
        var position = 0L
        while (position < length) {
            val count =
                file.read(
                    position,
                    buffer,
                    0,
                    minOf(bufferBytes.toLong(), length - position).toInt(),
                )
            if (count < 0) throw IOException("The pending file got shorter")
            digests.forEach { it.update(buffer, 0, count) }
            position += count
        }
    }

    private companion object {
        val hex: HexFormat = HexFormat.of()
    }
}
