package com.iltan.settle.data

import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore
import com.iltan.settle.data.db.LibraryDao
import com.iltan.settle.data.db.MediaEntry
import com.iltan.settle.data.media.MediaThumbnail
import com.iltan.settle.domain.scan.MediaKind
import kotlinx.coroutines.flow.Flow

/** What the screens read from and change in the library. */
class Library(private val dao: LibraryDao) {
    fun timeline(): Flow<List<MediaEntry>> = dao.timeline()

    fun onPhoneCount(): Flow<Int> = dao.onPhoneCount()

    fun lastScanAt(): Flow<Long?> = dao.lastScanAt()
}

/** The item's MediaStore URI in its own collection, which viewers and share targets expect. */
val MediaEntry.contentUri: Uri
    get() = mediaContentUri(kind, volume, mediaStoreId)

val MediaEntry.thumbnail: MediaThumbnail
    get() = MediaThumbnail(contentUri, dateModifiedS)

fun mediaContentUri(kind: MediaKind, volume: String, mediaStoreId: Long): Uri =
    ContentUris.withAppendedId(
        when (kind) {
            MediaKind.IMAGE -> MediaStore.Images.Media.getContentUri(volume)
            MediaKind.VIDEO -> MediaStore.Video.Media.getContentUri(volume)
        },
        mediaStoreId,
    )
