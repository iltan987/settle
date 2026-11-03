package com.iltan.settle.ui.viewer

import com.iltan.settle.data.backup.UploadProgress
import com.iltan.settle.data.db.UploadEntity
import com.iltan.settle.domain.CloudState
import com.iltan.settle.domain.backup.BackupDecision
import com.iltan.settle.domain.backup.BackupPause
import com.iltan.settle.domain.backup.UploadProblem
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CloudStatusTest {

    private fun upload(state: CloudState, problem: UploadProblem? = null) =
        UploadEntity(
            itemId = "01ITEM",
            driveFileId = "drive1",
            state = state,
            sizeBytes = 100,
            dateModifiedS = 1,
            sessionUri = null,
            sessionStartedAtMs = null,
            confirmedBytes = 0,
            sha256 = null,
            md5 = null,
            verifiedAtMs = null,
            problem = problem,
        )

    @Test
    fun aRunningUploadShowsItsProgress() {
        assertEquals(
            CloudStatus.Uploading(0.25f),
            cloudStatus(true, upload(CloudState.UPLOADING), UploadProgress(25, 100)),
        )
        assertEquals(CloudStatus.Uploading(null), cloudStatus(true, null, UploadProgress(0, 0)))
    }

    @Test
    fun aSentUploadShowsDriveCheckingItUntilDriveAnswers() {
        assertEquals(
            CloudStatus.Checking,
            cloudStatus(true, upload(CloudState.UPLOADING), UploadProgress(100, 100)),
        )
    }

    @Test
    fun aVerifiedCopyIsBackedUpEvenWhenDisconnected() {
        assertEquals(
            CloudStatus.BackedUp("drive1"),
            cloudStatus(false, upload(CloudState.VERIFIED), null),
        )
    }

    @Test
    fun withoutDriveNothingElseCanHappen() {
        assertEquals(CloudStatus.NotConnected, cloudStatus(false, null, null))
        assertEquals(
            CloudStatus.NotConnected,
            cloudStatus(false, upload(CloudState.UPLOADING, UploadProblem.QUOTA_FULL), null),
        )
    }

    @Test
    fun aStoppedUploadShowsWhy() {
        assertEquals(
            CloudStatus.Problem(UploadProblem.QUOTA_FULL),
            cloudStatus(true, upload(CloudState.UPLOADING, UploadProblem.QUOTA_FULL), null),
        )
        assertEquals(
            CloudStatus.Problem(UploadProblem.CHECKSUM_MISMATCH),
            cloudStatus(true, upload(CloudState.SUSPECT, UploadProblem.CHECKSUM_MISMATCH), null),
        )
    }

    @Test
    fun uploadedButUnconfirmedIsChecking() {
        assertEquals(CloudStatus.Checking, cloudStatus(true, upload(CloudState.UPLOADED), null))
        assertEquals(CloudStatus.NotBackedUp, cloudStatus(true, upload(CloudState.RESERVED), null))
        assertEquals(CloudStatus.NotBackedUp, cloudStatus(true, null, null))
    }

    @Test
    fun automaticBackupsDecisionShowsAsWaitingQueuedOrNotChosen() {
        assertEquals(
            CloudStatus.Waiting(5_000),
            cloudStatus(true, null, null, decision = BackupDecision.WaitUntil(5_000)),
        )
        assertEquals(
            CloudStatus.Queued,
            cloudStatus(true, null, null, decision = BackupDecision.Ready),
        )
        assertEquals(
            CloudStatus.NotChosen,
            cloudStatus(true, null, null, decision = BackupDecision.Skip),
        )
    }

    @Test
    fun aRequestShowsAsUnderWayUntilVerified() {
        assertEquals(CloudStatus.Uploading(null), cloudStatus(true, null, null, requested = true))
        assertEquals(
            CloudStatus.BackedUp("drive1"),
            cloudStatus(true, upload(CloudState.VERIFIED), null, requested = true),
        )
    }

    @Test
    fun aRequestWithoutARunWaitsForTheNextAutomaticBackup() {
        assertEquals(
            CloudStatus.Queued,
            cloudStatus(true, null, null, requested = true, running = false),
        )
    }

    @Test
    fun whileBackupWaitsForTheUserARequestShowsWhyInsteadOfASpinner() {
        for (reason in
            listOf(
                BackupPause.BY_USER,
                BackupPause.AUTH_NEEDED,
                BackupPause.QUOTA_FULL,
                BackupPause.NO_ORIGINAL_ACCESS,
            )) {
            assertEquals(
                CloudStatus.Paused(reason),
                cloudStatus(true, null, null, requested = true, pause = reason),
            )
        }
        assertEquals(
            CloudStatus.NotConnected,
            cloudStatus(true, null, null, requested = true, pause = BackupPause.NOT_CONNECTED),
        )
        assertEquals(
            CloudStatus.BackedUp("drive1"),
            cloudStatus(true, upload(CloudState.VERIFIED), null, pause = BackupPause.BY_USER),
        )
    }
}
