package com.iltan.settle.data.drive

import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.key.Keyer
import coil3.request.Options
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path.Companion.toOkioPath

/**
 * Drive's preview of a file Settle created, for items whose phone file is gone (the trash's "only
 * in Drive" cells). A Drive file's bytes never change under its ID, so a preview fetched once is
 * kept in the app's cache, which Android may clear when space runs low.
 */
data class DriveThumbnail(val driveFileId: String)

/**
 * Loads [DriveThumbnail]s through Drive's `thumbnailLink`, keeping each in [cacheDir]. Without a
 * preview from Drive, or offline, the load fails and the cell keeps its placeholder.
 */
class DriveThumbnailFetcher(
    private val data: DriveThumbnail,
    private val cacheDir: File,
    private val drive: () -> DriveRest,
) : Fetcher {

    override suspend fun fetch(): FetchResult {
        val file = File(cacheDir, "${data.driveFileId}.thumb")
        if (!file.exists()) {
            val bytes =
                drive().thumbnail(data.driveFileId)
                    ?: throw IOException("Drive has no preview of ${data.driveFileId}")
            withContext(Dispatchers.IO) {
                cacheDir.mkdirs()
                // Written whole, then moved into place, so a half-written preview is never read.
                val partial = File(cacheDir, "${data.driveFileId}.part")
                partial.writeBytes(bytes)
                if (!partial.renameTo(file)) throw IOException("Couldn't keep the preview")
            }
        }
        return SourceFetchResult(
            source = ImageSource(file.toOkioPath(), FileSystem.SYSTEM),
            mimeType = null,
            dataSource = DataSource.DISK,
        )
    }

    class Factory(private val cacheDir: File, private val drive: () -> DriveRest) :
        Fetcher.Factory<DriveThumbnail> {
        override fun create(data: DriveThumbnail, options: Options, imageLoader: ImageLoader) =
            DriveThumbnailFetcher(data, cacheDir, drive)
    }

    class Key : Keyer<DriveThumbnail> {
        override fun key(data: DriveThumbnail, options: Options) = "drive:${data.driveFileId}"
    }
}
