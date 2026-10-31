package com.iltan.settle.data.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MotionPhotosTest {

    /** Container directory as Samsung Camera writes it: primary, Ultra HDR gain map, video. */
    private fun xmp(items: String) =
        """
        <x:xmpmeta xmlns:x="adobe:ns:meta/">
          <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
            <rdf:Description
                xmlns:GCamera="http://ns.google.com/photos/1.0/camera/"
                xmlns:Container="http://ns.google.com/photos/1.0/container/"
                xmlns:Item="http://ns.google.com/photos/1.0/container/item/"
                GCamera:MotionPhoto="1" GCamera:MotionPhotoVersion="1">
              <Container:Directory>
                <rdf:Seq>$items</rdf:Seq>
              </Container:Directory>
            </rdf:Description>
          </rdf:RDF>
        </x:xmpmeta>
        """
            .trimIndent()

    private fun item(semantic: String, length: String? = null) =
        """
        <rdf:li rdf:parseType="Resource">
          <Container:Item Item:Mime="image/jpeg" Item:Semantic="$semantic"
              ${length?.let { "Item:Length=\"$it\"" }.orEmpty()}/>
        </rdf:li>
        """

    @Test
    fun readsTheMotionPhotoItemLength() {
        val directory = item("Primary") + item("GainMap", "34372") + item("MotionPhoto", "4086745")

        assertEquals(4_086_745L, MotionPhotos.videoLengthFromXmp(xmp(directory)))
    }

    @Test
    fun noMotionPhotoItemMeansNoVideo() {
        assertNull(MotionPhotos.videoLengthFromXmp(xmp(item("Primary") + item("GainMap", "1"))))
    }

    @Test
    fun missingOrInvalidLengthMeansNoVideo() {
        assertNull(MotionPhotos.videoLengthFromXmp(xmp(item("MotionPhoto"))))
        assertNull(MotionPhotos.videoLengthFromXmp(xmp(item("MotionPhoto", "0"))))
        assertNull(MotionPhotos.videoLengthFromXmp(xmp(item("MotionPhoto", "abc"))))
    }

    @Test
    fun aDifferentPrefixForTheSameNamespaceStillWorks() {
        val renamed =
            xmp(item("MotionPhoto", "42")).replace("xmlns:Item=", "xmlns:I=").replace("Item:", "I:")

        assertEquals(42L, MotionPhotos.videoLengthFromXmp(renamed))
    }
}
