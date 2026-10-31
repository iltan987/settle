package com.iltan.settle.data.media

import android.content.ContentResolver
import android.net.Uri
import android.util.Log
import android.util.Xml
import androidx.exifinterface.media.ExifInterface
import java.io.FileInputStream
import java.io.StringReader
import org.xmlpull.v1.XmlPullParser

/**
 * Finds the video inside a motion photo the way the Motion Photo format intends
 * (developer.android.com/media/platform/motion-photo-format): the XMP container directory gives the
 * video's length, and the video is the last that many bytes of the file. Reading only the XMP and
 * the video's first bytes is far cheaper than letting a player walk the whole JPEG.
 */
object MotionPhotos {

    /** Byte offset of the embedded MP4, or null if the file has no verifiable one. */
    fun videoOffset(resolver: ContentResolver, uri: Uri): Long? =
        try {
            resolver.openFileDescriptor(uri, "r")?.use { pfd ->
                val fileSize = pfd.statSize
                val xmp =
                    FileInputStream(pfd.fileDescriptor).use { input ->
                        ExifInterface(input).getAttributeBytes(ExifInterface.TAG_XMP)
                    } ?: return null
                val length = videoLengthFromXmp(xmp.decodeToString()) ?: return null
                val offset = fileSize - length
                // The spec asks readers to confirm the video is really there: an MP4 starts with
                // a box whose type, at bytes 4..7, is "ftyp".
                val header = ByteArray(8)
                FileInputStream(pfd.fileDescriptor).use { input ->
                    input.channel.position(offset)
                    if (input.read(header) != header.size) return null
                }
                offset.takeIf { header.decodeToString(4, 8) == "ftyp" }
            }
        } catch (e: Exception) {
            Log.w("MotionPhotos", "No embedded video found in $uri", e)
            null
        }

    /**
     * The `Item:Length` of the container item whose `Item:Semantic` is `MotionPhoto`, as written by
     * Google Camera and Samsung Camera (JPEG). Null if absent.
     */
    fun videoLengthFromXmp(xmp: String): Long? {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        parser.setInput(StringReader(xmp))
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType != XmlPullParser.START_TAG) continue
            val semantic = parser.getAttributeValue(ITEM_NS, "Semantic") ?: continue
            if (semantic == "MotionPhoto") {
                return parser.getAttributeValue(ITEM_NS, "Length")?.toLongOrNull()?.takeIf {
                    it > 0
                }
            }
        }
        return null
    }

    private const val ITEM_NS = "http://ns.google.com/photos/1.0/container/item/"
}
