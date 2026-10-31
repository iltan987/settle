package com.iltan.settle

import android.Manifest
import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.iltan.settle.ui.SettleApp
import com.iltan.settle.ui.theme.SettleTheme
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

    private fun grantFullAccess() =
        grant(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)

    @Test
    fun withoutAccessShowsOnboarding() {
        compose.setContent { SettleTheme { SettleApp() } }

        compose.onNodeWithText("Welcome to Settle").assertIsDisplayed()
        compose.onNodeWithText("Allow access").assertIsDisplayed()
    }

    @Test
    fun partialAccessAsksForAll() {
        grant(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        compose.setContent { SettleTheme { SettleApp() } }

        compose.onNodeWithText("Settle sees only some photos").assertIsDisplayed()
        compose.onNodeWithText("Allow all").assertIsDisplayed()
    }

    @Test
    fun withFullAccessStartsOnTimeline() {
        grantFullAccess()
        compose.setContent { SettleTheme { SettleApp() } }

        // The timeline's top bar is the only place with the settings action.
        compose.onNodeWithContentDescription("Settings").assertIsDisplayed()
    }

    @Test
    fun bottomBarSwitchesBetweenPhotosAndFolders() {
        grantFullAccess()
        compose.setContent { SettleTheme { SettleApp() } }

        compose.onNodeWithText("Folders").performClick()
        compose.onNodeWithText("Favorites").assertIsDisplayed()

        compose.onNodeWithText("Photos").performClick()
        compose.onNodeWithContentDescription("Settings").assertIsDisplayed()
    }

    @Test
    fun settingsListsLanguagesAndNavigatesBack() {
        grantFullAccess()
        compose.setContent { SettleTheme { SettleApp() } }

        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithText("Google Drive").assertIsDisplayed()
        compose.onNodeWithText("Connect Google Drive").assertIsDisplayed()
        // Below the library and Drive sections.
        compose.onNodeWithText("Türkçe").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Language").assertIsDisplayed()
        compose.onNodeWithText("System default").assertIsDisplayed()
        compose.onNodeWithText("English").assertIsDisplayed()

        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithContentDescription("Settings").assertIsDisplayed()
    }
}
