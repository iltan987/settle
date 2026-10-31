package com.iltan.settle.ui.viewer

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import com.iltan.settle.data.db.UploadEntity
import com.iltan.settle.data.drive.UploadProgress
import com.iltan.settle.domain.CloudState
import com.iltan.settle.domain.backup.UploadProblem
import com.iltan.settle.ui.theme.SettleTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CloudStatusTest {

    @get:Rule val compose = createComposeRule()

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
    fun theButtonSaysWhatTappingDoes() {
        var status: CloudStatus by mutableStateOf(CloudStatus.NotBackedUp)
        var taps = 0
        compose.setContent { SettleTheme { CloudButton(status) { taps++ } } }

        compose.onNodeWithContentDescription("Back up now").performClick()
        status = CloudStatus.Uploading(0.5f)
        compose.onNodeWithContentDescription("Uploading to Google Drive").assertExists()
        status = CloudStatus.BackedUp("drive1")
        compose.onNodeWithContentDescription("Backed up to Google Drive").assertExists()
        status = CloudStatus.Problem(UploadProblem.OFFLINE)
        compose.onNodeWithContentDescription("Back up again").performClick()

        assertEquals(2, taps)
    }
}
