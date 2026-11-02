package com.iltan.settle.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.iltan.settle.data.backup.BackupChoices
import com.iltan.settle.data.db.FolderSummary
import com.iltan.settle.data.system.SystemState
import com.iltan.settle.domain.backup.BackupPause
import com.iltan.settle.domain.backup.BackupSettings
import com.iltan.settle.domain.backup.RunProgress
import com.iltan.settle.domain.scan.MediaKind
import com.iltan.settle.domain.transfer.NetworkUse
import com.iltan.settle.ui.theme.SettleTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class BackupPageTest {

    @get:Rule val compose = createComposeRule()

    private val calls = mutableListOf<String>()

    private val running =
        BackupSettingsState(
            loaded = true,
            backedUp = 580,
            waiting = 12,
            queued = 3,
            needsAttention = 1,
            folders = listOf(folder("DCIM/Camera/", backedUp = 2), folder("DCIM/Screenshots/")),
        )

    private val actions =
        BackupActions(
            setAutomatic = { calls += "automatic $it" },
            setGrace = { calls += "grace $it" },
            setNetworkUse = { calls += "network $it" },
            setPaused = { calls += "paused $it" },
            backUpEverythingNow = { calls += "everything" },
            retryAfterFull = { calls += "retry" },
            connect = { calls += "connect" },
            openAttention = { calls += "attention" },
            openFolders = { calls += "folders" },
        )

    private fun show(state: BackupSettingsState) = compose.setContent {
        SettleTheme {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                BackupPage(state = state, debugOptions = false, actions = actions)
            }
        }
    }

    @Test
    fun withoutDriveItOnlyOffersToConnect() {
        show(BackupSettingsState(loaded = true, pause = BackupPause.NOT_CONNECTED))

        compose.onNodeWithText("Backup is off").assertIsDisplayed()
        compose.onNodeWithText("Back up automatically").assertDoesNotExist()
        compose.onNodeWithText("Connect").performClick()
        assertEquals(listOf("connect"), calls)
    }

    @Test
    fun theStatusCountsWhatIsBackedUpAndWaitingAndListsAttention() {
        show(running)

        compose.onNodeWithText("580 backed up · 15 waiting").assertIsDisplayed()
        compose.onNodeWithText("1 needs attention").performClick()
        compose.onNodeWithText("Start").performClick()
        assertEquals(listOf("attention", "everything"), calls)
    }

    @Test
    fun theDefaultsAreAutomaticOnTheChargerAfterADayAndPolite() {
        show(running)

        compose.onNodeWithText("Only while charging").assertIsDisplayed()
        compose.onNodeWithText("24 hours").assertIsDisplayed()
        compose.onNodeWithText("Off: waits for Wi-Fi").assertIsDisplayed()
        compose.onNodeWithText("Polite: leaves room for others on your network").assertIsDisplayed()
    }

    @Test
    fun choosingAWaitAndANetworkLimitInTheirSheetsAppliesThemAtOnce() {
        show(running)

        compose.onNodeWithText("Wait before backing up").performClick()
        compose.onNodeWithText("Recommended").assertIsDisplayed()
        compose.onNodeWithText("3 days").performClick()
        compose.onNodeWithText("Network use").performScrollTo().performClick()
        compose.onNodeWithText("Up to 20 Mbps").performClick()

        assertEquals(
            listOf("grace ${3 * 24 * 3_600_000L}", "network ${NetworkUse.Limit(2_500_000)}"),
            calls,
        )
    }

    @Test
    fun turningAutomaticBackupOffLeavesOutItsWaitAndTheCharger() {
        show(running.copy(choices = BackupChoices(BackupSettings(automatic = false))))

        compose.onNodeWithText("Wait before backing up").assertDoesNotExist()
        compose.onNodeWithText("Only while charging").assertDoesNotExist()
        compose.onNodeWithText("Back up automatically").performClick()
        assertEquals(listOf("automatic true"), calls)
    }

    @Test
    fun theFoldersRowNamesWhatBacksUp() {
        show(running)

        compose.onNodeWithText("Camera").performScrollTo().performClick()
        assertEquals(listOf("folders"), calls)
    }

    @Test
    fun aPauseByTheUserOffersResumeAndDimsBackingUpEverything() {
        show(
            running.copy(
                pause = BackupPause.BY_USER,
                needsAttention = 0,
                choices = BackupChoices(settings = BackupSettings(paused = true)),
            )
        )

        compose.onNodeWithText("Backup paused by you").assertIsDisplayed()
        compose.onNodeWithText("Resume backup first").assertIsDisplayed()
        compose.onNodeWithText("Start").assertIsNotEnabled()
        compose.onNodeWithText("Pause backup").assertDoesNotExist()
        compose.onNodeWithText("Resume").performClick()
        assertEquals(listOf("paused false"), calls)
    }

    @Test
    fun pausingIsAnActionAtTheEnd() {
        show(running)

        compose.onNodeWithText("Pause").performScrollTo().performClick()
        assertEquals(listOf("paused true"), calls)
    }

    @Test
    fun aFullDriveOffersToTryAgain() {
        show(running.copy(pause = BackupPause.QUOTA_FULL))

        compose.onNodeWithText("Paused: Google Drive is full").assertIsDisplayed()
        compose.onNodeWithText("Try again").performClick()
        assertEquals(listOf("retry"), calls)
    }

    @Test
    fun whileARunGoesItSaysHowFarAndOffersNothingToStart() {
        show(running.copy(run = RunProgress(3, 10, 0, 0, null)))

        compose.onNodeWithText("Backing up 3 of 10").assertIsDisplayed()
        compose.onNodeWithText("Back up everything now").assertDoesNotExist()
    }

    @Test
    fun turningAFolderOnAppliesAndTurningOneOffAsksFirst() {
        compose.setContent {
            SettleTheme {
                Column {
                    BackupFoldersPage(running.folders) { f, on -> calls += "${f.relativePath} $on" }
                }
            }
        }

        compose.onNodeWithText("Screenshots").performClick()
        compose.onNodeWithText("Camera").performClick()
        compose.onNodeWithText("Stop backing up Camera?").assertIsDisplayed()
        assertEquals(listOf("DCIM/Screenshots/ true"), calls)

        compose.onNode(hasText("Stop backing up") and hasAnyAncestor(isDialog())).performClick()
        assertEquals(listOf("DCIM/Screenshots/ true", "DCIM/Camera/ false"), calls)
    }

    @Test
    fun theSetupStepsShowOnlyWhatIsStillNeeded() {
        compose.setContent {
            SettleTheme {
                Column {
                    KeepRunningPage(
                        SystemState(batteryUnrestricted = false, samsung = true),
                        KeepRunningActions(
                            openAppInfo = { calls += "app info" },
                            openNeverSleeping = { calls += "never sleeping" },
                        ),
                    )
                }
            }
        }

        compose.onNodeWithText("Allow notifications").assertDoesNotExist()
        compose.onNodeWithText("Allow").performClick()
        compose.onNodeWithText("Open settings").performClick()
        assertEquals(listOf("app info", "never sleeping"), calls)
    }

    private fun folder(path: String, backUp: Boolean? = null, backedUp: Int = 0) =
        FolderSummary(
            relativePath = path,
            itemCount = 3,
            latestTakenAtMs = 0,
            hiddenFromTimeline = false,
            coverKind = MediaKind.IMAGE,
            coverVolume = "external_primary",
            coverMediaStoreId = 1,
            coverDateModifiedS = 0,
            backUp = backUp,
            backedUpCount = backedUp,
        )
}
