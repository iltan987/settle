package com.iltan.settle.data.trash

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.iltan.settle.data.backup.BackupPreferences
import com.iltan.settle.data.db.SettleDatabase
import com.iltan.settle.data.db.SupersededFileEntity
import com.iltan.settle.data.drive.RoomUploadStore
import com.iltan.settle.data.media.LibrarySync
import com.iltan.settle.data.media.MediaSource
import com.iltan.settle.domain.CloudState
import com.iltan.settle.domain.LocalState
import com.iltan.settle.domain.MediaItemId
import com.iltan.settle.domain.backup.FakeDrive
import com.iltan.settle.domain.backup.FileSnapshot
import com.iltan.settle.domain.backup.UploadRecord
import com.iltan.settle.domain.backup.md5
import com.iltan.settle.domain.backup.sha256
import com.iltan.settle.domain.deletion.DriveChanges
import com.iltan.settle.domain.deletion.DriveOpKind
import com.iltan.settle.domain.deletion.ExternalDeletionPolicy
import com.iltan.settle.domain.deletion.FakeRestoreTarget
import com.iltan.settle.domain.deletion.RestoreDownload
import com.iltan.settle.domain.deletion.TRASH_RETENTION_MILLIS
import com.iltan.settle.domain.deletion.TrashOrigin
import com.iltan.settle.domain.deletion.VanishedDecision
import com.iltan.settle.domain.deletion.afterScan
import com.iltan.settle.domain.scan.MediaKind
import com.iltan.settle.domain.scan.ScanRow
import kotlin.random.Random
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Deleting and restoring end to end on Room, with MediaStore, the phone's trash dialogs and Drive
 * replaced by fakes: the scan and the trash rules, [Deletion] and [TrashWork] together.
 */
