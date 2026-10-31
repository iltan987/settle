package com.iltan.settle.data.drive

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import com.iltan.settle.data.db.BackupDao
import com.iltan.settle.data.mediaContentUri
import com.iltan.settle.domain.MediaItemId
import com.iltan.settle.domain.backup.FileSnapshot
import com.iltan.settle.domain.backup.OriginalReader
import com.iltan.settle.domain.backup.OriginalSource
import com.iltan.settle.domain.backup.OriginalUnavailableException
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads originals through MediaStore for upload. Without `ACCESS_MEDIA_LOCATION` MediaStore hands
 * out a copy with the location removed, which would break the byte-for-byte rule (R1), so this
 * refuses to open anything then.
 */
class MediaStoreOriginals(private val context: Context, private val dao: BackupDao) :
    OriginalSource {

    private val resolver = context.contentResolver

    fun canReadOriginals(): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    override suspend fun snapshot(itemId: MediaItemId): FileSnapshot? =
        withContext(Dispatchers.IO) {
            val uri = uri(itemId) ?: return@withContext null
            resolver
                .query(
                    uri,
                    arrayOf(MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED),
                    null,
                    null,
                )
                ?.use { if (it.moveToFirst()) FileSnapshot(it.getLong(0), it.getLong(1)) else null }
        }

    override suspend fun open(itemId: MediaItemId): OriginalReader =
        withContext(Dispatchers.IO) {
            if (!canReadOriginals()) {
                throw OriginalUnavailableException("No access to unredacted originals")
            }
            val uri =
                uri(itemId) ?: throw OriginalUnavailableException("$itemId is not on the phone")
            val file =
                try {
                    resolver.openFileDescriptor(MediaStore.setRequireOriginal(uri), "r")
                } catch (e: Exception) {
                    throw OriginalUnavailableException("Can't open $uri", e)
                } ?: throw OriginalUnavailableException("Can't open $uri")
            val channel = FileInputStream(file.fileDescriptor).channel
            object : OriginalReader {
                // Positional reads straight into the upload buffer.
                override fun read(
                    position: Long,
                    buffer: ByteArray,
                    bufferOffset: Int,
                    length: Int,
                ): Int =
                    try {
                        channel.read(ByteBuffer.wrap(buffer, bufferOffset, length), position)
                    } catch (e: IOException) {
                        throw OriginalUnavailableException("Reading $uri failed", e)
                    }

                override fun close() {
                    channel.close()
                    file.close()
                }
            }
        }

    private suspend fun uri(itemId: MediaItemId): Uri? =
        dao.localFile(itemId.value)?.let { mediaContentUri(it.kind, it.volume, it.mediaStoreId) }
}
