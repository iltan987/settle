package com.iltan.settle.data.db

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.iltan.settle.domain.CloudState
import com.iltan.settle.domain.LocalState
import com.iltan.settle.domain.MediaItemId
import com.iltan.settle.domain.scan.IndexedRow
import com.iltan.settle.domain.scan.MediaKind
import com.iltan.settle.domain.scan.ScanPlan
import com.iltan.settle.domain.scan.ScanRow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LibraryDaoTest {

    private lateinit var db: SettleDatabase
    private lateinit var dao: LibraryDao
    private val volume = "external_primary"
    private var nextId = 0L

    @Before
    fun setUp() {
        db =
            Room.inMemoryDatabaseBuilder<SettleDatabase>(
                    ApplicationProvider.getApplicationContext<Context>()
                )
                .setDriver(AndroidSQLiteDriver())
                .build()
        dao = db.libraryDao()
    }

    @After fun tearDown() = db.close()

    private fun id() = MediaItemId.generate(nowMillis = ++nextId)

    private fun row(
        msId: Long,
        path: String = "DCIM/Camera/",
        taken: Long = msId * 1000,
        favorite: Boolean = false,
        trashed: Boolean = false,
        name: String = "IMG_$msId.jpg",
    ) =
        ScanRow(
            volume = volume,
            mediaStoreId = msId,
            relativePath = path,
            displayName = name,
            mimeType = "image/jpeg",
            kind = MediaKind.IMAGE,
            sizeBytes = 100 + msId,
            dateTakenMillis = taken,
            dateModifiedSeconds = taken / 1000,
            width = 10,
            height = 10,
            orientation = 0,
            durationMillis = 0,
            specialFormat = 0,
            isFavorite = favorite,
            isTrashed = trashed,
            generationModified = msId,
        )

    private fun plan(
        inserts: List<IndexedRow> = emptyList(),
        updates: List<IndexedRow> = emptyList(),
        forgets: List<MediaItemId> = emptyList(),
        missing: List<MediaItemId> = emptyList(),
    ) = ScanPlan(inserts, updates, forgets, missing, breakerTripped = false)

    private fun cursor(generation: Long = 1) =
        ScanCursorEntity(
            volume,
            mediaStoreVersion = "v1",
            generation = generation,
            lastScanAtMs = 5,
        )

    private suspend fun insert(vararg rows: ScanRow): List<MediaItemId> {
        val indexed = rows.map { IndexedRow(id(), it) }
        dao.applyScan(plan(inserts = indexed), cursor(), nowMs = 1)
        return indexed.map { it.itemId }
    }

    @Test
    fun insertedItemsAppearNewestFirstWithTheCursor() = runTest {
        insert(row(1, taken = 1_000), row(2, taken = 3_000), row(3, taken = 2_000))

        assertEquals(listOf(2L, 3L, 1L), dao.timeline().first().map { it.mediaStoreId })
        assertEquals(3, dao.onPhoneCount().first())
        assertEquals(cursor(), dao.cursor(volume))
        assertEquals(5L, dao.lastScanAt().first())
    }

    @Test
    fun timelineSkipsTrashedFilesAndHiddenFolders() = runTest {
        insert(row(1), row(2, trashed = true), row(3, path = "Pictures/WhatsApp/"))
        dao.setFolderHidden("Pictures/WhatsApp/", hidden = true)

        assertEquals(listOf(1L), dao.timeline().first().map { it.mediaStoreId })
        assertEquals(listOf(3L), dao.folder("Pictures/WhatsApp/").first().map { it.mediaStoreId })
    }

    @Test
    fun hidingAFolderAndBackingItUpAreIndependentChoices() = runTest {
        dao.setFolderBackUp("DCIM/Screenshots/", backUp = true)
        dao.setFolderHidden("DCIM/Screenshots/", hidden = true)
        dao.setFolderHidden("DCIM/Screenshots/", hidden = false)

        assertEquals(
            FolderSettingEntity("DCIM/Screenshots/", hiddenFromTimeline = false, backUp = true),
            dao.folderSetting("DCIM/Screenshots/"),
        )
    }

    @Test
    fun foldersHaveCountsNewestCoverAndHiddenFlag() = runTest {
        insert(
            row(1, taken = 1_000),
            row(2, taken = 5_000),
            row(3, path = "DCIM/Screenshots/", taken = 3_000),
        )
        dao.setFolderHidden("DCIM/Screenshots/", hidden = true)

        val folders = dao.folders().first()

        assertEquals(listOf("DCIM/Camera/", "DCIM/Screenshots/"), folders.map { it.relativePath })
        assertEquals(2, folders[0].itemCount)
        assertEquals(2L, folders[0].coverMediaStoreId)
        assertFalse(folders[0].hiddenFromTimeline)
        assertTrue(folders[1].hiddenFromTimeline)
    }

    @Test
    fun foldersComeBiggestFirstWithWhatIsBackedUpAndTheirSize() = runTest {
        val (a) =
            insert(
                row(1, taken = 1_000),
                row(2, taken = 2_000),
                row(3, path = "DCIM/Screenshots/", taken = 9_000),
            )
        db.backupDao().setCloudState(a.value, CloudState.VERIFIED)

        val folders = dao.folders().first()

        // Screenshots holds the newest item, but Camera holds more.
        assertEquals(listOf("DCIM/Camera/", "DCIM/Screenshots/"), folders.map { it.relativePath })
        assertEquals(1, folders[0].backedUpCount)
        assertEquals(101L + 102L, folders[0].sizeBytes)
        assertEquals(0, folders[1].backedUpCount)
    }

    @Test
    fun pendingBackupListsItemsWithoutAVerifiedCopy() = runTest {
        val (a, b) = insert(row(1, taken = 1_000), row(2, taken = 2_000), row(3, trashed = true))
        db.backupDao().setCloudState(a.value, CloudState.VERIFIED)

        val pending = dao.pendingBackup().first()

        assertEquals(listOf(b.value), pending.map { it.entry.id })
        assertEquals("DCIM/Camera/", pending.single().relativePath)
        assertEquals(102L, pending.single().sizeBytes)
    }

    @Test
    fun favoritesAreListedAndCanBeToggled() = runTest {
        val (a, b) = insert(row(1, favorite = true), row(2))

        assertEquals(listOf(a.value), dao.favorites().first().map { it.id })
        dao.setFavorite(b.value, true)
        dao.setFavorite(a.value, false)
        assertEquals(listOf(b.value), dao.favorites().first().map { it.id })
    }

    @Test
    fun forgettingAnItemRemovesItsFile() = runTest {
        val (a) = insert(row(1))

        dao.applyScan(plan(forgets = listOf(a)), cursor(2), nowMs = 2)

        assertEquals(0, dao.onPhoneCount().first())
        assertTrue(dao.knownFiles(volume).isEmpty())
        assertNull(dao.details(a.value))
    }

    @Test
    fun missingFilesLoseTheirMediaStoreIdAndLeaveTheTimeline() = runTest {
        val (a) = insert(row(1))

        dao.applyScan(plan(missing = listOf(a)), cursor(2), nowMs = 2)

        val known = dao.knownFiles(volume).single()
        assertEquals(LocalState.MISSING, known.localState)
        assertNull(known.mediaStoreId)
        assertTrue(dao.hasMissingFiles(volume))
        assertTrue(dao.timeline().first().isEmpty())
    }

    @Test
    fun filesThatSwapMediaStoreIdsUpdateWithoutConflict() = runTest {
        val (a, b) = insert(row(1, name = "a.jpg"), row(2, name = "b.jpg"))

        dao.applyScan(
            plan(
                updates =
                    listOf(
                        IndexedRow(a, row(2, name = "a.jpg")),
                        IndexedRow(b, row(1, name = "b.jpg")),
                    )
            ),
            cursor(2),
            nowMs = 2,
        )

        val byItem = dao.knownFiles(volume).associate { it.itemId to it.mediaStoreId }
        assertEquals(mapOf(a.value to 2L, b.value to 1L), byItem)
    }

    @Test
    fun updateKeepsTheItemAndChangesItsData() = runTest {
        val (a) = insert(row(1))

        dao.applyScan(
            plan(updates = listOf(IndexedRow(a, row(1, path = "Pictures/Trip/", favorite = true)))),
            cursor(2),
            nowMs = 2,
        )

        val details = dao.details(a.value)!!
        assertEquals("Pictures/Trip/", details.relativePath)
        assertEquals(listOf(a.value), dao.favorites().first().map { it.id })
    }
}
