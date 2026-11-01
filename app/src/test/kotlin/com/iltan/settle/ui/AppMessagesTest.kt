package com.iltan.settle.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.iltan.settle.ui.theme.SettleMotion
import com.iltan.settle.ui.theme.SettleTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AppMessagesTest {

    @get:Rule val compose = createComposeRule()

    private var screenShown by mutableStateOf(true)
    private var undone = 0

    /** A root with the app's host, and a "screen" that offers Undo once and can go away. */
    private fun show() = compose.setContent {
        SettleTheme(dynamicColor = false) {
            val scope = rememberCoroutineScope()
            val messages = remember { AppMessages(SnackbarHostState(), scope) }
            CompositionLocalProvider(LocalAppMessages provides messages) {
                Box {
                    if (screenShown) Screen()
                    AppSnackbarHost(messages, tabBarOffset = null)
                }
            }
        }
    }

    @Composable
    private fun Screen() {
        val messages = LocalAppMessages.current
        LaunchedEffect(Unit) { messages.offer("Moved to the trash", "Undo") { undone++ } }
    }

    @Test
    fun undoOutlivesTheScreenThatOfferedIt() {
        // The countdown is an animation; the clock moves only when the test says so.
        compose.mainClock.autoAdvance = false
        show()
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithText("Moved to the trash").assertIsDisplayed()

        screenShown = false
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithText("Undo").performClick()
        compose.mainClock.advanceTimeBy(1_000)

        assertEquals(1, undone)
        compose.onNodeWithText("Moved to the trash").assertDoesNotExist()
    }

    @Test
    fun theOfferEndsAfterTenSecondsButNotWhileTouched() {
        compose.mainClock.autoAdvance = false
        show()
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("Moved to the trash").assertIsDisplayed()

        compose.onNodeWithText("Moved to the trash").performTouchInput { down(center) }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(SettleMotion.UNDO_MILLIS + 1_000)
        compose.onNodeWithText("Moved to the trash").assertIsDisplayed()

        compose.onNodeWithText("Moved to the trash").performTouchInput { up() }
        // Input reaches the app at the next sync, not with the clock.
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(SettleMotion.UNDO_MILLIS + 1_000)
        compose.onNodeWithText("Moved to the trash").assertDoesNotExist()
        assertEquals(0, undone)
    }
}
