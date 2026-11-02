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
import com.iltan.settle.data.system.SystemState
import com.iltan.settle.domain.backup.BackupPause
import com.iltan.settle.domain.deletion.ExternalDeletionPolicy
import com.iltan.settle.ui.theme.SettleTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class SettingsMainPageTest {

    @get:Rule val compose = createComposeRule()

    private val opened = mutableListOf<Any>()

    private val connected =
        DriveSettingsState(loaded = true, email = "me@example.com", folder = "Settle")

    private val healthy = BackupSettingsState(loaded = true, backedUp = 256, waiting = 3)

    private fun show(
        drive: DriveSettingsState = connected,
        backup: BackupSettingsState = healthy,
        system: SystemState = SystemState(),
        canDeleteWithoutAsking: Boolean = true,
        library: LibraryState = LibraryState(602, null, scanning = false),
    ) = compose.setContent {
        SettleTheme {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                SettingsMainPage(
                    library = library,
                    drive = drive,
                    backup = backup,
                    policy = ExternalDeletionPolicy.MOVE_DRIVE_COPY_TO_TRASH,
                    canDeleteWithoutAsking = canDeleteWithoutAsking,
                    system = system,
                    language = AppLanguage.SYSTEM,
                    version = "1.0.0",
                    actions =
                        SettingsActions(
                            rescan = { opened += "rescan" },
                            open = { opened += it },
                            allowMediaManagement = { opened += "media" },
                        ),
                )
            }
        }
    }

    @Test
    fun eachRowSaysItsCurrentValue() {
        show()

        compose.onNodeWithText("602 photos and videos on this phone").assertIsDisplayed()
        compose.onNodeWithText("256 backed up · 3 waiting").assertIsDisplayed()
        compose.onNodeWithText("Next backup tonight, on Wi-Fi and the charger").assertIsDisplayed()
        compose.onNodeWithText("Move the Drive copy to the trash").performScrollTo()
        compose.onNodeWithText("System default").performScrollTo()
        compose.onNodeWithText("1.0.0").performScrollTo()
    }

    @Test
    fun theSummaryOpensItsPage() {
        show()

        compose.onNodeWithText("256 backed up · 3 waiting").performClick()
        compose.onNodeWithText("When you delete in another app").performScrollTo().performClick()
        compose.onNodeWithText("Language").performScrollTo().performClick()

        assertEquals(
            listOf(SettingsSection.BACKUP, SettingsSection.DELETION, SettingsSection.LANGUAGE),
            opened,
        )
    }

    @Test
    fun rescanningShowsTheCheckInPlace() {
        show(library = LibraryState(602, null, scanning = true))

        compose.onNodeWithText("Checking…").assertIsDisplayed()
    }

    @Test
    fun withoutDriveBackupSaysItIsOff() {
        show(
            drive = DriveSettingsState(loaded = true, folder = "Settle"),
            backup = BackupSettingsState(loaded = true, pause = BackupPause.NOT_CONNECTED),
        )

        compose.onNodeWithText("Backup is off").assertIsDisplayed()
        compose.onNodeWithText("Connect Google Drive to start").assertIsDisplayed()
    }

    @Test
    fun aPauseSaysWhyAndHowManyWait() {
        show(backup = healthy.copy(pause = BackupPause.AUTH_NEEDED, waiting = 9))

        compose.onNodeWithText("Backup paused").assertIsDisplayed()
        compose.onNodeWithText("Waiting for you to sign in · 9 waiting").assertIsDisplayed()
    }

    @Test
    fun rowsThatAreNotNeededAreLeftOut() {
        show(system = SystemState(), canDeleteWithoutAsking = true)

        compose.onNodeWithText("Delete without asking each time").assertDoesNotExist()
        compose.onNodeWithText("Keep backup running").assertDoesNotExist()
    }

    @Test
    fun openSetupStepsAndThePermissionShowUntilDone() {
        show(system = SystemState(batteryUnrestricted = false), canDeleteWithoutAsking = false)

        compose.onNodeWithText("Allow").performScrollTo().performClick()
        compose.onNodeWithText("1 step left").performScrollTo().performClick()

        assertEquals(listOf("media", SettingsSection.KEEP_RUNNING), opened)
    }
}
