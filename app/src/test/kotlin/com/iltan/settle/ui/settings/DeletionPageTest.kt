package com.iltan.settle.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.iltan.settle.domain.deletion.ExternalDeletionPolicy
import com.iltan.settle.ui.theme.SettleTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DeletionPageTest {

    @get:Rule val compose = createComposeRule()

    private fun show(
        canDeleteWithoutAsking: Boolean,
        onPolicy: (ExternalDeletionPolicy) -> Unit = {},
        onAllow: () -> Unit = {},
    ) = compose.setContent {
        SettleTheme {
            Column {
                DeletionPage(
                    policy = ExternalDeletionPolicy.MOVE_DRIVE_COPY_TO_TRASH,
                    canDeleteWithoutAsking = canDeleteWithoutAsking,
                    onPolicy = onPolicy,
                    onAllowDeletingWithoutAsking = onAllow,
                )
            }
        }
    }

    @Test
    fun thePolicyDefaultsToMovingTheDriveCopyAndCanAskInstead() {
        var chosen: ExternalDeletionPolicy? = null
        show(canDeleteWithoutAsking = true, onPolicy = { chosen = it })

        compose.onNodeWithText("Move the Drive copy to the trash").assertIsSelected()
        compose.onNodeWithText("Ask me first").performClick()

        assertEquals(ExternalDeletionPolicy.ASK, chosen)
    }

    @Test
    fun mediaManagementIsOfferedOnlyWhileNotGranted() {
        var asked = false
        show(canDeleteWithoutAsking = false, onAllow = { asked = true })

        compose.onNodeWithText("Delete without asking each time").assertIsDisplayed()
        compose.onNodeWithText("Allow").performClick()

        assertTrue(asked)
    }

    @Test
    fun grantedMediaManagementNeedsNoRow() {
        show(canDeleteWithoutAsking = true)

        assertEquals(
            0,
            compose
                .onAllNodes(androidx.compose.ui.test.hasText("Delete without asking each time"))
                .fetchSemanticsNodes()
                .size,
        )
    }
}
