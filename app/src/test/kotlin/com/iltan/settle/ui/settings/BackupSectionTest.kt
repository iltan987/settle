package com.iltan.settle.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.iltan.settle.data.backup.BackupChoices
import com.iltan.settle.data.db.FolderSummary
import com.iltan.settle.domain.backup.BackupPause
import com.iltan.settle.domain.backup.BackupSettings
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
class BackupSectionTest {

    @get:Rule val compose = createComposeRule()

    private val calls = mutableListOf<String>()

    private fun folder(path: String, backUp: Boolean? = null) =
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
        )

    private val running =
        BackupSettingsState(
            loaded = true,
            backedUp = 580,
            waiting = 12,
            queued = 3,
            needsAttention = 1,
            folders = listOf(folder("DCIM/Camera/"), folder("DCIM/Screenshots/")),
        )

    private fun show(state: BackupSettingsState, system: SystemState = SystemState()) =
        compose.setContent {
            SettleTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    BackupSection(
                        state = state,
                        system = system,
                        debugOptions = false,
                        actions =
                            BackupActions(
                                setAutomatic = { calls += "automatic $it" },
                                setGrace = { calls += "grace $it" },
                                setNetworkUse = { calls += "network $it" },
                                setPaused = { calls += "paused $it" },
                                setFolderBackUp = { f, on -> calls += "${f.relativePath} $on" },
                                backUpEverythingNow = { calls += "everything" },
                                retryAfterFull = { calls += "retry" },
                                openNeverSleeping = { calls += "never sleeping" },
                                openAppInfo = { calls += "app info" },
                            ),
                    )
                }
            }
        }

    @Test
    fun withoutDriveItOnlyAsksToConnect() {
        show(BackupSettingsState(loaded = true, pause = BackupPause.NOT_CONNECTED))

        compose.onNodeWithText("Connect Google Drive above to back up").assertIsDisplayed()
        compose.onNodeWithText("Back up automatically").assertDoesNotExist()
    }

    @Test
    fun theSummaryCountsWhatIsBackedUpReadyAndWaiting() {
        show(running)

        compose.onNodeWithText("580 backed up · 3 ready · 12 waiting").assertIsDisplayed()
        compose.onNodeWithText("1 item needs attention").assertIsDisplayed()
        compose.onNodeWithText("Back up everything now").performClick()
        assertEquals(listOf("everything"), calls)
    }

    @Test
    fun theDefaultsAreAutomaticOnTheChargerAfterADayAndPolite() {
        show(running)

        compose.onNodeWithText("Only while charging").assertIsDisplayed()
        compose.onNodeWithText("24 hours").assertIsDisplayed()
        compose.onNodeWithText("Polite: leaves room for others on your network").assertIsDisplayed()
    }

    @Test
    fun choosingAGracePeriodAndANetworkLimit() {
        show(running)

        compose.onNodeWithText("Wait before backing up").performClick()
        compose.onNodeWithText("3 days").performClick()
        compose.onNodeWithText("Network use").performScrollTo().performClick()
        compose.onNodeWithText("Up to 20 Mbps").performClick()

        assertEquals(
            listOf("grace ${3 * 24 * 3_600_000L}", "network ${NetworkUse.Limit(2_500_000)}"),
            calls,
        )
    }

    @Test
    fun onlyTheCameraFolderIsChosenByDefault() {
        show(running)

        compose.onNodeWithText("1 folder chosen").performScrollTo().performClick()
        compose.onNodeWithText("Screenshots").performScrollTo().performClick()

        assertEquals(listOf("DCIM/Screenshots/ true"), calls)
    }

    @Test
    fun aPausedBackupOffersToResume() {
        show(
            running.copy(
                pause = BackupPause.BY_USER,
                choices = BackupChoices(settings = BackupSettings(paused = true)),
            )
        )

        compose.onNodeWithText("Backup is paused").assertIsDisplayed()
        compose.onNodeWithText("Back up everything now").assertDoesNotExist()
        compose.onNodeWithText("Resume").performClick()
        assertEquals(listOf("paused false"), calls)
    }

    @Test
    fun aFullDriveOffersToTryAgain() {
        show(running.copy(pause = BackupPause.QUOTA_FULL))

        compose.onNodeWithText("Your Google Drive is full").assertIsDisplayed()
        compose.onNodeWithText("Back up again").performClick()
        assertEquals(listOf("retry"), calls)
    }

    @Test
    fun theReliabilitySetupShowsOnlyWhatIsStillNeeded() {
        show(running, SystemState(batteryUnrestricted = true, samsung = false))
        compose.onNodeWithText("Allow unrestricted battery use").assertDoesNotExist()
        compose.onNodeWithText("Add Settle to Never sleeping apps").assertDoesNotExist()
    }

    @Test
    fun onSamsungItLinksToNeverSleepingAppsAndBattery() {
        show(running, SystemState(batteryUnrestricted = false, samsung = true))

        compose.onNodeWithText("Allow unrestricted battery use").performScrollTo().performClick()
        compose.onNodeWithText("Add Settle to Never sleeping apps").performScrollTo().performClick()

        assertEquals(listOf("app info", "never sleeping"), calls)
    }
}
