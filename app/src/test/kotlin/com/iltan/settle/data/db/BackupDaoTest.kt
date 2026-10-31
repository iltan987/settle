package com.iltan.settle.data.db

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.iltan.settle.data.drive.RoomUploadStore
import com.iltan.settle.domain.CloudState
import com.iltan.settle.domain.MediaItemId
import com.iltan.settle.domain.backup.FileSnapshot
import com.iltan.settle.domain.backup.UploadProblem
import com.iltan.settle.domain.backup.UploadRecord
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
class BackupDaoTest {

    private lateinit var db: SettleDatabase
    private lateinit var dao: BackupDao
    private lateinit var store: RoomUploadStore
    private var nextId = 0L

    @Before
    fun setUp() {
        db =
            Room.inMemoryDatabaseBuilder<SettleDatabase>(
                    ApplicationProvider.getApplicationContext<Context>()
                )
                .setDriver(AndroidSQLiteDriver())
                .build()
        dao = db.backupDao()
        store = RoomUploadStore(dao)
    }

    @After fun tearDown() = db.close()

    private suspend fun insertItem(): MediaItemId {
        val id = MediaItemId.generate(nowMillis = ++nextId)
        val row =
            ScanRow(
                volume = "external_primary",
                mediaStoreId = nextId,
                relativePath = "DCIM/Camera/",
                displayName = "IMG_$nextId.jpg",
                mimeType = "image/jpeg",
                kind = MediaKind.IMAGE,
                sizeBytes = 100,
                dateTakenMillis = 5_000,
                dateModifiedSeconds = 5,
                width = 4000,
                height = 3000,
                orientation = 0,
                durationMillis = 0,
                specialFormat = 2,
                isFavorite = false,
                isTrashed = false,
                generationModified = nextId,
            )
        db.libraryDao()
            .applyScan(
                ScanPlan(listOf(IndexedRow(id, row)), emptyList(), emptyList(), emptyList(), false),
                ScanCursorEntity("external_primary", "v1", 1, 1),
                nowMs = 1,
            )
        return id
    }

    private fun record(id: MediaItemId, state: CloudState, problem: UploadProblem? = null) =
        UploadRecord(
            itemId = id,
            driveFileId = "drive-$id",
            state = state,
            snapshot = FileSnapshot(100, 5),
            sessionUri = "https://session",
            sessionStartedAtMillis = 7,
            confirmedBytes = 50,
            problem = problem,
        )

    @Test
    fun anUploadRecordRoundTripsAndMovesTheItemsCloudState() = runTest {
        val id = insertItem()
        val uploading = record(id, CloudState.UPLOADING)

        store.save(uploading)

        assertEquals(uploading, store.record(id))
        assertEquals(CloudState.UPLOADING, db.libraryDao().timeline().first().single().cloudState)
        // The scan rules see it too, so a vanished original is kept as missing (ADR-006).
        assertEquals(
            CloudState.UPLOADING,
            db.libraryDao().knownFiles("external_primary").single().cloudState,
        )
    }

    @Test
    fun theUploadSeesTheItemsNameFolderAndFormat() = runTest {
        val id = insertItem()

        val item = store.item(id)!!

        assertEquals("IMG_1.jpg", item.displayName)
        assertEquals("DCIM/Camera/", item.relativePath)
        assertEquals(5_000L, item.takenAtMillis)
        assertEquals(2, item.specialFormat)
    }

    @Test
    fun onlyUnfinishedUploadsWithoutAProblemAreResumed() = runTest {
        val (reserved, uploading, uploaded, verified, full) = List(5) { insertItem() }
        store.save(record(reserved, CloudState.RESERVED))
        store.save(record(uploading, CloudState.UPLOADING))
        store.save(record(uploaded, CloudState.UPLOADED))
        store.save(record(verified, CloudState.VERIFIED))
        store.save(record(full, CloudState.UPLOADING, UploadProblem.QUOTA_FULL))

        assertEquals(
            listOf(reserved, uploading, uploaded).map { it.value },
            dao.unfinishedUploads(),
        )
    }

    @Test
    fun theDebugResetForgetsUploadsAndFolders() = runTest {
        val id = insertItem()
        store.save(record(id, CloudState.VERIFIED))
        store.saveFolderId("Settle (debug)", "root1")

        dao.forgetAllUploads()

        assertNull(store.record(id))
        assertNull(store.folderId("Settle (debug)"))
        assertEquals(CloudState.NONE, db.libraryDao().timeline().first().single().cloudState)
    }

    @Test
    fun theAccountIsOneRow() = runTest {
        dao.setAccount(DriveAccountEntity(email = "a@example.com"))
        dao.setAccount(DriveAccountEntity(email = "b@example.com"))

        assertEquals("b@example.com", dao.accountEmail())
        assertEquals("b@example.com", dao.account().first()?.email)

        dao.deleteAccount()
        assertNull(dao.accountEmail())
    }
}
