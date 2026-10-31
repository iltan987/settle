package com.iltan.settle.domain.backup

import com.iltan.settle.domain.CloudState
import com.iltan.settle.domain.MediaItemId
import java.security.MessageDigest
import java.time.ZoneId
import java.util.HexFormat
import kotlin.random.Random
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface BackupResult {
    data class Verified(val driveFileId: String) : BackupResult

    /** The bytes are in Drive, but Drive hasn't reported every checksum yet; ask again later. */
    data class AwaitingChecksum(val driveFileId: String) : BackupResult

    data class Stopped(val problem: UploadProblem) : BackupResult
}

/** Resumable chunks must be multiples of this, except the last one. */
const val CHUNK_GRANULARITY = 256 * 1024

const val DEFAULT_CHUNK_BYTES = 8 * 1024 * 1024

/** Drive keeps a resumable session for a week; start a new one a day before that. */
const val SESSION_MAX_AGE_MILLIS = 6L * 24 * 60 * 60 * 1000

/**
 * Uploads one original to Drive and verifies it (ADR-004, ADR-010).
 *
 * Every step is saved through [UploadStore], so calling [backUp] again after any failure or a
 * killed process continues where the server stopped:
 * - the Drive file ID is reserved first, so retries can't create a second file;
 * - the server's confirmed offset always decides where the next chunk starts;
 * - digests cover exactly the bytes the server confirmed, and are rebuilt from the phone after a
 *   restart instead of being stored;
 * - a copy is VERIFIED only when Drive's size, SHA-256 and MD5 equal the bytes sent and the
 *   original didn't change while uploading.
 *
 * One upload at a time per engine; engines running at once share a folder lock.
 */
