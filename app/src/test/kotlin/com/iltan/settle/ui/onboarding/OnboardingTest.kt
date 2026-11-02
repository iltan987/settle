package com.iltan.settle.ui.onboarding

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.iltan.settle.data.media.MediaAccess
import com.iltan.settle.data.system.SystemState
import com.iltan.settle.ui.settings.KeepRunningActions
import com.iltan.settle.ui.settings.SetupStep
import com.iltan.settle.ui.settings.openSetupSteps
import com.iltan.settle.ui.theme.SettleTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class OnboardingTest {

    @get:Rule val compose = createComposeRule()

    private val calls = mutableListOf<String>()

    private fun step(
        access: MediaAccess = MediaAccess.FULL,
        done: Boolean = false,
        denied: Boolean = false,
        connected: Boolean = false,
        connectSkipped: Boolean = false,
        setupNeeded: Boolean = false,
        setupLeft: Boolean = false,
    ) =
        onboardingStep(
            access,
            done,
            denied,
            connected,
            connectSkipped,
            setupNeeded,
            setupLeft,
        )

    @Test
    fun photoAccessComesFirstAndIsNeverSkipped() {
        assertEquals(OnboardingStep.WELCOME, step(access = MediaAccess.NONE))
        assertEquals(OnboardingStep.BLOCKED, step(access = MediaAccess.NONE, denied = true))
        assertEquals(OnboardingStep.PARTIAL, step(access = MediaAccess.PARTIAL))
        // Taken away after onboarding: the access screen takes the timeline's place.
        assertEquals(OnboardingStep.BLOCKED, step(access = MediaAccess.NONE, done = true))
        assertEquals(OnboardingStep.PARTIAL, step(access = MediaAccess.PARTIAL, done = true))
    }

    @Test
    fun driveIsOfferedOnceThenTheSetupStepsOnlyWhileNeeded() {
        assertEquals(OnboardingStep.CONNECT, step())
        assertNull(step(connectSkipped = true, setupNeeded = true))
        assertEquals(OnboardingStep.SETUP, step(connected = true, setupNeeded = true))
        assertNull(step(connected = true, setupNeeded = false))
        assertNull(step(connected = true, setupNeeded = true, setupLeft = true))
        assertNull(step(done = true))
    }

    @Test
    fun samsungsStepIsNeededOnlyUntilBatteryUseIsUnrestricted() {
        // One UI leaves Unrestricted apps out of Never sleeping apps; they can't be put to sleep.
        assertEquals(
            listOf(SetupStep.BATTERY, SetupStep.NEVER_SLEEPING),
            SystemState(batteryUnrestricted = false, samsung = true).openSetupSteps,
        )
        assertEquals(emptyList<SetupStep>(), SystemState(samsung = true).openSetupSteps)
        assertEquals(
            listOf(SetupStep.BATTERY),
            SystemState(batteryUnrestricted = false).openSetupSteps,
        )
    }

    @Test
    fun theBlockedScreenGivesAndroidsWayBack() {
        compose.setContent { SettleTheme { AccessBlockedScreen { calls += "settings" } } }

        compose.onNodeWithText("Settle can’t see your photos").assertIsDisplayed()
        compose.onNodeWithText("Photos and videos").assertIsDisplayed()
        compose.onNodeWithText("Open Android settings").performClick()
        assertEquals(listOf("settings"), calls)
    }

    @Test
    fun connectingStatesThePromisesAndSaysWhenDriveWasRefused() {
        compose.setContent {
            SettleTheme {
                ConnectDriveScreen(
                    connecting = false,
                    problem = "Settle needs permission to create its folder in Drive.",
                    onConnect = { calls += "connect" },
                    onNotNow = { calls += "not now" },
                )
            }
        }

        compose.onNodeWithText("Waits before it uploads").assertIsDisplayed()
        compose.onNodeWithText("Delete once, everywhere").assertIsDisplayed()
        compose
            .onNodeWithText("Settle needs permission to create its folder in Drive.")
            .assertIsDisplayed()
        compose.onNodeWithText("Connect Google Drive").performClick()
        compose.onNodeWithText("Not now").performClick()
        assertEquals(listOf("connect", "not now"), calls)
    }

    @Test
    fun aDoneStepStaysInPlaceCheckedAndSamsungsTrustsTheUser() {
        var system by
            mutableStateOf(
                SystemState(
                    notificationsAllowed = false,
                    batteryUnrestricted = false,
                    samsung = true,
                )
            )
        compose.setContent {
            SettleTheme {
                var neverSleeping by remember { mutableStateOf(false) }
                SetupScreen(
                    email = "test@example.com",
                    shown = listOf(SetupStep.NOTIFICATIONS, SetupStep.NEVER_SLEEPING),
                    system = system,
                    neverSleepingDone = neverSleeping,
                    actions =
                        KeepRunningActions(
                            allowNotifications = { calls += "notifications" },
                            openNeverSleeping = { calls += "never sleeping" },
                        ),
                    onNeverSleepingDone = { neverSleeping = true },
                    onFinish = { calls += "finish" },
                )
            }
        }

        compose.onNodeWithText("Drive connected · test@example.com").assertIsDisplayed()
        compose.onNodeWithText("Allow").performClick()
        system = system.copy(notificationsAllowed = true)
        // Still listed, now checked.
        compose.onNodeWithText("Notifications").assertIsDisplayed()
        // Its "Done", and the Done button.
        compose.onAllNodesWithText("Done").assertCountEquals(2)

        compose.onNodeWithText("Open settings").performClick()
        compose.onNodeWithText("Open battery settings").performClick()
        compose.onNodeWithText("I’ve done this").performClick()
        compose.onNodeWithText("Open settings").assertDoesNotExist()

        compose.onNodeWithText("Skip").performClick()
        assertEquals(listOf("notifications", "never sleeping", "finish"), calls)
    }
}
