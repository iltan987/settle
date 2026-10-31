package com.iltan.settle.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
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
class DriveSectionTest {

    @get:Rule val compose = createComposeRule()

    private val calls = mutableListOf<String>()

    private fun show(state: DriveSettingsState) = compose.setContent {
        SettleTheme {
            Column {
                DriveSection(
                    state = state,
                    onConnect = { calls += "connect" },
                    onDisconnect = { calls += "disconnect" },
                    onResetSandbox = { calls += "reset" },
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
    fun notConnectedOffersToConnect() {
        show(DriveSettingsState(loaded = true, folder = "Settle"))

        compose.onNodeWithText("Not connected").assertIsDisplayed()
        compose.onNodeWithText("Connect Google Drive").performClick()

        assertEquals(listOf("connect"), calls)
    }

    @Test
    fun connectedShowsTheAccountStorageAndFolder() {
        show(connected)

        compose.onNodeWithText("me@example.com").assertIsDisplayed()
        compose.onNodeWithText("of 100 GB used", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Folder: Settle (debug)", substring = true).assertIsDisplayed()
    }

    @Test
    fun disconnectingAsksFirst() {
        show(connected)

        compose.onNodeWithText("Disconnect").performClick()
        compose.onNodeWithText("Files already in Drive stay there", substring = true).assertExists()
        assertEquals(emptyList<String>(), calls)

        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Disconnect").performClick()
        // The dialog's button, below the list item with the same label.
        compose.onAllNodesWithText("Disconnect").onLast().performClick()

        assertEquals(listOf("disconnect"), calls)
    }

    @Test
    fun theSandboxResetIsOnlyOfferedInDebugBuildsAndAsksFirst() {
        show(connected.copy(sandbox = true))

        compose.onNodeWithText("Reset debug Drive folder").performClick()
        compose.onNodeWithText("restored for 30 days", substring = true).assertExists()
        compose.onNodeWithText("Reset").performClick()

        assertEquals(listOf("reset"), calls)
    }

    @Test
    fun releaseBuildsHaveNoSandboxReset() {
        show(connected)

        compose.onNodeWithText("Reset debug Drive folder").assertDoesNotExist()
    }

    @Test
    fun whenGoogleNeedsANewSignInItOffersToReconnect() {
        show(connected.copy(needsReconnect = true))

        compose.onNodeWithText("Reconnect").performClick()

        assertEquals(listOf("connect"), calls)
    }
}
