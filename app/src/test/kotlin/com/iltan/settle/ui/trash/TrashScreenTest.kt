package com.iltan.settle.ui.trash

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.iltan.settle.data.db.TrashedEntry
import com.iltan.settle.data.db.VanishedEntry
import com.iltan.settle.domain.CloudState
import com.iltan.settle.domain.LocalState
import com.iltan.settle.domain.deletion.RestoreProblem
import com.iltan.settle.domain.deletion.TrashOrigin
import com.iltan.settle.domain.deletion.VanishedReason
import com.iltan.settle.domain.scan.MediaKind
import com.iltan.settle.ui.theme.SettleTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TrashScreenTest {

    @get:Rule val compose = createComposeRule()

    private val day = 24 * 60 * 60 * 1000L
    private val now = 100 * day

    private fun entry(
        id: String,
        expiresInDays: Long = 12,
        origin: TrashOrigin? = TrashOrigin.SETTLE,
        onPhone: Boolean = true,
        restoring: Boolean = false,
        problem: RestoreProblem? = null,
    ) =
        TrashedEntry(
            id = id,
            kind = MediaKind.IMAGE,
            mimeType = "image/jpeg",
            takenAtMs = 0,
            durationMs = 0,
            volume = "external_primary",
            mediaStoreId = if (onPhone) id.hashCode().toLong() else null,
            dateModifiedS = 0,
            localState = if (onPhone) LocalState.TRASHED else LocalState.MISSING,
            cloudState = CloudState.VERIFIED,
            origin = origin,
            trashedAtMs = now - day,
            expiresAtMs = now + expiresInDays * day - 1,
            restoreRequestedAtMs = if (restoring || problem != null) now else null,
            restoreProblem = problem,
        )

    private val restored = mutableListOf<Set<String>>()
    private val deletedForever = mutableListOf<Set<String>>()
    private var emptied = false
    private var moved = false

    private fun show(state: TrashState) = compose.setContent {
        SettleTheme {
            TrashScreen(
                state = state,
                nowMillis = now,
                onBack = {},
                onRestore = { restored += it },
                onDeleteForever = { deletedForever += it },
                onEmpty = { emptied = true },
                onMoveDriveCopies = { moved = true },
                onKeepDriveCopies = {},
            )
        }
    }

    @Test
    fun itemsShowTheirDaysLeftWhereTheyWereDeletedAndRestoring() {
        show(
            TrashState(
                items =
                    listOf(
                        entry("a", expiresInDays = 12),
                        entry(
                            "b",
                            expiresInDays = 1,
                            origin = TrashOrigin.ELSEWHERE,
                            onPhone = false,
                        ),
                        entry("c", restoring = true),
                        entry("d", problem = RestoreProblem.NOT_WRITABLE),
                    ),
                loading = false,
            )
        )

        compose.onNodeWithText("12 days").assertIsDisplayed()
        compose.onNodeWithText("1 day").assertIsDisplayed()
        compose.onNodeWithContentDescription("Deleted in another app").assertIsDisplayed()
        compose.onNodeWithText("Restoring").assertIsDisplayed()
        compose.onNodeWithText("Phone full").assertIsDisplayed()
    }

    @Test
    fun aSelectionCanBeRestored() {
        show(TrashState(items = listOf(entry("a"), entry("b")), loading = false))

        compose.onNodeWithTag("trash-a").performClick()
        compose.onNodeWithText("1 selected").assertIsDisplayed()
        compose.onNodeWithContentDescription("Restore").performClick()

        assertEquals(listOf(setOf("a")), restored)
        compose.onNodeWithText("Trash").assertIsDisplayed()
    }

    @Test
    fun deletingForeverAsksFirstAndSaysItCantBeUndone() {
        show(TrashState(items = listOf(entry("a"), entry("b")), loading = false))
        compose.onNodeWithTag("trash-a").performClick()
        compose.onNodeWithTag("trash-b").performClick()

        compose.onNodeWithContentDescription("Delete forever").performClick()
        assertTrue(deletedForever.isEmpty())
        compose.onNodeWithText("Delete 2 items forever?").assertIsDisplayed()
        compose
            .onNodeWithText(
                "They'll be removed from this phone and from Google Drive. This can't be undone."
            )
            .assertIsDisplayed()
        compose.onNodeWithText("Delete forever").performClick()

        assertEquals(listOf(setOf("a", "b")), deletedForever)
    }

    @Test
    fun cancellingTheConfirmationDeletesNothing() {
        show(TrashState(items = listOf(entry("a")), loading = false))

        compose.onNodeWithText("Empty trash").performClick()
        compose.onNodeWithText("Cancel").performClick()

        assertTrue(!emptied && deletedForever.isEmpty())
    }

    @Test
    fun emptyingTheTrashAsksFirst() {
        show(TrashState(items = listOf(entry("a"), entry("b"), entry("c")), loading = false))

        compose.onNodeWithText("Empty trash").performClick()
        compose.onNodeWithText("Delete 3 items forever?").assertIsDisplayed()
        compose.onNodeWithText("Delete forever").performClick()

        assertTrue(emptied)
    }

    @Test
    fun itemsDeletedElsewhereWaitForADecision() {
        show(
            TrashState(
                undecided =
                    listOf(
                        VanishedEntry("x", MediaKind.IMAGE, 0, "IMG_1.jpg", VanishedReason.ASK, 0)
                    ),
                loading = false,
            )
        )

        compose.onNodeWithText("1 item deleted in another app").assertIsDisplayed()
        compose.onNodeWithText("IMG_1.jpg").assertIsDisplayed()
        compose.onNodeWithText("Move Drive copies to trash").performClick()

        assertTrue(moved)
        compose.onNodeWithText("The trash is empty").assertIsDisplayed()
    }
}
