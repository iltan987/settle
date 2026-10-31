package com.iltan.settle.data.backup

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.iltan.settle.data.db.BackupRequestEntity
import com.iltan.settle.data.db.DriveAccountEntity
import com.iltan.settle.data.db.ScanCursorEntity
import com.iltan.settle.data.db.SettleDatabase
import com.iltan.settle.data.drive.RoomUploadStore
import com.iltan.settle.data.drive.UploadMeter
import com.iltan.settle.domain.CloudState
import com.iltan.settle.domain.MediaItemId
import com.iltan.settle.domain.backup.BackupEngine
import com.iltan.settle.domain.backup.BackupPause
import com.iltan.settle.domain.backup.CHUNK_GRANULARITY
import com.iltan.settle.domain.backup.ChunkResult
import com.iltan.settle.domain.backup.DriveApi
import com.iltan.settle.domain.backup.DriveFailure
import com.iltan.settle.domain.backup.FakeDrive
import com.iltan.settle.domain.backup.FakeOriginals
import com.iltan.settle.domain.backup.HOUR_MILLIS
import com.iltan.settle.domain.backup.NewDriveFile
import com.iltan.settle.domain.backup.sha256
import com.iltan.settle.domain.scan.IndexedRow
import com.iltan.settle.domain.scan.MediaKind
import com.iltan.settle.domain.scan.ScanPlan
import com.iltan.settle.domain.scan.ScanRow
import com.iltan.settle.domain.transfer.PacerConfig
import java.time.ZoneOffset
import kotlin.random.Random
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class BackupRunnerTest {

    private var database: SettleDatabase? = null
    private val db: SettleDatabase
        get() = checkNotNull(database) { "Call useTestClock() first" }

    private val drive = FakeDrive().apply { interleave = true }
    private val counting = CountingDrive(drive)
    private val originals = FakeOriginals()
    private var nextId = 0L
    private val start = 1_800_000_000_000L
    private val chunk = CHUNK_GRANULARITY

    /**
     * Room runs its queries on the test's dispatcher, so database work and the fake network's
     * delays share one virtual clock and uploads overlap as they would on a phone.
     */
    private fun TestScope.useTestClock() {
        database =
            Room.inMemoryDatabaseBuilder<SettleDatabase>(
                    ApplicationProvider.getApplicationContext<Context>()
                )
                .setDriver(AndroidSQLiteDriver())
                .setQueryCoroutineContext(StandardTestDispatcher(testScheduler))
                .build()
    }

    @After fun tearDown() = database?.close() ?: Unit

    /**
     * Counts uploads between their start and their last chunk, to see how many run at once. Each
     * chunk takes a second of virtual time, as on a network.
     */
    private class CountingDrive(private val drive: FakeDrive) : DriveApi by drive {
        var active = 0
        var mostAtOnce = 0

        override suspend fun startUpload(file: NewDriveFile, sizeBytes: Long): String =
            drive.startUpload(file, sizeBytes).also {
                active++
                mostAtOnce = maxOf(mostAtOnce, active)
            }

        override suspend fun uploadChunk(
            sessionUri: String,
            offset: Long,
            buffer: ByteArray,
            length: Int,
            totalBytes: Long,
        ): ChunkResult {
            delay(1_000)
            return drive.uploadChunk(sessionUri, offset, buffer, length, totalBytes).also {
                if (it is ChunkResult.Done) active--
            }
        }
    }

    private suspend fun item(size: Int, path: String = "DCIM/Camera/"): MediaItemId {
        val id = MediaItemId.generate(nowMillis = ++nextId)
        val row =
            ScanRow(
                volume = "external_primary",
                mediaStoreId = nextId,
                relativePath = path,
                displayName = "IMG_$nextId.jpg",
                mimeType = "image/jpeg",
                kind = MediaKind.IMAGE,
                sizeBytes = size.toLong(),
                dateTakenMillis = 1_792_000_000_000,
                dateModifiedSeconds = 1,
                width = 1,
                height = 1,
                orientation = 0,
                durationMillis = 0,
                specialFormat = 0,
                isFavorite = false,
                isTrashed = false,
                generationModified = nextId,
            )
        db.libraryDao()
            .applyScan(
                ScanPlan(listOf(IndexedRow(id, row)), emptyList(), emptyList(), emptyList(), false),
                ScanCursorEntity("external_primary", "v1", nextId, 1),
                nowMs = 1,
            )
        originals.bytes[id] = Random(nextId).nextBytes(size)
        return id
    }

    private fun TestScope.runner(pacer: PacerConfig = PacerConfig()): BackupRunner {
        val dao = db.backupDao()
        val preferences = BackupPreferences(dao)
        val clock = { start + testScheduler.currentTime }
        val planner = BackupPlanner(dao, preferences, canReadOriginals = { true }, clock = clock)
        val store = RoomUploadStore(dao)
        return BackupRunner(
            dao = dao,
            planner = planner,
            preferences = preferences,
            newEngine = { lock ->
                BackupEngine(
                    drive = counting,
                    originals = originals,
                    store = store,
                    clock = clock,
                    rootFolder = "Settle (debug)",
                    zone = ZoneOffset.UTC,
                    chunkBytes = chunk,
                    jitterMillis = { 0 },
                    folderLock = lock,
                )
            },
            meter = UploadMeter(),
            measureDelay = { 40 },
            clock = clock,
            pacerConfig = pacer,
            workerDispatcher = StandardTestDispatcher(testScheduler),
        )
    }

    private fun TestScope.planner() =
        BackupPlanner(
            db.backupDao(),
            BackupPreferences(db.backupDao()),
            canReadOriginals = { true },
            clock = { start + testScheduler.currentTime },
        )

    private suspend fun TestScope.connect() {
        useTestClock()
        db.backupDao().setAccount(DriveAccountEntity(email = "owner@example.com"))
    }

    private suspend fun state(id: MediaItemId) = db.backupDao().upload(id.value)?.state

    private fun assertDriveHoldsExactly(id: MediaItemId) {
        val record = drive.files.values.single { it.appProperties["st.id"] == id.value }
        assertEquals(sha256(originals.bytes.getValue(id)), sha256(record.bytes))
    }

    @Test
    fun anAutomaticRunBacksUpEveryReadyItemOfChosenFoldersOnly() = runTest {
        connect()
        val camera = List(5) { item(2 * chunk + it) }
        val screenshot = item(100, path = "DCIM/Screenshots/")

        val end = runner().run(RunKind.AUTOMATIC)

        assertEquals(RunEnd.DONE, end)
        camera.forEach {
            assertEquals(CloudState.VERIFIED, state(it))
            assertDriveHoldsExactly(it)
        }
        assertNull(state(screenshot))
        assertEquals(5, drive.mediaFiles.size)
    }

    @Test
    fun uploadsRunSeveralAtOnceUpToThePacersSlots() = runTest {
        connect()
        List(8) { item(3 * chunk) }

        runner(PacerConfig(startSlots = 3, maxSlots = 3)).run(RunKind.AUTOMATIC)

        assertEquals(3, counting.mostAtOnce)
        assertEquals(8, drive.mediaFiles.size)
    }

    @Test
    fun aRequestedRunTakesOnlyWhatTheUserAskedFor() = runTest {
        connect()
        val asked = item(chunk + 1, path = "Pictures/WhatsApp/")
        val other = item(chunk + 1)
        db.backupDao().addRequests(listOf(BackupRequestEntity(asked.value, start)))

        runner().run(RunKind.REQUESTED)

        assertEquals(CloudState.VERIFIED, state(asked))
        assertNull(state(other))
        assertTrue(db.backupDao().backupCandidates().none { it.requestedAtMs != null })
    }

    @Test
    fun drivesBeingFullPausesBackupAsAWhole() = runTest {
        connect()
        List(4) { item(chunk) }
        drive.failNext("startUpload", DriveFailure.QUOTA_FULL)

        val end = runner(PacerConfig(startSlots = 1, maxSlots = 1)).run(RunKind.AUTOMATIC)

        assertEquals(RunEnd.PAUSED, end)
        assertEquals(BackupPause.QUOTA_FULL, BackupPreferences(db.backupDao()).current().blocked)
        assertEquals(0, drive.mediaFiles.size)
        val plan = BackupPlanner(db.backupDao(), BackupPreferences(db.backupDao()), { true }).plan()
        assertEquals(BackupPause.QUOTA_FULL, plan.pause)
    }

    @Test
    fun aFailedItemWaitsForItsRetryWhileTheOthersGoUp() = runTest {
        connect()
        val failing = item(chunk)
        val fine = List(3) { item(chunk) }
        drive.failNext("startUpload", DriveFailure.FATAL)

        val end = runner(PacerConfig(startSlots = 1, maxSlots = 1)).run(RunKind.AUTOMATIC)

        assertEquals(RunEnd.DONE, end)
        fine.forEach { assertEquals(CloudState.VERIFIED, state(it)) }
        // Whichever item started first got the failure; it waits an hour from then.
        val retries = (fine + failing).mapNotNull { db.backupDao().retry(it.value) }
        assertEquals(1, retries.size)
        assertEquals(1, retries.single().attempts)
        val retryAt = retries.single().retryAtMs!!
        assertTrue(retryAt in start + HOUR_MILLIS..start + testScheduler.currentTime + HOUR_MILLIS)
    }

    @Test
    fun aCopyAwaitingItsChecksumWaitsInsteadOfBeingTriedAgainAtOnce() = runTest {
        connect()
        val id = item(chunk)
        drive.sha256Delays = 1_000

        assertEquals(RunEnd.DONE, runner().run(RunKind.AUTOMATIC))

        assertEquals(CloudState.UPLOADED, state(id))
        val retry = db.backupDao().retry(id.value)!!
        assertEquals(1, retry.attempts)
        val wait = 15 * 60_000L
        assertTrue(retry.retryAtMs!! in start + wait..start + testScheduler.currentTime + wait)
        val plan = planner().plan()
        assertTrue(plan.queue.isEmpty())
        assertEquals(1, plan.waiting)
        assertEquals(1, drive.mediaFiles.size)
    }

    @Test
    fun aRequestAwaitingItsChecksumEndsTheUserInitiatedRun() = runTest {
        connect()
        val id = item(chunk)
        db.backupDao().addRequests(listOf(BackupRequestEntity(id.value, start)))
        drive.sha256Delays = 1_000

        assertEquals(RunEnd.DONE, runner().run(RunKind.REQUESTED))

        assertNull(planner().plan().requested)
        assertTrue(db.backupDao().backupCandidates().none { it.requestedAtMs != null })
    }

    @Test
    fun aCancelledRunLeavesEveryUploadResumableWithoutResendingConfirmedBytes() = runTest {
        connect()
        val items = List(3) { item(6 * chunk) }
        val total = items.sumOf { originals.bytes.getValue(it).size.toLong() }
        val runner = runner(PacerConfig(startSlots = 3, maxSlots = 3))
        var chunks = 0
        val run = async { runner.run(RunKind.AUTOMATIC) }
        drive.afterChunk = { if (++chunks == 7) run.cancel() }
        run.join()
        drive.afterChunk = {}
        val sentBeforeStop = drive.bytesSent
        assertTrue(drive.mediaFiles.isEmpty())

        assertEquals(RunEnd.DONE, runner.run(RunKind.AUTOMATIC))

        items.forEach {
            assertEquals(CloudState.VERIFIED, state(it))
            assertDriveHoldsExactly(it)
        }
        // At most the chunks in flight when the run stopped are sent twice.
        assertTrue(drive.bytesSent - sentBeforeStop < total - sentBeforeStop + 3L * chunk)
        assertEquals(3, drive.mediaFiles.size)
    }
}