@RunWith(RobolectricTestRunner::class)
class DeletionTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val volume = "external_primary"
    private val wait = 60_000L
    private var now = 1_000_000L

    /** MediaStore in memory, with the system trash. */
    private class FakeSource(val volume: String) : MediaSource {
        var generation = 0L
        val files = mutableMapOf<Long, ScanRow>()

        fun put(row: ScanRow) {
            files[row.mediaStoreId] = row.copy(generationModified = ++generation)
        }

        fun trash(id: Long, trashed: Boolean) = put(files.getValue(id).copy(isTrashed = trashed))

        override fun volumes() = setOf(volume)

        override fun version(volume: String) = "v1"

        override fun generation(volume: String) = generation

        override fun rows(volume: String, sinceGeneration: Long?) =
            files.values.filter { it.generationModified > (sinceGeneration ?: -1) }

        override fun ids(volume: String) = files.keys.toSet()
    }

    /** The phone's trash and delete dialogs; each request is one batch. */
    private inner class FakePhone : PhoneTrash {
        var manageMedia = false
        val requests = mutableListOf<Pair<String, List<PhoneFile>>>()

        override fun canDeleteWithoutAsking() = manageMedia

        override fun trashRequests(files: List<PhoneFile>, trashed: Boolean) =
            batch(if (trashed) "trash" else "untrash", files)

        override fun deleteRequests(files: List<PhoneFile>) = batch("delete", files)

        private fun batch(
            kind: String,
            files: List<PhoneFile>,
        ): List<Pair<List<PhoneFile>, IntentSender>> {
            if (files.isEmpty()) return emptyList()
            requests += kind to files
            return listOf(files to sender())
        }

        private fun sender(): IntentSender =
            PendingIntent.getActivity(context, 0, Intent(), PendingIntent.FLAG_IMMUTABLE)
                .intentSender
    }

    private val db =
        Room.inMemoryDatabaseBuilder<SettleDatabase>(context)
            .setDriver(AndroidSQLiteDriver())
            .build()
    private val source = FakeSource(volume)
    private val drive = FakeDrive()
    private val phone = FakePhone()
    private val target = FakeRestoreTarget()
    private val trashStore = TrashStore(db.trashDao())
    private val preferences = BackupPreferences(db.backupDao())
    private var restoreStarted = 0

    private val sync =
        LibrarySync(
            source,
            db.libraryDao(),
            hasFullAccess = { true },
            now = { now },
            trashRules = { plan, nowMillis ->
                val ids = plan.missing + plan.updates.map { it.itemId }
                afterScan(
                    plan,
                    trashStore.items(ids).associateBy { it.itemId },
                    preferences.current().externalDeletion,
                    nowMillis,
                    wait,
                )
            },
        )

    private val deletion =
        Deletion(
            db.trashDao(),
            trashStore,
            phone,
            preferences,
            betweenScans = { sync.betweenScans(it) },
            reschedule = {},
            startRestore = { restoreStarted++ > -1 },
            clock = { now },
        )

    private val work =
        TrashWork(
            db.trashDao(),
            trashStore,
            DriveChanges(drive, trashStore, { now }),
            RestoreDownload(
                drive,
                target,
                RoomUploadStore(db.backupDao()),
                trashStore,
                1024,
                jitterMillis = { 0 },
            ),
            preferences,
            betweenScans = { sync.betweenScans(it) },
            clock = { now },
        )

    @After fun tearDown() = db.close()

    private fun row(id: Long) =
        ScanRow(
            volume = volume,
            mediaStoreId = id,
            relativePath = "DCIM/Camera/",
            displayName = "IMG_$id.jpg",
            mimeType = "image/jpeg",
            kind = MediaKind.IMAGE,
            sizeBytes = 2_000,
            dateTakenMillis = 1_700_000_000_000 + id,
            dateModifiedSeconds = 5,
            width = 1,
            height = 1,
            orientation = 0,
            durationMillis = 0,
            specialFormat = 0,
            isFavorite = false,
            isTrashed = false,
            generationModified = 0,
        )

    /** Items on the phone, each backed up and verified in Drive as `drive<id>`. */
    private suspend fun backedUp(vararg mediaStoreIds: Long): List<String> {
        mediaStoreIds.forEach { source.put(row(it)) }
        sync.scan()
        return mediaStoreIds.map { msId ->
            val itemId =
                checkNotNull(
                    db.trashDao().phoneFiles(allIds()).first { it.mediaStoreId == msId }.id
                )
            val bytes = Random(msId).nextBytes(2_000)
            drive.files["drive$msId"] =
                FakeDrive.StoredFile("drive$msId", "IMG_$msId.jpg", null, false, bytes)
            RoomUploadStore(db.backupDao())
                .save(
                    UploadRecord(
                        itemId = checkNotNull(MediaItemId.parseOrNull(itemId)),
                        driveFileId = "drive$msId",
                        state = CloudState.VERIFIED,
                        snapshot = FileSnapshot(2_000, 5),
                        confirmedBytes = 2_000,
                        sha256 = sha256(bytes),
                        md5 = md5(bytes),
                    )
                )
            itemId
        }
    }

    private suspend fun allIds() = db.libraryDao().knownFiles(volume).map { it.itemId }

    private suspend fun item(id: String) =
        trashStore.items(listOf(checkNotNull(MediaItemId.parseOrNull(id)))).singleOrNull()

    /** The user confirms every system dialog, and the system does what it confirmed. */
    private suspend fun DeletionRequest.confirm(apply: () -> Unit = {}) {
        apply()
        steps.forEach { it.complete(approved = true) }
    }

    @Test
    fun deletingMovesThePhoneFileAndEveryDriveCopyToTheTrash() = runTest {
        val (id) = backedUp(1)
        db.backupDao().addSuperseded(SupersededFileEntity("old1", id))
        drive.files["old1"] = FakeDrive.StoredFile("old1", "IMG_1.jpg", null, false)

        val request = deletion.delete(listOf(id))
        assertEquals(
            listOf("trash" to listOf(1L)),
            phone.requests.map { (k, f) -> k to f.map { it.mediaStoreId } },
        )
        request.confirm { source.trash(1, true) }
        work.runDriveChanges()
        sync.scan()

        assertEquals(LocalState.TRASHED, item(id)?.localState)
        assertEquals(TrashOrigin.SETTLE, item(id)?.entry?.origin)
        assertEquals(setOf("drive1", "old1"), drive.trashed)
        assertEquals(emptyList<Any>(), db.libraryDao().timeline().first())
        assertEquals(listOf(id), deletion.trash.first().map { it.id })
    }

    @Test
    fun aDeclinedDialogChangesNothing() = runTest {
        val (id) = backedUp(1)

        deletion.delete(listOf(id)).steps.single().complete(approved = false)
        work.runDriveChanges()

        assertEquals(LocalState.PRESENT, item(id)?.localState)
        assertNull(item(id)?.entry)
        assertTrue(drive.trashed.isEmpty())
    }

    @Test
    fun undoBringsItBackOnThePhoneAndInDrive() = runTest {
        val (id) = backedUp(1)
        deletion.delete(listOf(id)).confirm { source.trash(1, true) }
        work.runDriveChanges()

        deletion.restore(listOf(id)).confirm { source.trash(1, false) }
        work.runDriveChanges()
        sync.scan()

        assertEquals(LocalState.PRESENT, item(id)?.localState)
        assertNull(item(id)?.entry)
        assertTrue(drive.trashed.isEmpty())
        assertEquals(1, db.libraryDao().timeline().first().size)
    }

    @Test
    fun undoBeforeDriveHeardOfTheDeletionLeavesDriveAlone() = runTest {
        val (id) = backedUp(1)
        deletion.delete(listOf(id)).confirm { source.trash(1, true) }
        deletion.restore(listOf(id)).confirm { source.trash(1, false) }

        work.runDriveChanges()

        assertTrue(drive.trashed.isEmpty())
        assertNull(db.trashDao().nextOpDueAt())
    }

    @Test
    fun aDeletionElsewhereReachesDriveOnlyAfterTheWait() = runTest {
        val (id) = backedUp(1)
        source.files.remove(1)

        sync.scan()
        work.runDriveChanges()
        assertEquals(TrashOrigin.ELSEWHERE, item(id)?.entry?.origin)
        assertTrue(drive.trashed.isEmpty())

        now += wait
        work.runDriveChanges()
        assertEquals(setOf("drive1"), drive.trashed)
    }

    @Test
    fun aFileThatComesBackBeforeTheWaitIsNeverTrashedInDrive() = runTest {
        val (id) = backedUp(1)
        val file = source.files.remove(1)!!
        sync.scan()

        source.put(file.copy(mediaStoreId = 2))
        sync.scan()
        now += wait
        work.runDriveChanges()

        assertEquals(LocalState.PRESENT, item(id)?.localState)
        assertNull(item(id)?.entry)
        assertTrue(drive.trashed.isEmpty())
    }

    @Test
    fun withAskNothingHappensInDriveUntilTheUserDecides() = runTest {
        preferences.update { it.copy(externalDeletion = ExternalDeletionPolicy.ASK) }
        val (id) = backedUp(1)
        source.files.remove(1)
        sync.scan()
        now += wait
        work.runDriveChanges()

        assertTrue(drive.trashed.isEmpty())
        assertEquals(listOf(id), deletion.undecided.first().map { it.id })

        deletion.decide(listOf(id), VanishedDecision.MOVE_DRIVE_COPIES_TO_TRASH)
        work.runDriveChanges()
        assertEquals(setOf("drive1"), drive.trashed)
        assertEquals(emptyList<Any>(), deletion.undecided.first())
    }

    @Test
    fun aMassDisappearanceTouchesNothingInDriveUntilAnswered() = runTest {
        val ids = backedUp(*(1L..250L).toList().toLongArray())
        source.files.clear()

        sync.scan()
        now += wait
        work.runDriveChanges()
        assertTrue(drive.trashed.isEmpty())
        assertEquals(250, deletion.breakerCount.first())

        deletion.decide(null, VanishedDecision.KEEP_DRIVE_COPIES)
        work.runDriveChanges()
        assertTrue(drive.trashed.isEmpty())
        assertEquals(0, deletion.breakerCount.first())
        assertEquals(ids.size, allIds().size)
    }

    @Test
    fun restoringSomethingDeletedElsewhereDownloadsTheVerifiedCopy() = runTest {
        val (id) = backedUp(1)
        source.files.remove(1)
        sync.scan()
        now += wait
        work.runDriveChanges()

        val request = deletion.restore(listOf(id))
        assertEquals(0, request.steps.size)
        assertEquals(1, restoreStarted)
        work.runRestores()

        val restored = checkNotNull(item(id))
        assertEquals(LocalState.PRESENT, restored.localState)
        assertNull(restored.entry)
        assertTrue(drive.trashed.isEmpty())
        val (_, bytes) = target.published.getValue(checkNotNull(restored.mediaStoreId))
        assertEquals(sha256(drive.files.getValue("drive1").bytes), sha256(bytes))
        assertEquals(emptyList<Any>(), deletion.trash.first())
    }

    @Test
    fun deletingForeverRemovesThePhoneFileAndEveryDriveFile() = runTest {
        val (id) = backedUp(1)
        db.backupDao().addSuperseded(SupersededFileEntity("old1", id))
        drive.files["old1"] = FakeDrive.StoredFile("old1", "IMG_1.jpg", null, false)
        deletion.delete(listOf(id)).confirm { source.trash(1, true) }
        sync.scan()

        deletion.emptyTrash().confirm { source.files.remove(1) }
        work.runDriveChanges()
        sync.scan()

        assertEquals("delete", phone.requests.last().first)
        assertNull(item(id))
        assertFalse("drive1" in drive.files || "old1" in drive.files)
        assertEquals(emptyList<String>(), allIds())
    }

    @Test
    fun deletingForeverNeverTouchesItemsOutsideTheTrash() = runTest {
        val (id) = backedUp(1)

        val request = deletion.deleteForever(listOf(id))
        work.runDriveChanges()

        assertEquals(0, request.steps.size)
        assertEquals(LocalState.PRESENT, item(id)?.localState)
        assertTrue("drive1" in drive.files)
    }

    @Test
    fun anUploadThatLandsAfterTheItemWasDeletedIsTrashedToo() = runTest {
        val (id) = backedUp(1)
        deletion.delete(listOf(id)).confirm { source.trash(1, true) }
        work.runDriveChanges()
        drive.trashed.clear()

        deletion.uploadEnded(id)
        work.runDriveChanges()

        assertEquals(setOf("drive1"), drive.trashed)
    }

    @Test
    fun anItemIsForgottenOnceItsTrashTimeIsOverAndThePhoneDeletedIt() = runTest {
        val (id) = backedUp(1)
        deletion.delete(listOf(id)).confirm { source.trash(1, true) }
        work.runDriveChanges()

        now += TRASH_RETENTION_MILLIS
        work.expire()
        assertEquals(LocalState.TRASHED, item(id)?.localState)

        source.files.remove(1)
        sync.scan()
        work.expire()
        assertNull(item(id))
    }

    @Test
    fun theDeletedItemsDriveCopyStaysQueuedWhileOffline() = runTest {
        val (id) = backedUp(1)
        drive.failNext("setTrashed", com.iltan.settle.domain.backup.DriveFailure.TRANSIENT)
        deletion.delete(listOf(id)).confirm { source.trash(1, true) }

        work.runDriveChanges()
        assertTrue(drive.trashed.isEmpty())
        assertEquals(DriveOpKind.TRASH, trashStore.due(Long.MAX_VALUE).single().kind)

        now += 60_000
        work.runDriveChanges()
        assertEquals(setOf("drive1"), drive.trashed)
    }
}
