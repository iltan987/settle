package com.iltan.settle.domain.backup

import com.iltan.settle.domain.CloudState
import com.iltan.settle.domain.MediaItemId
import com.iltan.settle.domain.scan.MediaKind
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.ZoneOffset
import kotlin.random.Random
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class BackupEngineTest {

    private val drive = FakeDrive()
    private val originals = FakeOriginals()
    private val store = FakeUploadStore()
    private var now = 1_000L
    private var nextItem = 0L

    /** 2026-10-15 in UTC. */
    private val october = 1_792_000_000_000L
    /** 2026-11-15 in UTC. */
    private val november = october + 31L * 24 * 60 * 60 * 1000

    private val chunk = CHUNK_GRANULARITY

    private fun engine(chunkBytes: Int = chunk, folderLock: Mutex = Mutex()) =
        BackupEngine(
            drive = drive,
            originals = originals,
            store = store,
            clock = { now },
            rootFolder = "Settle (debug)",
            zone = ZoneOffset.UTC,
            chunkBytes = chunkBytes,
            jitterMillis = { 0 },
            folderLock = folderLock,
        )

    private fun item(size: Int, takenAt: Long = october, name: String = "IMG.jpg"): MediaItemId {
        val id = MediaItemId.generate(nowMillis = ++nextItem)
        originals.bytes[id] = Random(nextItem).nextBytes(size)
        store.items[id] =
            BackupItem(
                id = id,
                kind = MediaKind.IMAGE,
                displayName = name,
                mimeType = "image/jpeg",
                relativePath = "DCIM/Camera/",
                takenAtMillis = takenAt,
                width = 4000,
                height = 3000,
                specialFormat = 0,
            )
        return id
    }

    private class Killed : RuntimeException("process killed")

    /** Throws [Killed] before the server accepts the chunk at [offset] or later. */
    private fun killAt(offset: Long) {
        drive.beforeChunk = {
            if (it >= offset) {
                drive.beforeChunk = {}
                throw Killed()
            }
        }
    }

    private fun stored(id: MediaItemId): FakeDrive.StoredFile =
        drive.files.getValue(store.records.getValue(id).driveFileId)

    /**
     * ADR-002/004 invariant after every scenario: a VERIFIED record points at a Drive file whose
     * bytes are exactly the phone's.
     */
    @AfterEach
    fun verifiedMeansIdenticalBytes() {
        store.records.values
            .filter { it.state == CloudState.VERIFIED }
            .forEach { record ->
                val original = originals.bytes.getValue(record.itemId)
                val copy = drive.files.getValue(record.driveFileId).bytes
                sha256(copy) shouldBe sha256(original)
                record.sha256 shouldBe sha256(original)
                record.md5 shouldBe md5(original)
            }
    }

    @Test
    fun uploadsRunningAtOnceCreateEachFolderAndFileOnce() = runTest {
        drive.interleave = true
        // Two months, so new folders are needed while other uploads are under way.
        val ids =
            List(12) { item(2 * chunk + it, takenAt = if (it % 2 == 0) october else november) }
        val queue = Channel<MediaItemId>(Channel.UNLIMITED)
        ids.forEach(queue::trySend)
        queue.close()
        val lock = Mutex()

        val results = coroutineScope {
            List(4) {
                    async {
                        val engine = engine(folderLock = lock)
                        buildList { for (id in queue) add(engine.backUp(id)) }
                    }
                }
                .awaitAll()
                .flatten()
        }

        results.forEach { it.shouldBeInstanceOf<BackupResult.Verified>() }
        drive.mediaFiles shouldHaveSize 12
        drive.folders.map { "${drive.pathOf(it)}/${it.name}".trimStart('/') }.sorted() shouldBe
            listOf(
                "Settle (debug)",
                "Settle (debug)/media",
                "Settle (debug)/media/2026",
                "Settle (debug)/media/2026/10",
                "Settle (debug)/media/2026/11",
            )
    }

    @Test
    fun aSmallFileIsUploadedInOneChunkAndVerified() = runTest {
        val id = item(100_000, name = "PXL_1.jpg")

        val result = engine().backUp(id)

        result shouldBe BackupResult.Verified(store.records.getValue(id).driveFileId)
        store.records.getValue(id).state shouldBe CloudState.VERIFIED
        drive.chunkCalls shouldBe 1
        val file = stored(id)
        file.name shouldBe "PXL_1.jpg"
        drive.pathOf(file) shouldBe "Settle (debug)/media/2026/10"
        file.appProperties["st.id"] shouldBe id.value
    }

    @Test
    fun aLargeFileIsSentInChunksWithEveryByteOnce() = runTest {
        val id = item(4 * chunk + 123)
        val progress = mutableListOf<Long>()

        engine().backUp(id) { sent, _ -> progress += sent }

        drive.chunkCalls shouldBe 5
        drive.bytesSent shouldBe 4L * chunk + 123
        progress.last() shouldBe 4L * chunk + 123
        progress shouldBe progress.sorted()
    }

    @Test
    fun anUploadKilledHalfwayResumesWithoutResendingConfirmedBytes() = runTest {
        val id = item(4 * chunk + 10)
        killAt(2L * chunk)
        assertThrows<Killed> { engine().backUp(id) }
        store.records.getValue(id).state shouldBe CloudState.UPLOADING

        // A new process: a new engine over the same persisted state.
        val result = engine().backUp(id)

        result.shouldBeInstanceOf<BackupResult.Verified>()
        drive.bytesSent shouldBe 4L * chunk + 10
        drive.startCalls shouldBe 1
        drive.mediaFiles shouldHaveSize 1
    }

    @Test
    fun theNextChunkStartsWhereTheServerStoppedNotWhereTheAppDid() = runTest {
        val id = item(3 * chunk)
        drive.keepOfNextChunk = 1_000

        engine().backUp(id).shouldBeInstanceOf<BackupResult.Verified>()

        drive.bytesSent shouldBe 4L * chunk - 1_000
    }

    @Test
    fun anExpiredSessionStartsOverWithTheSameReservedFile() = runTest {
        val id = item(3 * chunk)
        killAt(chunk.toLong())
        assertThrows<Killed> { engine().backUp(id) }
        val reserved = store.records.getValue(id).driveFileId
        drive.expireSessions()

        engine().backUp(id) shouldBe BackupResult.Verified(reserved)

        drive.startCalls shouldBe 2
        drive.mediaFiles shouldHaveSize 1
    }

    @Test
    fun aSessionOlderThanSixDaysIsReplacedBeforeDriveExpiresIt() = runTest {
        val id = item(3 * chunk)
        killAt(chunk.toLong())
        assertThrows<Killed> { engine().backUp(id) }
        now += SESSION_MAX_AGE_MILLIS + 1

        engine().backUp(id).shouldBeInstanceOf<BackupResult.Verified>()

        drive.startCalls shouldBe 2
        drive.mediaFiles shouldHaveSize 1
    }

    @Test
    fun aLostAnswerToTheLastChunkIsRecoveredFromTheSessionStatus() = runTest {
        val id = item(2 * chunk)
        drive.loseNextAnswer("uploadChunk")
        drive.loseNextAnswer("uploadChunk")

        engine().backUp(id).shouldBeInstanceOf<BackupResult.Verified>()

        drive.mediaFiles shouldHaveSize 1
    }

    @Test
    fun aFileCreatedByAnEarlierTryIsVerifiedInsteadOfDuplicated() = runTest {
        val id = item(2 * chunk)
        // Drive completes the file, the app is killed before hearing so, and a week passes.
        drive.afterChunk = { if (it == chunk.toLong()) throw Killed() }
        assertThrows<Killed> { engine().backUp(id) }
        drive.afterChunk = {}
        drive.expireSessions()
        val reserved = store.records.getValue(id).driveFileId

        // The new session with the same ID gets a 409; the existing file holds the right bytes.
        engine().backUp(id) shouldBe BackupResult.Verified(reserved)

        drive.mediaFiles shouldHaveSize 1
        store.superseded shouldBe emptyMap()
    }

    @Test
    fun aConflictingFileWithOtherBytesIsLeftAloneAndANewFileReserved() = runTest {
        val id = item(chunk)
        val snapshot = originals.snapshot(id)!!
        store.records[id] = UploadRecord(id, "taken", CloudState.RESERVED, snapshot)
        drive.files["taken"] =
            FakeDrive.StoredFile("taken", "other.jpg", null, isFolder = false, ByteArray(10))

        val result = engine().backUp(id)

        result.shouldBeInstanceOf<BackupResult.Verified>()
        result.driveFileId shouldNotBe "taken"
        drive.files.getValue("taken").bytes shouldBe ByteArray(10)
        store.superseded shouldBe mapOf("taken" to id)
    }

    @Test
    fun aLateSha256IsWaitedFor() = runTest {
        val id = item(chunk)
        drive.sha256Delays = 2

        engine().backUp(id).shouldBeInstanceOf<BackupResult.Verified>()
    }

    @Test
    fun withoutSha256TheCopyStaysUploadedAndIsCheckedAgainWithoutReuploading() = runTest {
        val id = item(chunk)
        drive.sha256Delays = 100

        engine().backUp(id).shouldBeInstanceOf<BackupResult.AwaitingChecksum>()
        store.records.getValue(id).state shouldBe CloudState.UPLOADED

        drive.sha256Delays = 0
        engine().backUp(id).shouldBeInstanceOf<BackupResult.Verified>()
        drive.bytesSent shouldBe chunk.toLong()
    }

    @Test
    fun anUploadTriedAgainNoLongerCarriesTheProblemThatStoppedIt() = runTest {
        val id = item(3 * chunk)
        drive.failNext("uploadChunk", DriveFailure.TRANSIENT, times = 7)
        engine().backUp(id) shouldBe BackupResult.Stopped(UploadProblem.OFFLINE)
        store.records.getValue(id).problem shouldBe UploadProblem.OFFLINE

        drive.sha256Delays = 100
        engine().backUp(id).shouldBeInstanceOf<BackupResult.AwaitingChecksum>()

        store.records.getValue(id).problem shouldBe null
    }

    @Test
    fun aCopyWithDifferentBytesIsSuspectAndNeverVerified() = runTest {
        val id = item(chunk)
        drive.corruptStoredBytes = true

        engine().backUp(id) shouldBe BackupResult.Stopped(UploadProblem.CHECKSUM_MISMATCH)

        store.records.getValue(id).state shouldBe CloudState.SUSPECT
    }

    @Test
    fun anOriginalEditedDuringTheUploadIsNotVerifiedAndIsUploadedAgainNextTime() = runTest {
        val id = item(3 * chunk)
        drive.beforeChunk = { if (it > 0) originals.modifiedSeconds[id] = 2 }

        engine().backUp(id) shouldBe BackupResult.Stopped(UploadProblem.CHANGED_DURING_UPLOAD)
        store.records.getValue(id).state shouldBe CloudState.SUSPECT
        val first = store.records.getValue(id).driveFileId

        drive.beforeChunk = {}
        val result = engine().backUp(id)

        result.shouldBeInstanceOf<BackupResult.Verified>()
        result.driveFileId shouldNotBe first
        drive.files.containsKey(first) shouldBe true
        // The copy of the earlier bytes stays in Drive and is remembered for superseding.
        store.superseded shouldBe mapOf(first to id)
    }

    @Test
    fun anOriginalThatShrinksWhileUploadingStopsTheUpload() = runTest {
        val id = item(3 * chunk)
        drive.beforeChunk = {
            if (it > 0) originals.bytes[id] = originals.bytes.getValue(id).copyOf(chunk + 5)
        }

        engine().backUp(id) shouldBe BackupResult.Stopped(UploadProblem.CHANGED_DURING_UPLOAD)

        drive.mediaFiles shouldHaveSize 0
    }

    @Test
    fun aFullDriveStopsTheUploadAndKeepsItsProgress() = runTest {
        val id = item(3 * chunk)
        drive.beforeChunk = {
            if (it == 2L * chunk) {
                drive.beforeChunk = {}
                throw DriveException(DriveFailure.QUOTA_FULL)
            }
        }

        engine().backUp(id) shouldBe BackupResult.Stopped(UploadProblem.QUOTA_FULL)
        store.records.getValue(id).problem shouldBe UploadProblem.QUOTA_FULL
        store.records.getValue(id).confirmedBytes shouldBe 2L * chunk

        engine().backUp(id).shouldBeInstanceOf<BackupResult.Verified>()
        drive.bytesSent shouldBe 3L * chunk
        store.records.getValue(id).problem shouldBe null
    }

    @Test
    fun missingAuthorizationStopsBeforeAnythingIsReserved() = runTest {
        val id = item(chunk)
        drive.failNext("generateIds", DriveFailure.AUTH_NEEDED)

        engine().backUp(id) shouldBe BackupResult.Stopped(UploadProblem.AUTH_NEEDED)

        store.records.containsKey(id) shouldBe false
    }

    @Test
    fun rateLimitsAndNetworkErrorsBackOffAndThenContinue() = runTest {
        val id = item(2 * chunk)
        drive.failNext("uploadChunk", DriveFailure.RATE_LIMITED)
        drive.failNext("uploadChunk", DriveFailure.TRANSIENT, times = 2)

        engine().backUp(id).shouldBeInstanceOf<BackupResult.Verified>()

        // 1 s + 2 s + 4 s of backoff, in virtual time.
        currentTime shouldBeGreaterThanOrEqual 7_000
        currentTime shouldBeLessThan 8_000
    }

    @Test
    fun aNetworkThatNeverComesBackStopsAsOffline() = runTest {
        val id = item(chunk)
        drive.failNext("uploadChunk", DriveFailure.TRANSIENT, times = 50)

        engine().backUp(id) shouldBe BackupResult.Stopped(UploadProblem.OFFLINE)

        store.records.getValue(id).problem shouldBe UploadProblem.OFFLINE
    }

    @Test
    fun foldersAreCreatedOnceEvenWhenAnAnswerIsLost() = runTest {
        drive.loseNextAnswer("createFolder")
        val first = item(1_000)
        val second = item(1_000)
        val november = item(1_000, takenAt = october + 31L * 24 * 60 * 60 * 1000)

        listOf(first, second, november).forEach { engine().backUp(it) }

        drive.folders.map { drive.pathOf(it) + "/" + it.name }.sorted() shouldBe
            listOf(
                "/Settle (debug)",
                "Settle (debug)/media",
                "Settle (debug)/media/2026",
                "Settle (debug)/media/2026/10",
                "Settle (debug)/media/2026/11",
            )
    }

    @Test
    fun aRemovedCachedFolderIsFoundOrCreatedAgain() = runTest {
        val first = item(1_000)
        engine().backUp(first)
        val month = store.folderIds.getValue("Settle (debug)/media/2026/10")
        drive.files.remove(month)

        engine().backUp(item(1_000)).shouldBeInstanceOf<BackupResult.Verified>()

        drive.folders.count { it.name == "10" } shouldBe 1
    }

    @Test
    fun aVerifiedItemIsNotUploadedAgain() = runTest {
        val id = item(chunk)
        engine().backUp(id)

        engine().backUp(id).shouldBeInstanceOf<BackupResult.Verified>()

        drive.startCalls shouldBe 1
        drive.bytesSent shouldBe chunk.toLong()
    }

    @Test
    fun anOriginalThatIsGoneStopsWithoutTouchingDrive() = runTest {
        val id = item(chunk)
        originals.bytes.remove(id)

        engine().backUp(id) shouldBe BackupResult.Stopped(UploadProblem.ORIGINAL_UNAVAILABLE)

        drive.files shouldBe emptyMap()
    }

    @Test
    fun chunksMustBeMultiplesOf256KiB() {
        assertThrows<IllegalArgumentException> { engine(chunkBytes = 1_000_000) }
    }

    private val TestScope.currentTime: Long
        get() = testScheduler.currentTime
}
