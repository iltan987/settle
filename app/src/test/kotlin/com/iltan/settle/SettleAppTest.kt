package com.iltan.settle

import android.Manifest
import android.app.Application
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.iltan.settle.ui.SettleApp
import com.iltan.settle.ui.theme.SettleTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class SettleAppTest {

    @get:Rule val compose = createComposeRule()

    private fun grant(vararg permissions: String) =
        shadowOf(ApplicationProvider.getApplicationContext<Application>())
            .grantPermissions(*permissions)

    private fun grantFullAccess() {
        grant(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        // Onboarding ran once before; these tests start in the app itself.
        runBlocking {
            ApplicationProvider.getApplicationContext<Application>()
                .appGraph
                .uiPreferences
                .onboardingDone()
        }
    }

    /**
     * Shows the app once the gate has read Settle's records, which Room does off the main thread.
     */
    private fun showApp() {
        compose.setContent { SettleTheme { SettleApp() } }
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasClickAction()).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun withoutAccessOnboardingWelcomesAndAsksForPhotos() {
        showApp()

        compose.onNodeWithText("Your phone and your Drive as one library").assertIsDisplayed()
        compose.onNodeWithText("Allow access to photos").assertIsDisplayed()
    }

    @Test
    fun partialAccessAsksForAll() {
        grant(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        showApp()

        compose.onNodeWithText("Settle sees only the photos you picked").assertIsDisplayed()
        compose.onNodeWithText("Allow all").assertIsDisplayed()
    }

    @Test
    fun withAccessOnboardingOffersDriveOnceAndNotNowOpensTheTimeline() {
        grant(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        showApp()

        compose.onNodeWithText("Back up to your own Google Drive").assertIsDisplayed()
        compose.onNodeWithText("Not now").performClick()

        compose.onNodeWithContentDescription("Settings").assertIsDisplayed()
        compose.waitUntil {
            runBlocking {
                ApplicationProvider.getApplicationContext<Application>()
                    .appGraph
                    .uiPreferences
                    .prefs
                    .first()
                    .onboardingDone
            }
        }
    }

    @Test
    fun withFullAccessStartsOnTimeline() {
        grantFullAccess()
        showApp()

        // The account button in the timeline's top bar opens Settings.
        compose.onNodeWithContentDescription("Settings").assertIsDisplayed()
    }

    @Test
    fun bottomBarSwitchesBetweenPhotosAndFolders() {
        grantFullAccess()
        showApp()

        compose.onNodeWithText("Folders").performClick()
        compose.onNodeWithText("Favorites").assertIsDisplayed()

        compose.onNodeWithText("Photos").performClick()
        compose.onNodeWithContentDescription("Settings").assertIsDisplayed()
    }

    @Test
    fun theBackupTabSaysBackupIsOffUntilDriveIsConnected() {
        grantFullAccess()
        showApp()

        compose.onNodeWithText("Backup").performClick()

        compose.onNodeWithText("Backup is off").assertIsDisplayed()
    }

    @Test
    fun settingsOpensItsLanguagePageAndNavigatesBack() {
        grantFullAccess()
        showApp()

        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithText("Google Drive").assertIsDisplayed()
        compose.onNodeWithText("Not connected").assertIsDisplayed()
        // Below the library, Drive, backup and deletion groups.
        compose.onNodeWithText("Language").performScrollTo().performClick()
        compose.onNodeWithText("Türkçe").assertIsDisplayed()
        // English is a row of its own, and the system's language under System default.
        compose.onNodeWithText("System default").assertIsDisplayed()
        compose.onAllNodesWithText("English").assertCountEquals(2)

        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Settings").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithContentDescription("Settings").assertIsDisplayed()
    }
}
