package com.iltan.settle.data.media

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.iltan.settle.data.db.SettleDatabase
import com.iltan.settle.domain.LocalState
import com.iltan.settle.domain.scan.MediaKind
import com.iltan.settle.domain.scan.ScanRow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LibrarySyncTest {

    private val volume = "external_primary"

    /** A MediaStore volume held in memory. */
    private class FakeSource(val volume: String) : MediaSource {
        var version = "v1"
        var generation = 0L
        val files = mutableMapOf<Long, ScanRow>()
        val requestedSince = mutableListOf<Long?>()

        fun add(row: ScanRow) {
            generation++
            files[row.mediaStoreId] = row.copy(generationModified = generation)
        }

        override fun volumes() = setOf(volume)

        override fun version(volume: String) = version

        override fun generation(volume: String) = generation

        override fun rows(volume: String, sinceGeneration: Long?): List<ScanRow> {
            requestedSince += sinceGeneration
            return files.values.filter { it.generationModified > (sinceGeneration ?: -1) }
        }

        override fun ids(volume: String) = files.keys.toSet()
    }

    private lateinit var db: SettleDatabase
    private val source = FakeSource(volume)
    private var fullAccess = true
    private var clock = 1_000L
    private lateinit var sync: LibrarySync

    @Before
    fun setUp() {
        db =
            Room.inMemoryDatabaseBuilder<SettleDatabase>(
                    ApplicationProvider.getApplicationContext<Context>()
                )
                .setDriver(AndroidSQLiteDriver())
                .build()
        sync = LibrarySync(source, db.libraryDao(), { fullAccess }, now = { clock++ })
    }

    @After fun tearDown() = db.close()

    private fun row(id: Long, name: String = "IMG_$id.jpg") =
        ScanRow(
            volume = volume,
            mediaStoreId = id,
            relativePath = "DCIM/Camera/",
            displayName = name,
            mimeType = "image/jpeg",
            kind = MediaKind.IMAGE,
            sizeBytes = 100 + id,
            dateTakenMillis = id * 1000,
            dateModifiedSeconds = id,
            width = 1,
            height = 1,
            orientation = 0,
            durationMillis = 0,
            specialFormat = 0,
            isFavorite = false,
            isTrashed = false,
            generationModified = 0,
        )

    private suspend fun itemIdsByMediaStoreId() =
        db.libraryDao().knownFiles(volume).associate { it.mediaStoreId to it.itemId }

    @Test
    fun withoutFullAccessNothingIsReadOrWritten() = runTest {
        source.add(row(1))
        fullAccess = false

        sync.scan()

        assertTrue(source.requestedSince.isEmpty())
        assertNull(db.libraryDao().cursor(volume))
    }

    @Test
    fun firstScanIsFullAndLaterScansAreIncremental() = runTest {
        source.add(row(1))
        source.add(row(2))
        sync.scan()
        source.add(row(3))
        sync.scan()

        assertEquals(listOf(null, 2L), source.requestedSince)
        assertEquals(3, db.libraryDao().onPhoneCount().first())
        assertEquals(3L, db.libraryDao().cursor(volume)!!.generation)
    }

    @Test
    fun deletedFileIsForgotten() = runTest {
        source.add(row(1))
        source.add(row(2))
        sync.scan()
        source.files.remove(1)

        sync.scan()

        assertEquals(setOf(2L), itemIdsByMediaStoreId().keys)
    }

    @Test
    fun mediaStoreRebuildKeepsItemIdsByMatchingFiles() = runTest {
        source.add(row(1, name = "a.jpg"))
        source.add(row(2, name = "b.jpg"))
        sync.scan()
        val before = itemIdsByMediaStoreId()

        // A rebuild renumbers everything and resets generations.
        val a = source.files.getValue(1)
        val b = source.files.getValue(2)
        source.files.clear()
        source.version = "v2"
        source.generation = 0
        source.add(b.copy(mediaStoreId = 10))
        source.add(a.copy(mediaStoreId = 11))
        sync.scan()

        assertEquals(null, source.requestedSince.last())
        val after = itemIdsByMediaStoreId()
        assertEquals(before[1L], after[11L])
        assertEquals(before[2L], after[10L])
    }

    @Test
    fun missingFilesForceAFullScanAndAreFoundAgain() = runTest {
        (1L..300L).forEach { source.add(row(it)) }
        sync.scan()
        val saved = source.files.toMap()
        source.files.clear()
        sync.scan()
        assertTrue(sync.state.value.breakerTripped)
        assertTrue(db.libraryDao().knownFiles(volume).all { it.localState == LocalState.MISSING })

        source.files.putAll(saved)
        sync.scan()

        assertEquals(null, source.requestedSince.last())
        assertEquals(300, db.libraryDao().onPhoneCount().first())
        assertEquals(false, sync.state.value.breakerTripped)
    }
}
