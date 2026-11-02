package com.iltan.settle.ui.viewer

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.iltan.settle.domain.backup.BackupPause
import com.iltan.settle.domain.backup.UploadProblem
import com.iltan.settle.ui.theme.SettleTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ViewerStatusChipTest {

    @get:Rule val compose = createComposeRule()

    private var status: CloudStatus by mutableStateOf(CloudStatus.BackedUp("drive1"))
    private var chargingOnly by mutableStateOf(true)
    private val opened = mutableListOf<Unit>()
    private val actions = mutableListOf<ChipAction>()

    private fun show() {
        compose.setContent {
            SettleTheme {
                ViewerStatusChip(
                    viewerChip(status, "Screenshots", chargingOnly, NOW),
                    onClick = { opened += Unit },
                    onAction = { actions += it },
                )
            }
        }
    }

    /** Shows [status] and checks the chip's text and its one action, or that it has none. */
    private fun expect(status: CloudStatus, text: String, action: String?) {
        this.status = status
        compose.onNodeWithText(text).assertExists()
        for (label in ACTION_LABELS) {
            compose.onAllNodesWithText(label).assertCountEquals(if (label == action) 1 else 0)
        }
    }

    @Test
    fun everyStatusSaysWhereTheItemIsAndOffersOnlyWhatCanBeDone() {
        show()

        expect(CloudStatus.BackedUp("drive1"), "Backed up", null)
        expect(CloudStatus.Uploading(0.64f), "Uploading · 64%", null)
        expect(CloudStatus.Uploading(null), "Uploading", null)
        expect(CloudStatus.Waiting(NOW + 5 * HOUR), "Backs up in 5 hours", "Back up now")
        expect(CloudStatus.Queued, "Backs up tonight", "Back up now")
        expect(CloudStatus.NotChosen, "Screenshots isn't backed up", "Back up folder")
        expect(CloudStatus.NotBackedUp, "Not backed up", "Back up now")
        expect(
            CloudStatus.Problem(UploadProblem.ORIGINAL_UNAVAILABLE),
            "Couldn't read this file",
            "Details",
        )
        expect(CloudStatus.Problem(UploadProblem.OFFLINE), "Upload interrupted", "Back up again")
        expect(CloudStatus.Paused(BackupPause.BY_USER), "Backup paused by you", "Resume")
        expect(
            CloudStatus.Paused(BackupPause.AUTH_NEEDED),
            "Backup paused · sign in to Google",
            "Sign in",
        )
        expect(
            CloudStatus.Paused(BackupPause.QUOTA_FULL),
            "Backup paused · Drive is full",
            "Manage",
        )
        expect(
            CloudStatus.Paused(BackupPause.NO_ORIGINAL_ACCESS),
            "Paused · needs location access",
            "Allow",
        )
        expect(CloudStatus.NotConnected, "Not backed up · Drive not connected", "Connect")
    }

    @Test
    fun queuedSaysTonightOnlyWhenBackupWaitsForTheCharger() {
        status = CloudStatus.Queued
        chargingOnly = false
        show()

        compose.onNodeWithText("Backs up soon").assertExists()
    }

    @Test
    fun theChipOpensDetailsAndItsPillActs() {
        status = CloudStatus.Waiting(NOW + HOUR)
        show()

        compose.onNodeWithText("Backs up in 1 hour").performClick()
        compose.onNodeWithText("Back up now").performClick()

        assertEquals(1, opened.size)
        assertEquals(listOf(ChipAction.BACK_UP), actions)
    }

    private companion object {
        const val NOW = 1_800_000_000_000L
        const val HOUR = 60 * 60 * 1000L
        val ACTION_LABELS =
            listOf(
                "Back up now",
                "Back up again",
                "Back up folder",
                "Details",
                "Connect",
                "Sign in",
                "Manage",
                "Resume",
                "Allow",
            )
    }
}
