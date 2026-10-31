package com.iltan.settle.domain.deletion

import com.iltan.settle.domain.MediaItemId
import com.iltan.settle.domain.backup.BackupItem
import com.iltan.settle.domain.backup.FileSnapshot
import java.io.ByteArrayOutputStream
import java.io.IOException

/** The Drive change queue in memory, keyed by Drive file ID like the real one. */
class FakeDriveOpStore : DriveOpStore {
    val ops = linkedMapOf<String, DriveOp>()

    fun enqueue(vararg queued: DriveOp) = queued.forEach { ops[it.driveFileId] = it }

    override suspend fun due(nowMillis: Long) =
        ops.values.filter { it.notBeforeMillis <= nowMillis }.sortedBy { it.notBeforeMillis }

    override suspend fun done(op: DriveOp) {
        if (ops[op.driveFileId] == op) ops.remove(op.driveFileId)
    }

    override suspend fun retry(op: DriveOp, notBeforeMillis: Long) {
        if (ops[op.driveFileId] == op) {
            ops[op.driveFileId] =
                op.copy(notBeforeMillis = notBeforeMillis, attempts = op.attempts + 1)
        }
    }

    override suspend fun nextDueAt() = ops.values.minOfOrNull { it.notBeforeMillis }
}

/** Pending and published files on a fake phone. */
class FakeRestoreTarget : RestoreTarget {
    class Pending(val item: BackupItem) {
        val bytes = ByteArrayOutputStream()
    }

    val pending = mutableMapOf<String, Pending>()
    /** Published files by MediaStore ID. */
    val published = mutableMapOf<Long, Pair<BackupItem, ByteArray>>()
    val discarded = mutableListOf<String>()
    /** The phone is full after this many bytes. */
    var spaceLeft: Long = Long.MAX_VALUE
    var publishedModifiedSeconds = 99L
    private var nextHandle = 0
    private var nextMediaStoreId = 1000L

    override suspend fun create(item: BackupItem): String {
        val handle = "pending${++nextHandle}"
        pending[handle] = Pending(item)
        return handle
    }

    override suspend fun open(handle: String): PendingFile? {
        val file = pending[handle] ?: return null
        return object : PendingFile {
            override val sizeBytes: Long
                get() = file.bytes.size().toLong()

            override fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
                val content = file.bytes.toByteArray()
                if (position >= content.size) return -1
                val count = minOf(length.toLong(), content.size - position).toInt()
                content.copyInto(buffer, offset, position.toInt(), position.toInt() + count)
                return count
            }

            override fun append(buffer: ByteArray, offset: Int, length: Int) {
                if (spaceLeft < length) throw IOException("No space left")
                spaceLeft -= length
                file.bytes.write(buffer, offset, length)
            }

            override fun close() {}
        }
    }

    override suspend fun publish(handle: String): PublishedFile {
        val file = checkNotNull(pending.remove(handle))
        val bytes = file.bytes.toByteArray()
        val id = nextMediaStoreId++
        published[id] = file.item to bytes
        return PublishedFile(id, FileSnapshot(bytes.size.toLong(), publishedModifiedSeconds))
    }

    override suspend fun discard(handle: String) {
        pending.remove(handle)
        discarded += handle
    }
}

class FakeDownloadStore : DownloadStore {
    val handles = mutableMapOf<MediaItemId, String>()

    override suspend fun pendingDownload(itemId: MediaItemId) = handles[itemId]

    override suspend fun savePendingDownload(itemId: MediaItemId, handle: String) {
        handles[itemId] = handle
    }

    override suspend fun clearPendingDownload(itemId: MediaItemId) {
        handles.remove(itemId)
    }
}
