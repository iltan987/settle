package com.iltan.settle.data.media

import android.content.ContentResolver
import android.database.ContentObserver
import android.net.Uri
import android.provider.MediaStore

/**
 * Requests a scan whenever MediaStore reports a photo or video change, while the process lives. The
 * notification is only a trigger: what changed is read from generations, since the change flags are
 * optional for providers.
 */
class MediaChangeObserver(private val onChange: () -> Unit) : ContentObserver(null) {

    fun register(resolver: ContentResolver) {
        for (uri in watched) resolver.registerContentObserver(uri, true, this)
    }

    override fun onChange(selfChange: Boolean) = onChange()

    override fun onChange(selfChange: Boolean, uri: Uri?) = onChange()

    override fun onChange(selfChange: Boolean, uris: Collection<Uri>, flags: Int) = onChange()

    private companion object {
        val watched =
            listOf(
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL),
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL),
            )
    }
}
