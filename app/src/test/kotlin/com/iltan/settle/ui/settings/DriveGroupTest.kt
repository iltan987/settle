package com.iltan.settle.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.iltan.settle.data.drive.DriveAbout
import com.iltan.settle.ui.theme.SettleTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class DriveGroupTest {

    @get:Rule val compose = createComposeRule()

    private val calls = mutableListOf<String>()

    private fun show(state: DriveSettingsState) = compose.setContent {
        SettleTheme {
            Column {
                DriveGroup(
                    state,
                    DriveActions(
                        connect = { calls += "connect" },
                        disconnect = { calls += "disconnect" },
                        openFolder = { calls += "folder $it" },
                        resetSandbox = { calls += "reset" },
                    ),
                )
            }
        }
    }

    private val connected =
        DriveSettingsState(
            loaded = true,
            email = "me@example.com",
            about = DriveAbout("me@example.com", limitBytes = 100_000_000_000, usageBytes = 0),
            folder = "Settle (debug)",
        )

    @Test
    fun notConnectedOffersToConnectOnACard() {
        show(DriveSettingsState(loaded = true, folder = "Settle"))

        compose.onNodeWithText("Not connected").assertIsDisplayed()
        compose.onNodeWithText("Disconnect").assertDoesNotExist()
        compose.onNodeWithText("Connect").performClick()

        assertEquals(listOf("connect"), calls)
    }

    @Test
    fun connectedShowsTheAccountStorageAndFolder() {
        show(connected.copy(folderId = "f1"))

        compose.onNodeWithText("me@example.com").assertIsDisplayed()
        compose.onNodeWithText("Connected").assertIsDisplayed()
        compose.onNodeWithText("of 100 GB used", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Settle (debug) · in My Drive").performClick()

        assertEquals(listOf("folder f1"), calls)
    }

    @Test
    fun beforeTheFirstBackupTheFolderIsNotThereToOpen() {
        show(connected)

        compose.onNodeWithText("first backup", substring = true).performClick()

        assertEquals(emptyList<String>(), calls)
    }

    @Test
    fun disconnectingAsksFirst() {
        show(connected)

        compose.onNodeWithText("Disconnect").performClick()
        compose.onNodeWithText("Files already in Drive stay there", substring = true).assertExists()
        assertEquals(emptyList<String>(), calls)

        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Disconnect").performClick()
        compose.onNode(hasText("Disconnect") and hasAnyAncestor(isDialog())).performClick()

        assertEquals(listOf("disconnect"), calls)
    }

    @Test
    fun theSandboxResetIsOnlyOfferedInDebugBuildsAndAsksFirst() {
        show(connected.copy(sandbox = true))

        compose.onNodeWithText("Reset debug Drive folder").performClick()
        compose.onNode(hasText("Reset") and hasAnyAncestor(isDialog())).performClick()

        assertEquals(listOf("reset"), calls)
    }

    @Test
    fun releaseBuildsHaveNoSandboxReset() {
        show(connected)

        compose.onNodeWithText("Reset debug Drive folder").assertDoesNotExist()
    }

    @Test
    fun whenGoogleNeedsANewSignInItWarnsAndOffersToSignIn() {
        show(connected.copy(needsReconnect = true))

        compose.onNodeWithText("Needs you to sign in again").assertIsDisplayed()
        compose.onNodeWithText("Sign in").performClick()

        assertEquals(listOf("connect"), calls)
    }
}
