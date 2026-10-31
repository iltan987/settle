package com.iltan.settle.data.db

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.iltan.settle.data.drive.RoomUploadStore
import com.iltan.settle.domain.CloudState
import com.iltan.settle.domain.LocalState
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

    private suspend fun insertItem(
        path: String = "DCIM/Camera/",
        dateAddedSeconds: Long = 0,
    ): MediaItemId {
        val id = MediaItemId.generate(nowMillis = ++nextId)
        val row =
            ScanRow(
                volume = "external_primary",
                mediaStoreId = nextId,
                relativePath = path,
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
                dateAddedSeconds = dateAddedSeconds,
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
    fun aReplacedDriveFileIsRememberedOnceAndOutlivesTheItem() = runTest {
        val id = insertItem()
        store.recordSuperseded(id, "old1")
        store.recordSuperseded(id, "old1")

        db.libraryDao().deleteItem(id.value)

        assertEquals(listOf(SupersededFileEntity("old1", id.value)), dao.superseded(id.value))
    }

    @Test
    fun theDebugResetForgetsUploadsAndFolders() = runTest {
        val id = insertItem()
        store.save(record(id, CloudState.VERIFIED))
        store.saveFolderId("Settle (debug)", "root1")
        store.recordSuperseded(id, "old1")

        dao.forgetAllUploads()

        assertNull(store.record(id))
        assertNull(store.folderId("Settle (debug)"))
        assertEquals(emptyList<SupersededFileEntity>(), dao.superseded(id.value))
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

    @Test
    fun backupCandidatesCarryFolderChoiceRetryAndRequest() = runTest {
        val camera = insertItem(dateAddedSeconds = 1_700)
        val screenshot = insertItem(path = "DCIM/Screenshots/")
        val verified = insertItem()
        db.libraryDao().setFolderBackUp("DCIM/Screenshots/", backUp = true)
        store.save(record(camera, CloudState.UPLOADING, UploadProblem.OFFLINE))
        dao.saveRetry(UploadRetryEntity(camera.value, attempts = 2, retryAtMs = 99))
        dao.addRequests(listOf(BackupRequestEntity(screenshot.value, requestedAtMs = 42)))
        store.save(record(verified, CloudState.VERIFIED))

        val rows = dao.backupCandidates().associateBy { it.id }

        assertEquals(setOf(camera.value, screenshot.value), rows.keys)
        assertEquals(
            BackupCandidateRow(
                id = camera.value,
                localState = LocalState.PRESENT,
                sizeBytes = 100,
                takenAtMs = 5_000,
                dateAddedS = 1_700,
                relativePath = "DCIM/Camera/",
                backUp = null,
                cloudState = CloudState.UPLOADING,
                problem = UploadProblem.OFFLINE,
                retryAtMs = 99,
                attempts = 2,
                requestedAtMs = null,
            ),
            rows.getValue(camera.value),
        )
        assertEquals(true, rows.getValue(screenshot.value).backUp)
        assertEquals(42L, rows.getValue(screenshot.value).requestedAtMs)
        assertEquals(1, dao.backedUpCount().first())
    }

    @Test
    fun aRepeatedRequestKeepsItsFirstTimeAndVerifyingClearsRequestAndRetry() = runTest {
        val id = insertItem()
        dao.addRequests(listOf(BackupRequestEntity(id.value, requestedAtMs = 1)))
        dao.addRequests(listOf(BackupRequestEntity(id.value, requestedAtMs = 2)))
        dao.saveRetry(UploadRetryEntity(id.value, attempts = 1, retryAtMs = 5))
        assertEquals(1L, dao.backupCandidates().single().requestedAtMs)

        store.save(record(id, CloudState.VERIFIED))

        assertEquals(emptyList<BackupCandidateRow>(), dao.backupCandidates())
        assertNull(dao.retry(id.value))
        store.save(record(id, CloudState.UPLOADING))
        assertNull(dao.backupCandidates().single().requestedAtMs)
    }
}
