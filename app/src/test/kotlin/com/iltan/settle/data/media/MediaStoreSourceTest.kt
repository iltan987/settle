package com.iltan.settle.data.media

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.provider.MediaStore
import android.provider.MediaStore.Files.FileColumns
import android.provider.MediaStore.MediaColumns
import androidx.test.core.app.ApplicationProvider
import com.iltan.settle.domain.LocalState
import com.iltan.settle.domain.scan.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MediaStoreSourceTest {

    /** Answers MediaStore queries with fixed rows and records what was asked. */
    class FakeMediaProvider : ContentProvider() {
        var lastUri: Uri? = null
        var lastArgs: Bundle? = null
        val rows = mutableListOf<Map<String, Any?>>()

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            queryArgs: Bundle?,
            cancellationSignal: CancellationSignal?,
        ): Cursor {
            lastUri = uri
            lastArgs = queryArgs
            val columns = checkNotNull(projection)
            return MatrixCursor(columns).apply {
                rows.forEach { row -> addRow(columns.map { row[it] }) }
            }
        }

        override fun onCreate() = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor? = null

        override fun getType(uri: Uri): String? = null

        override fun insert(uri: Uri, values: ContentValues?): Uri? = null

        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0

        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?,
        ) = 0
    }

    private lateinit var provider: FakeMediaProvider
    private lateinit var source: MediaStoreSource

    @Before
    fun setUp() {
        provider =
            Robolectric.buildContentProvider(FakeMediaProvider::class.java)
                .create(MediaStore.AUTHORITY)
                .get()
        source = MediaStoreSource(ApplicationProvider.getApplicationContext<Context>())
    }

    private fun videoRow(id: Long) =
        mapOf(
            MediaColumns._ID to id,
            MediaColumns.RELATIVE_PATH to "Movies/",
            MediaColumns.DISPLAY_NAME to "clip.mp4",
            MediaColumns.MIME_TYPE to "video/mp4",
            FileColumns.MEDIA_TYPE to FileColumns.MEDIA_TYPE_VIDEO,
            MediaColumns.SIZE to 5_000L,
            MediaColumns.DATE_TAKEN to null,
            MediaColumns.DATE_MODIFIED to 1_700_000_000L,
            MediaColumns.WIDTH to 1920,
            MediaColumns.HEIGHT to 1080,
            MediaColumns.ORIENTATION to 90,
            MediaColumns.DURATION to 12_345L,
            MediaColumns.IS_FAVORITE to 1,
            MediaColumns.IS_TRASHED to 1,
            MediaColumns.GENERATION_MODIFIED to 77L,
            FileColumns.SPECIAL_FORMAT to FileColumns.SPECIAL_FORMAT_NONE,
        )

    @Test
    fun mapsRowsFromTheFilesCollection() {
        provider.rows += videoRow(42)

        val row = source.rows("external_primary").single()

        assertEquals(MediaStore.Files.getContentUri("external_primary"), provider.lastUri)
        assertEquals(42L, row.mediaStoreId)
        assertEquals(MediaKind.VIDEO, row.kind)
        assertEquals("Movies/", row.relativePath)
        assertNull(row.dateTakenMillis)
        assertEquals(1_700_000_000_000L, row.takenAtMillis)
        assertEquals(12_345L, row.durationMillis)
        assertEquals(true, row.isFavorite)
        assertEquals(LocalState.TRASHED, row.localState)
        assertEquals(77L, row.generationModified)
    }

    @Test
    fun includesTrashedRowsAndFiltersByGeneration() {
        source.rows("external_primary", sinceGeneration = 10)

        val args = checkNotNull(provider.lastArgs)
        assertEquals(MediaStore.MATCH_INCLUDE, args.getInt(MediaStore.QUERY_ARG_MATCH_TRASHED))
        val selection = args.getString(ContentResolver.QUERY_ARG_SQL_SELECTION)!!
        assertEquals(true, selection.contains("${MediaColumns.GENERATION_MODIFIED} > ?"))
        assertEquals(
            listOf("10", "10"),
            args.getStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS)!!.toList(),
        )
    }

    @Test
    fun readsAllIds() {
        provider.rows += videoRow(1)
        provider.rows += videoRow(2)

        assertEquals(setOf(1L, 2L), source.ids("external_primary"))
    }
}
