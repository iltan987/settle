package com.iltan.settle.data.media

import android.net.Uri
import android.util.Size
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.key.Keyer
import coil3.request.Options
import coil3.size.pxOrElse

/**
 * A grid thumbnail of a photo or video on the phone. [dateModifiedS] is part of the cache key so an
 * edited file gets a fresh thumbnail.
 */
data class MediaThumbnail(val uri: Uri, val dateModifiedS: Long) {
    /** Memory cache key; the viewer uses it to show this thumbnail while the full image loads. */
    val cacheKey: String
        get() = "thumbnail:$uri:$dateModifiedS"
}

/**
 * Loads [MediaThumbnail]s through `ContentResolver.loadThumbnail`, which serves MediaStore's cached
 * thumbnails for photos and videos. Coil on its own would decode every full-size file.
 */
class MediaThumbnailFetcher(private val data: MediaThumbnail, private val options: Options) :
    Fetcher {

    override suspend fun fetch(): FetchResult {
        val width = options.size.width.pxOrElse { DEFAULT_SIZE }
        val height = options.size.height.pxOrElse { DEFAULT_SIZE }
        val bitmap =
            options.context.contentResolver.loadThumbnail(data.uri, Size(width, height), null)
        return ImageFetchResult(
            image = bitmap.asImage(),
            isSampled = true,
            dataSource = DataSource.DISK,
        )
    }

    class Factory : Fetcher.Factory<MediaThumbnail> {
        override fun create(data: MediaThumbnail, options: Options, imageLoader: ImageLoader) =
            MediaThumbnailFetcher(data, options)
    }

    class Key : Keyer<MediaThumbnail> {
        override fun key(data: MediaThumbnail, options: Options) = data.cacheKey
    }

    private companion object {
        const val DEFAULT_SIZE = 384
    }
}
