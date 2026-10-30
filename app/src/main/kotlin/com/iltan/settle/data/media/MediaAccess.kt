package com.iltan.settle.data.media

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager

/** How much of the phone's media Settle may read. Read fresh each time; never stored. */
enum class MediaAccess {
    /** All photos and videos: the library can be scanned. */
    FULL,
    /** Only photos the user picked: a scan would see most files as deleted, so none runs. */
    PARTIAL,
    NONE;

    companion object {
        /** Requested together so the system shows one "photos and videos" dialog. */
        val permissions =
            arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
                // Needed to read photo locations and, from Phase 2, the unredacted original bytes.
                Manifest.permission.ACCESS_MEDIA_LOCATION,
            )

        fun of(context: Context): MediaAccess {
            fun granted(permission: String) =
                context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
            return when {
                granted(Manifest.permission.READ_MEDIA_IMAGES) &&
                    granted(Manifest.permission.READ_MEDIA_VIDEO) -> FULL
                granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> PARTIAL
                else -> NONE
            }
        }
    }
}
