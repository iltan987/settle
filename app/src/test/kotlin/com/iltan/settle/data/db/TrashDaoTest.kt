package com.iltan.settle.data.db

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.iltan.settle.data.drive.RoomUploadStore
import com.iltan.settle.data.trash.TrashStore
import com.iltan.settle.domain.CloudState
import com.iltan.settle.domain.LocalState
import com.iltan.settle.domain.MediaItemId
import com.iltan.settle.domain.backup.FileSnapshot
import com.iltan.settle.domain.backup.UploadRecord
import com.iltan.settle.domain.deletion.DriveOp
import com.iltan.settle.domain.deletion.DriveOpKind
import com.iltan.settle.domain.deletion.TrashChanges
import com.iltan.settle.domain.deletion.TrashEntry
import com.iltan.settle.domain.deletion.TrashOrigin
import com.iltan.settle.domain.deletion.Vanished
import com.iltan.settle.domain.deletion.VanishedReason
import com.iltan.settle.domain.scan.IndexedRow
import com.iltan.settle.domain.scan.MediaKind
import com.iltan.settle.domain.scan.ScanPlan
import com.iltan.settle.domain.scan.ScanRow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TrashDaoTest {

    private lateinit var db: SettleDatabase
    private lateinit var dao: TrashDao
    private lateinit var store: TrashStore
    private var nextId = 0L

    @Before
    fun setUp() {
        db =
            Room.inMemoryDatabaseBuilder<SettleDatabase>(
                    ApplicationProvider.getApplicationContext<Context>()
                )
                .setDriver(AndroidSQLiteDriver())
                .build()
        dao = db.trashDao()
        store = TrashStore(dao)
    }

    @After fun tearDown() = db.close()

    private fun row(mediaStoreId: Long, trashed: Boolean = false) =
        ScanRow(
            volume = "external_primary",
            mediaStoreId = mediaStoreId,
            relativePath = "DCIM/Camera/",
            displayName = "IMG_$mediaStoreId.jpg",
            mimeType = "image/jpeg",
            kind = MediaKind.IMAGE,
            sizeBytes = 100,
            dateTakenMillis = 5_000 + mediaStoreId,
            dateModifiedSeconds = 5,
            width = 1,
            height = 1,
            orientation = 0,
            durationMillis = 0,
            specialFormat = 0,
            isFavorite = false,
            isTrashed = trashed,
            generationModified = mediaStoreId,
        )

    private suspend fun insertItem(backedUp: Boolean = true): MediaItemId {
        val id = MediaItemId.generate(nowMillis = ++nextId)
        db.libraryDao()
            .applyScan(
                ScanPlan(
                    listOf(IndexedRow(id, row(nextId))),
                    emptyList(),
                    emptyList(),
                    emptyList(),
                    false,
                ),
                ScanCursorEntity("external_primary", "v1", 1, 1),
                nowMs = 1,
            )
        if (backedUp) {
            RoomUploadStore(db.backupDao())
                .save(
                    UploadRecord(
                        id,
                        "drive-$nextId",
                        CloudState.VERIFIED,
                        FileSnapshot(100, 5),
                        sha256 = "aa",
                    )
                )
        }
        return id
    }

    @Test
    fun theRulesSeeTheItemWithItsDriveFilesEntryAndQueue() = runTest {
        val id = insertItem()
        db.backupDao().addSuperseded(SupersededFileEntity("old", id.value))
        dao.applyTrashChanges(
            TrashChanges(
                entries = listOf(TrashEntry(id, TrashOrigin.SETTLE, 10)),
                ops = listOf(DriveOp("drive-1", id, DriveOpKind.TRASH)),
                markTrashed = listOf(id),
            )
        )

        val item = store.items(listOf(id)).single()

        assertEquals(LocalState.TRASHED, item.localState)
        assertEquals(1L, item.mediaStoreId)
        assertEquals("drive-1", item.driveFiles.current)
        assertEquals(listOf("old"), item.driveFiles.superseded)
        assertEquals(TrashEntry(id, TrashOrigin.SETTLE, 10), item.entry)
        assertEquals(1, item.pendingOps)
        assertNull(item.vanished)
    }

    @Test
    fun anOperationReplacesTheQueuedOneForTheSameFile() = runTest {
        val id = insertItem()
        val trash = DriveOp("drive-1", id, DriveOpKind.TRASH, notBeforeMillis = 50)
        val untrash = DriveOp("drive-1", id, DriveOpKind.UNTRASH)
        dao.applyTrashChanges(TrashChanges(ops = listOf(trash)))
        dao.applyTrashChanges(TrashChanges(ops = listOf(untrash)))

        assertEquals(listOf(untrash), store.due(nowMillis = 100))
    }

    @Test
    fun anOperationReplacedWhileItRanIsNeitherRemovedNorRescheduled() = runTest {
        val id = insertItem()
        val trash = DriveOp("drive-1", id, DriveOpKind.TRASH)
        dao.applyTrashChanges(TrashChanges(ops = listOf(trash)))
        val running = store.due(0).single()
        // The user restores while the trash request is on its way.
        val untrash = DriveOp("drive-1", id, DriveOpKind.UNTRASH)
        dao.applyTrashChanges(TrashChanges(ops = listOf(untrash)))

        store.retry(running, notBeforeMillis = 999)
        store.done(running)

        assertEquals(listOf(untrash), store.due(0))
    }

    @Test
    fun aFailedOperationWaitsAndCountsItsTries() = runTest {
        val id = insertItem()
        dao.applyTrashChanges(TrashChanges(ops = listOf(DriveOp("drive-1", id, DriveOpKind.TRASH))))

        store.retry(store.due(0).single(), notBeforeMillis = 60_000)

        assertEquals(emptyList<DriveOp>(), store.due(59_999))
        assertEquals(1, store.due(60_000).single().attempts)
        assertEquals(60_000L, store.nextDueAt())
    }

    @Test
    fun aPermanentDeleteOutlivesTheItem() = runTest {
        val id = insertItem()
        db.backupDao().addSuperseded(SupersededFileEntity("old", id.value))
        dao.applyTrashChanges(
            TrashChanges(
                ops =
                    listOf(
                        DriveOp("drive-1", id, DriveOpKind.DELETE),
                        DriveOp("old", id, DriveOpKind.DELETE),
                    ),
                purge = listOf(id),
            )
        )

        assertEquals(emptyList<TrashItemRow>(), dao.trashItems(listOf(id.value)))
        assertEquals(emptyList<SupersededFileEntity>(), dao.supersededOf(listOf(id.value)))
        assertEquals(2, store.due(0).size)
    }

    @Test
    fun theTrashListsSettlesEntriesAndAnythingInThePhonesTrash() = runTest {
        val mine = insertItem()
        val elsewhere = insertItem()
        val onPhone = insertItem()
        dao.applyTrashChanges(
            TrashChanges(
                entries = listOf(TrashEntry(mine, TrashOrigin.SETTLE, 20)),
                markTrashed = listOf(mine, elsewhere),
            )
        )

        val trash = dao.observeTrash().first()

        assertEquals(listOf(mine.value, elsewhere.value), trash.map { it.id })
        assertEquals(TrashOrigin.SETTLE, trash[0].origin)
        assertEquals(20L + 30L * 24 * 60 * 60 * 1000, trash[0].expiresAtMs)
        assertNull(trash[1].origin)
        assertEquals(false, trash.any { it.id == onPhone.value })
    }

    @Test
    fun undecidedItemsAreListedUntilKeptOrBack() = runTest {
        val (breaker, kept, back) = List(3) { insertItem() }
        db.libraryDao().markMissing(breaker.value)
        db.libraryDao().markMissing(kept.value)
        dao.applyTrashChanges(
            TrashChanges(
                vanished =
                    listOf(
                        Vanished(breaker, 5, VanishedReason.BREAKER),
                        Vanished(kept, 5, VanishedReason.BREAKER, kept = true),
                        Vanished(back, 5, VanishedReason.ASK),
                    )
            )
        )

        assertEquals(listOf(breaker.value), dao.observeUndecided().first().map { it.id })
        assertEquals(listOf(breaker.value), dao.undecided(VanishedReason.BREAKER))
    }

    @Test
    fun aScanAndWhatTheTrashRulesMadeOfItLandTogether() = runTest {
        val id = insertItem()
        db.libraryDao()
            .applyScan(
                ScanPlan(emptyList(), emptyList(), emptyList(), listOf(id), false),
                ScanCursorEntity("external_primary", "v1", 2, 2),
                nowMs = 2,
                trash =
                    TrashChanges(
                        entries = listOf(TrashEntry(id, TrashOrigin.ELSEWHERE, 2)),
                        ops = listOf(DriveOp("drive-1", id, DriveOpKind.TRASH, 3_600_002)),
                    ),
            )

        val item = store.items(listOf(id)).single()
        assertEquals(LocalState.MISSING, item.localState)
        assertEquals(TrashOrigin.ELSEWHERE, item.entry?.origin)
        assertEquals(3_600_002L, store.nextDueAt())
    }

    @Test
    fun aRestoredFileTakesTheItemsPlaceAndLeavesTheTrash() = runTest {
        val id = insertItem()
        db.libraryDao().markMissing(id.value)
        db.libraryDao().releaseMediaStoreId(id.value)
        dao.applyTrashChanges(
            TrashChanges(entries = listOf(TrashEntry(id, TrashOrigin.ELSEWHERE, 2)))
        )
        dao.addRestoreRequests(listOf(RestoreRequestEntity(id.value, 3)))
        store.savePendingDownload(id, "content://media/external/images/media/77")

        dao.restored(id.value, mediaStoreId = 77, sizeBytes = 100, dateModifiedS = 99)

        val item = store.items(listOf(id)).single()
        assertEquals(LocalState.PRESENT, item.localState)
        assertEquals(77L, item.mediaStoreId)
        assertNull(item.entry)
        assertEquals(emptyList<RestoreRequestEntity>(), dao.restoreRequests())
        assertEquals(1, db.libraryDao().timeline().first().size)
    }

    @Test
    fun expiredEntriesAreFound() = runTest {
        val (old, recent) = List(2) { insertItem() }
        dao.applyTrashChanges(
            TrashChanges(
                entries =
                    listOf(
                        TrashEntry(old, TrashOrigin.SETTLE, 0, expiresAtMillis = 100),
                        TrashEntry(recent, TrashOrigin.SETTLE, 0, expiresAtMillis = 200),
                    )
            )
        )

        assertEquals(listOf(old), store.expired(nowMillis = 150).map { it.itemId })
    }
}
