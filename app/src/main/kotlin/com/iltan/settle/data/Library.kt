package com.iltan.settle.data

import android.content.ContentResolver
import android.content.ContentUris
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import com.iltan.settle.data.db.FolderSummary
import com.iltan.settle.data.db.LibraryDao
import com.iltan.settle.data.db.MediaDetails
import com.iltan.settle.data.db.MediaEntry
import com.iltan.settle.data.media.MediaThumbnail
import com.iltan.settle.data.media.MotionPhotos
import com.iltan.settle.domain.scan.MediaKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/** Which list of items a grid or the viewer shows. */
@Serializable
sealed interface GridSource {
    @Serializable data object Timeline : GridSource

    @Serializable data class Folder(val relativePath: String) : GridSource

    @Serializable data object Favorites : GridSource
}

/** What the screens read from and change in the library. */
class Library(private val dao: LibraryDao, private val resolver: ContentResolver) {

    fun entries(source: GridSource): Flow<List<MediaEntry>> =
        when (source) {
            GridSource.Timeline -> dao.timeline()
            is GridSource.Folder -> dao.folder(source.relativePath)
            GridSource.Favorites -> dao.favorites()
        }

    fun folders(): Flow<List<FolderSummary>> = dao.folders()

    fun onPhoneCount(): Flow<Int> = dao.onPhoneCount()

    fun lastScanAt(): Flow<Long?> = dao.lastScanAt()

    suspend fun details(id: String): MediaDetails? = dao.details(id)

    suspend fun setFolderHidden(relativePath: String, hidden: Boolean) =
        dao.setFolderHidden(relativePath, hidden)

    suspend fun setFolderBackUp(relativePath: String, backUp: Boolean) =
        dao.setFolderBackUp(relativePath, backUp)

    /**
     * For a file on the phone, MediaStore's favourite flag is the truth (ADR-012): it is written
     * there so other apps see it, and scans copy it back. Room is updated right away so the UI
     * doesn't wait for the next scan.
     */
    suspend fun setFavorite(entry: MediaEntry, favorite: Boolean) =
        setFavorites(listOf(entry), favorite)

    /** Marks or unmarks [entries] as favourites, in MediaStore too, in one request. */
    suspend fun setFavorites(entries: List<MediaEntry>, favorite: Boolean) {
        if (entries.isEmpty()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            withContext(Dispatchers.IO) {
                MediaStore.markIsFavoriteStatus(resolver, entries.map { it.contentUri }, favorite)
            }
        }
        entries.forEach { dao.setFavorite(it.id, favorite) }
    }

    /** Byte offset of a motion photo's embedded video; see [MotionPhotos]. */
    suspend fun motionVideoOffset(entry: MediaEntry): Long? =
        withContext(Dispatchers.IO) { MotionPhotos.videoOffset(resolver, entry.contentUri) }

    /** Where a photo was taken, from its EXIF data; null if unknown or not allowed. */
    suspend fun location(entry: MediaEntry): Pair<Double, Double>? =
        withContext(Dispatchers.IO) {
            if (entry.kind != MediaKind.IMAGE) return@withContext null
            try {
                // Without the original, MediaStore hands out a copy with the location removed.
                val original = MediaStore.setRequireOriginal(entry.contentUri)
                resolver.openInputStream(original)?.use { stream ->
                    ExifInterface(stream).latLong?.let { (lat, lon) -> lat to lon }
                }
            } catch (e: Exception) {
                // No ACCESS_MEDIA_LOCATION, or a file without readable EXIF.
                Log.w("Library", "No location for ${entry.id}", e)
                null
            }
        }
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
