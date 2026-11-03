package com.iltan.settle.ui.trash

import android.content.Context
import android.content.Intent

/**
 * Samsung Gallery's own trash. Deleting in Gallery takes a photo out of MediaStore and keeps the
 * file where no other app can read it, so Settle can neither show nor restore one that never
 * reached Drive; it can only point there.
 */
object GalleryTrash {
    private const val PACKAGE = "com.sec.android.gallery3d"

    /**
     * Gallery's trash screen. Undocumented, but Gallery declares it in an exported intent filter
     * (seen on the S24 FE, One UI 9), so it is used only when it resolves.
     */
    private const val TRASH_VIEW = "com.android.gallery.action.TRASH_VIEW"

    /** Opens Gallery's trash, or else Gallery itself; null without Samsung Gallery. */
    fun intent(context: Context): Intent? {
        val packages = context.packageManager
        val trash = Intent(TRASH_VIEW).setPackage(PACKAGE)
        if (trash.resolveActivity(packages) != null) return trash
        return packages.getLaunchIntentForPackage(PACKAGE)
    }
}