class BackupEngine(
    private val drive: DriveApi,
    private val originals: OriginalSource,
    private val store: UploadStore,
    private val clock: Clock,
    /** `Settle`, or `Settle (debug)` for debug builds (ADR-008). */
    private val rootFolder: String,
    private val zone: ZoneId,
    private val chunkBytes: Int = DEFAULT_CHUNK_BYTES,
    private val maxRetries: Int = 6,
    /** How often a missing SHA-256 is asked for again before giving up for now. */
    private val checksumChecks: Int = 3,
    private val jitterMillis: () -> Long = { Random.nextLong(0, 1_001) },
    /** Told about every failure the engine handles, for diagnosis. */
    private val log: (String) -> Unit = {},
    /**
     * Shared by engines that upload at the same time, so two of them never both create the same
     * month's folder.
     */
    private val folderLock: Mutex = Mutex(),
) {
    init {
        require(chunkBytes > 0 && chunkBytes % CHUNK_GRANULARITY == 0) {
            "Chunks must be a positive multiple of 256 KiB"
        }
    }

    private val buffer by lazy { ByteArray(chunkBytes) }

    suspend fun backUp(
        itemId: MediaItemId,
        onProgress: (sentBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): BackupResult {
        var failures = 0
        var restarts = 0
        var progressMark = -1L
        var conflict = false
        while (true) {
            try {
                if (conflict) {
                    conflict = false
                    resolveConflict(itemId)?.let {
                        return it
                    }
                }
                return attempt(itemId, onProgress)
            } catch (e: DriveException) {
                log("$itemId: Drive ${e.failure}: ${e.message}")
                when (e.failure) {
                    DriveFailure.AUTH_NEEDED -> return stop(itemId, UploadProblem.AUTH_NEEDED)
                    DriveFailure.QUOTA_FULL -> return stop(itemId, UploadProblem.QUOTA_FULL)
                    DriveFailure.FATAL -> return stop(itemId, UploadProblem.FAILED)
                    DriveFailure.SESSION_GONE,
                    DriveFailure.NOT_FOUND,
                    DriveFailure.CONFLICT -> {
                        if (++restarts > maxRetries) return stop(itemId, UploadProblem.FAILED)
                        when (e.failure) {
                            DriveFailure.SESSION_GONE -> dropSession(itemId)
                            // A cached folder may have been removed in Drive.
                            DriveFailure.NOT_FOUND -> {
                                store.forgetFolderIds()
                                dropSession(itemId)
                            }
                            else -> conflict = true
                        }
                    }
                    DriveFailure.RATE_LIMITED,
                    DriveFailure.TRANSIENT -> {
                        // Failures only count while no bytes get through.
                        val confirmed = store.record(itemId)?.confirmedBytes ?: -1
                        if (confirmed > progressMark) {
                            progressMark = confirmed
                            failures = 0
                        }
                        if (++failures > maxRetries) return stop(itemId, UploadProblem.OFFLINE)
                        delay(backoffMillis(failures, jitterMillis()))
                    }
                }
            } catch (e: OriginalChangedException) {
                log("$itemId: ${e.message}")
                return stop(itemId, UploadProblem.CHANGED_DURING_UPLOAD)
            } catch (e: OriginalUnavailableException) {
                log("$itemId: ${e.message} (${e.cause})")
                return stop(itemId, UploadProblem.ORIGINAL_UNAVAILABLE)
            }
        }
    }

    private suspend fun attempt(
        itemId: MediaItemId,
        onProgress: (Long, Long) -> Unit,
    ): BackupResult {
        val item =
            store.item(itemId)
                ?: throw OriginalUnavailableException("$itemId is not in the library")
        val current =
            originals.snapshot(itemId)
                ?: throw OriginalUnavailableException("$itemId is no longer on the phone")
        if (current.sizeBytes <= 0) throw OriginalUnavailableException("$itemId is empty")

        val existing = store.record(itemId)
        var record =
            when {
                existing == null -> reserve(itemId, current)
                existing.snapshot != current || existing.state == CloudState.SUSPECT ->
                    // New bytes, or a copy that can't be trusted: a file Drive already created
                    // stays as it is and is remembered; the upload starts over under a new ID.
                    if (existing.fileExists) {
                        store.recordSuperseded(itemId, existing.driveFileId)
                        reserve(itemId, current)
                    } else
                        existing.copy(snapshot = current, problem = null).withoutSession().also {
                            store.save(it)
                        }
                // Trying again: the problem that stopped the last try no longer describes it.
                existing.problem != null -> existing.copy(problem = null).also { store.save(it) }
                else -> existing
            }
        when (record.state) {
            CloudState.VERIFIED -> return BackupResult.Verified(record.driveFileId)
            CloudState.UPLOADED -> return confirm(record, remote = null)
            else -> {}
        }

        val total = current.sizeBytes
        val sha256 = MessageDigest.getInstance("SHA-256")
        val md5 = MessageDigest.getInstance("MD5")
        var done: RemoteFile? = null
        originals.open(itemId).use { reader ->
            var offset: Long
            val session = record.sessionUri
            val startedAt = record.sessionStartedAtMillis
            if (
                session == null ||
                    startedAt == null ||
                    clock.nowMillis() - startedAt > SESSION_MAX_AGE_MILLIS
            ) {
                val file =
                    NewDriveFile(
                        id = record.driveFileId,
                        name = item.displayName,
                        mimeType = item.mimeType,
                        parentId = folderFor(item),
                        appProperties = appPropertiesFor(item),
                        description = descriptionFor(item),
                    )
                val uri = drive.startUpload(file, total)
                record =
                    record.copy(
                        state = CloudState.UPLOADING,
                        sessionUri = uri,
                        sessionStartedAtMillis = clock.nowMillis(),
                        confirmedBytes = 0,
                        problem = null,
                    )
                store.save(record)
                offset = 0
            } else {
                when (val status = drive.uploadStatus(session, total)) {
                    is ChunkResult.Incomplete -> offset = status.confirmedBytes
                    is ChunkResult.Done -> {
                        offset = total
                        done = status.file
                    }
                }
                // The digests of what the server already holds are rebuilt from the phone.
                digest(reader, 0, offset, sha256, md5)
            }
            onProgress(offset, total)

            while (done == null) {
                val length = minOf(chunkBytes.toLong(), total - offset).toInt()
                readFully(reader, offset, length)
                when (
                    val result =
                        drive.uploadChunk(record.sessionUri!!, offset, buffer, length, total)
                ) {
                    is ChunkResult.Incomplete -> {
                        val accepted = result.confirmedBytes - offset
                        // Fewer bytes than before means the digests are ahead of the server;
                        // the next attempt rebuilds them from its status.
                        if (accepted < 0 || accepted > length) {
                            throw DriveException(
                                DriveFailure.TRANSIENT,
                                "Server confirmed ${result.confirmedBytes} of $offset+$length",
                            )
                        }
                        sha256.update(buffer, 0, accepted.toInt())
                        md5.update(buffer, 0, accepted.toInt())
                        offset = result.confirmedBytes
                        record = record.copy(confirmedBytes = offset)
                        store.save(record)
                    }
                    is ChunkResult.Done -> {
                        sha256.update(buffer, 0, length)
                        md5.update(buffer, 0, length)
                        offset += length
                        done = result.file
                    }
                }
                onProgress(offset, total)
            }
        }

        record =
            record
                .copy(
                    state = CloudState.UPLOADED,
                    confirmedBytes = total,
                    sha256 = hex.formatHex(sha256.digest()),
                    md5 = hex.formatHex(md5.digest()),
                )
                .withoutSession()
        // ADR-004 condition 3: the bytes sent must be the bytes the phone still has.
        if (originals.snapshot(itemId) != record.snapshot) {
            store.save(
                record.copy(
                    state = CloudState.SUSPECT,
                    problem = UploadProblem.CHANGED_DURING_UPLOAD,
                )
            )
            return BackupResult.Stopped(UploadProblem.CHANGED_DURING_UPLOAD)
        }
        store.save(record)
        return confirm(record, done)
    }

    /**
     * Compares Drive's copy with the digests of the bytes sent; asks again while SHA-256 is due.
     */
    private suspend fun confirm(uploaded: UploadRecord, remote: RemoteFile?): BackupResult {
        val local =
            LocalDigest(
                uploaded.snapshot.sizeBytes,
                checkNotNull(uploaded.sha256),
                checkNotNull(uploaded.md5),
            )
        var file = remote ?: drive.getFile(uploaded.driveFileId)
        var checks = 0
        while (true) {
            if (file == null) {
                // Removed in Drive after the upload; the next backup starts over.
                store.save(
                    uploaded.copy(state = CloudState.SUSPECT, problem = UploadProblem.FAILED)
                )
                return BackupResult.Stopped(UploadProblem.FAILED)
            }
            when (verify(local, file)) {
                Verification.MATCH -> {
                    store.save(
                        uploaded.copy(
                            state = CloudState.VERIFIED,
                            verifiedAtMillis = clock.nowMillis(),
                            problem = null,
                        )
                    )
                    return BackupResult.Verified(uploaded.driveFileId)
                }
                Verification.MISMATCH -> {
                    store.save(
                        uploaded.copy(
                            state = CloudState.SUSPECT,
                            problem = UploadProblem.CHECKSUM_MISMATCH,
                        )
                    )
                    return BackupResult.Stopped(UploadProblem.CHECKSUM_MISMATCH)
                }
                Verification.PENDING -> {
                    if (++checks > checksumChecks) {
                        return BackupResult.AwaitingChecksum(uploaded.driveFileId)
                    }
                    delay(backoffMillis(checks, jitterMillis()))
                    file = drive.getFile(uploaded.driveFileId)
                }
            }
        }
    }

    /**
     * Drive already has a file with the reserved ID: an earlier attempt finished without hearing
     * back. If it holds these bytes it is the backup; otherwise a new ID is reserved and the upload
     * starts again (the other file is left alone). Returns null to retry.
     */
    private suspend fun resolveConflict(itemId: MediaItemId): BackupResult? {
        val record = store.record(itemId) ?: return null
        val remote = drive.getFile(record.driveFileId)
        if (remote != null) {
            val local = originals.open(itemId).use { reader -> digestAll(reader, record.snapshot) }
            if (verify(local, remote) != Verification.MISMATCH) {
                val uploaded =
                    record
                        .copy(
                            state = CloudState.UPLOADED,
                            confirmedBytes = local.sizeBytes,
                            sha256 = local.sha256,
                            md5 = local.md5,
                        )
                        .withoutSession()
                store.save(uploaded)
                return confirm(uploaded, remote)
            }
            // A file with other bytes holds the reserved ID; it stays, and is remembered.
            store.recordSuperseded(itemId, record.driveFileId)
        }
        reserve(itemId, record.snapshot)
        return null
    }

    private suspend fun reserve(itemId: MediaItemId, snapshot: FileSnapshot): UploadRecord {
        val record =
            UploadRecord(
                itemId = itemId,
                driveFileId = drive.generateIds(1).single(),
                state = CloudState.RESERVED,
                snapshot = snapshot,
            )
        store.save(record)
        return record
    }

    private suspend fun dropSession(itemId: MediaItemId) {
        val record = store.record(itemId) ?: return
        if (!record.fileExists) store.save(record.withoutSession())
    }

    private suspend fun stop(itemId: MediaItemId, problem: UploadProblem): BackupResult {
        store.record(itemId)?.let { store.save(it.copy(problem = problem)) }
        return BackupResult.Stopped(problem)
    }

    /** The Drive folder for the item, found or created once and then cached. */
    private suspend fun folderFor(item: BackupItem): String = folderLock.withLock {
        var parent: String? = null
        var path = ""
        for (name in mediaFolderPath(rootFolder, item.takenAtMillis, zone)) {
            path = if (path.isEmpty()) name else "$path/$name"
            val id =
                store.folderId(path)
                    ?: (drive.findFolder(name, parent) ?: createFolder(name, parent)).also {
                        store.saveFolderId(path, it)
                    }
            parent = id
        }
        checkNotNull(parent)
    }

    private suspend fun createFolder(name: String, parent: String?): String {
        val id = drive.generateIds(1).single()
        try {
            drive.createFolder(id, name, parent)
        } catch (e: DriveException) {
            // Created by an earlier try whose answer was lost.
            if (e.failure != DriveFailure.CONFLICT) throw e
        }
        return id
    }

    private fun readFully(reader: OriginalReader, offset: Long, length: Int) {
        var read = 0
        while (read < length) {
            val count = reader.read(offset + read, buffer, read, length - read)
            if (count < 0) throw OriginalChangedException()
            read += count
        }
    }

    private fun digest(
        reader: OriginalReader,
        from: Long,
        to: Long,
        vararg digests: MessageDigest,
    ) {
        var offset = from
        while (offset < to) {
            val length = minOf(chunkBytes.toLong(), to - offset).toInt()
            readFully(reader, offset, length)
            digests.forEach { it.update(buffer, 0, length) }
            offset += length
        }
    }

    private fun digestAll(reader: OriginalReader, snapshot: FileSnapshot): LocalDigest {
        val sha256 = MessageDigest.getInstance("SHA-256")
        val md5 = MessageDigest.getInstance("MD5")
        digest(reader, 0, snapshot.sizeBytes, sha256, md5)
        return LocalDigest(
            snapshot.sizeBytes,
            hex.formatHex(sha256.digest()),
            hex.formatHex(md5.digest()),
        )
    }

    private fun UploadRecord.withoutSession() =
        copy(sessionUri = null, sessionStartedAtMillis = null)

    private class OriginalChangedException : Exception("The original got shorter while uploading")

    private companion object {
        val hex: HexFormat = HexFormat.of()
    }
}
