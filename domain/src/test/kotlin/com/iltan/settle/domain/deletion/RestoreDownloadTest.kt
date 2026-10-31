package com.iltan.settle.domain.deletion

import com.iltan.settle.domain.CloudState
import com.iltan.settle.domain.MediaItemId
import com.iltan.settle.domain.backup.BackupItem
import com.iltan.settle.domain.backup.DriveFailure
import com.iltan.settle.domain.backup.FakeDrive
import com.iltan.settle.domain.backup.FakeUploadStore
import com.iltan.settle.domain.backup.FileSnapshot
import com.iltan.settle.domain.backup.UploadRecord
import com.iltan.settle.domain.backup.md5
import com.iltan.settle.domain.backup.sha256
import com.iltan.settle.domain.scan.MediaKind
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.random.Random
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class RestoreDownloadTest {

    private val drive = FakeDrive()
    private val target = FakeRestoreTarget()
    private val uploads = FakeUploadStore()
    private val downloads = FakeDownloadStore()
    private var next = 0L

    private fun restorer() =
        RestoreDownload(drive, target, uploads, downloads, bufferBytes = 1024, jitterMillis = { 0 })

    /** An item backed up and verified in Drive, whose phone file is gone. */
    private fun backedUp(
        size: Int = 10_000,
        state: CloudState = CloudState.VERIFIED,
    ): Pair<MediaItemId, ByteArray> {
        val id = MediaItemId.generate(nowMillis = ++next)
        val bytes = Random(next).nextBytes(size)
        val fileId = "file$next"
        drive.files[fileId] = FakeDrive.StoredFile(fileId, "IMG.jpg", null, false, bytes)
        uploads.items[id] =
            BackupItem(id, MediaKind.IMAGE, "IMG.jpg", "image/jpeg", "DCIM/Camera/", 1, 1, 1, 0)
        uploads.records[id] =
            UploadRecord(
                itemId = id,
                driveFileId = fileId,
                state = state,
                snapshot = FileSnapshot(size.toLong(), dateModifiedSeconds = 5),
                confirmedBytes = size.toLong(),
                sha256 = sha256(bytes),
                md5 = md5(bytes),
            )
        return id to bytes
    }

    /**
     * The invariant: a published file always holds exactly the backed-up bytes, and nothing is
     * published otherwise.
     */
    private fun assertPublishedMatchBackups() {
        for ((item, bytes) in target.published.values) {
            sha256(bytes) shouldBe uploads.records.getValue(item.id).sha256
        }
    }

    @Test
    fun `downloads the verified copy into the original folder and publishes it`() = runTest {
        val (id, bytes) = backedUp()

        val result = restorer().restore(id).shouldBeInstanceOf<RestoreResult.Restored>()

        val (item, published) = target.published.getValue(result.file.mediaStoreId)
        published shouldBe bytes
        item.relativePath shouldBe "DCIM/Camera/"
        downloads.handles.keys.shouldBeEmpty()
        assertPublishedMatchBackups()
    }

    @Test
    fun `the record takes the restored file's snapshot so no new upload follows`() = runTest {
        val (id, _) = backedUp()
        target.publishedModifiedSeconds = 777

        restorer().restore(id)

        uploads.records.getValue(id).snapshot shouldBe FileSnapshot(10_000, 777)
        uploads.records.getValue(id).state shouldBe CloudState.VERIFIED
    }

    @Test
    fun `a dropped connection resumes where the file stopped`() = runTest {
        val (id, bytes) = backedUp()
        drive.cutDownloadAfter = 4_000

        restorer().restore(id).shouldBeInstanceOf<RestoreResult.Restored>()

        drive.downloadOffsets shouldContainExactly listOf(0L, 4_000L)
        target.published.values.single().second shouldBe bytes
    }

    @Test
    fun `a killed download resumes from the bytes written, without downloading them again`() =
        runTest {
            val (id, bytes) = backedUp()
            drive.cutDownloadAfter = 3_000
            // Killed: the first attempt never gets to retry.
            val killed =
                RestoreDownload(
                    drive,
                    target,
                    uploads,
                    downloads,
                    1024,
                    maxRetries = 0,
                    jitterMillis = { 0 },
                )
            killed.restore(id) shouldBe RestoreResult.Stopped(RestoreProblem.OFFLINE)
            downloads.handles.keys shouldContainExactly listOf(id)

            restorer().restore(id).shouldBeInstanceOf<RestoreResult.Restored>()

            drive.downloadOffsets shouldContainExactly listOf(0L, 3_000L)
            target.published.values.single().second shouldBe bytes
        }

    @Test
    fun `different bytes in Drive are never published`() = runTest {
        val (id, bytes) = backedUp()
        val fileId = uploads.records.getValue(id).driveFileId
        val other = bytes.copyOf().also { it[0] = (it[0] + 1).toByte() }
        drive.files[fileId] = FakeDrive.StoredFile(fileId, "IMG.jpg", null, false, other)

        restorer().restore(id) shouldBe RestoreResult.Stopped(RestoreProblem.CHECKSUM_MISMATCH)

        target.published.size shouldBe 0
        target.pending.size shouldBe 0
        downloads.handles.keys.shouldBeEmpty()
    }

    @Test
    fun `a longer file in Drive is never published`() = runTest {
        val (id, bytes) = backedUp()
        val fileId = uploads.records.getValue(id).driveFileId
        drive.files[fileId] = FakeDrive.StoredFile(fileId, "IMG.jpg", null, false, bytes + 1)

        restorer().restore(id) shouldBe RestoreResult.Stopped(RestoreProblem.CHECKSUM_MISMATCH)
        target.published.size shouldBe 0
    }

    @Test
    fun `an item without a verified copy is not restored`() = runTest {
        val (id, _) = backedUp(state = CloudState.UPLOADED)

        restorer().restore(id) shouldBe RestoreResult.Stopped(RestoreProblem.NOT_IN_DRIVE)
        drive.downloadCalls shouldBe 0
    }

    @Test
    fun `a copy Drive no longer has stops and leaves nothing behind`() = runTest {
        val (id, _) = backedUp()
        drive.files.clear()

        restorer().restore(id) shouldBe RestoreResult.Stopped(RestoreProblem.NOT_IN_DRIVE)
        target.pending.size shouldBe 0
    }

    @Test
    fun `a full phone stops the download`() = runTest {
        val (id, _) = backedUp()
        target.spaceLeft = 2_000

        restorer().restore(id) shouldBe RestoreResult.Stopped(RestoreProblem.NOT_WRITABLE)
        target.published.size shouldBe 0
    }

    @Test
    fun `sign-in trouble stops the download`() = runTest {
        val (id, _) = backedUp()
        drive.failNext("download", DriveFailure.AUTH_NEEDED)

        restorer().restore(id) shouldBe RestoreResult.Stopped(RestoreProblem.AUTH_NEEDED)
    }

    @Test
    fun `a network that stays down gives up after the retries`() = runTest {
        val (id, _) = backedUp()
        drive.failNext("download", DriveFailure.TRANSIENT, times = 7)

        restorer().restore(id) shouldBe RestoreResult.Stopped(RestoreProblem.OFFLINE)
    }

    @Test
    fun `a pending file the system dropped is started again`() = runTest {
        val (id, bytes) = backedUp()
        downloads.handles[id] = "gone"

        restorer().restore(id).shouldBeInstanceOf<RestoreResult.Restored>()
        target.published.values.single().second shouldBe bytes
    }
}
