package com.iltan.settle

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.iltan.settle.ui.SettleApp
import com.iltan.settle.ui.theme.SettleTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SettleAppTest {

    @get:Rule val compose = createComposeRule()

    @Test
    fun startsOnEmptyTimeline() {
        compose.setContent { SettleTheme { SettleApp() } }

        compose.onNodeWithText("Photos").assertIsDisplayed()
        compose.onNodeWithText("Nothing here yet").assertIsDisplayed()
    }

    @Test
    fun settingsListsLanguagesAndNavigatesBack() {
        compose.setContent { SettleTheme { SettleApp() } }

        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithText("Language").assertIsDisplayed()
        compose.onNodeWithText("System default").assertIsDisplayed()
        compose.onNodeWithText("English").assertIsDisplayed()
        compose.onNodeWithText("Türkçe").assertIsDisplayed()

        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Nothing here yet").assertIsDisplayed()
    }
}
