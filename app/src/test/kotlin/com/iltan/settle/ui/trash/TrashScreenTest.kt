package com.iltan.settle.ui.trash

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.iltan.settle.data.db.TrashedEntry
import com.iltan.settle.data.trash.RestoreProgress
import com.iltan.settle.domain.CloudState
import com.iltan.settle.domain.LocalState
import com.iltan.settle.domain.deletion.RestoreProblem
import com.iltan.settle.domain.deletion.TrashOrigin
import com.iltan.settle.domain.deletion.VanishedDecision
import com.iltan.settle.domain.deletion.VanishedReason
import com.iltan.settle.domain.scan.MediaKind
import com.iltan.settle.ui.AppMessages
import com.iltan.settle.ui.LocalAppMessages
import com.iltan.settle.ui.theme.SettleTheme
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TrashScreenTest {

    @get:Rule val compose = createComposeRule()

    private val day = 24 * 60 * 60 * 1000L
    private val now = System.currentTimeMillis()

    private fun entry(
        id: String,
        daysLeft: Int = 30,
        origin: TrashOrigin = TrashOrigin.SETTLE,
        onPhone: Boolean = true,
        backedUp: Boolean = true,
        restoring: Boolean = false,
        problem: RestoreProblem? = null,
        waiting: Boolean = false,
        name: String = "",
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
            cloudState = if (backedUp) CloudState.VERIFIED else CloudState.NONE,
            origin = origin.takeUnless { waiting },
            trashedAtMs = (now - (30 - daysLeft) * day).takeUnless { waiting },
            expiresAtMs = (now + daysLeft * day - 1).takeUnless { waiting },
            restoreRequestedAtMs = if (restoring || problem != null) now else null,
            restoreProblem = problem,
            relativePath = "DCIM/Camera/",
            sizeBytes = 2_000_000,
            waitingReason = VanishedReason.ASK.takeIf { waiting },
            driveFileId = "drive-$id".takeIf { backedUp },
            displayName = name,
        )

    private val restored = mutableListOf<List<String>>()
    private val deletedForever = mutableListOf<List<String>>()
    private val decided = mutableListOf<Pair<List<String>?, VanishedDecision>>()
    private var emptied = false

    private val actions =
        TrashActions(
            restore = { restored += it.map { e -> e.id } },
            deleteForever = { deletedForever += it.map { e -> e.id } },
            emptyTrash = { emptied = true },
            decide = { ids, _, decision, _ -> decided += ids to decision },
        )

    private fun state(vararg entries: TrashedEntry) =
        TrashState(groups = trashGroups(entries.toList(), ZoneId.systemDefault()), loading = false)

    private fun show(state: TrashState) = compose.setContent {
        val scope = rememberCoroutineScope()
        val messages = remember { AppMessages(SnackbarHostState(), scope) }
        CompositionLocalProvider(LocalAppMessages provides messages) {
            SettleTheme {
                TrashScreen(state = state, nowMillis = now, onBack = {}, actions = actions)
            }
        }
    }

    @Test
    fun itemsAreGroupedByHowTheyWereDeletedWithTheirDaysLeft() {
        show(
            state(
                entry("a"),
                entry("b", daysLeft = 2, origin = TrashOrigin.ELSEWHERE, onPhone = false),
                entry("c", backedUp = false),
            )
        )

        compose.onNodeWithText("Deleted today").assertIsDisplayed()
        compose.onNodeWithText("Deleted in another app").assertIsDisplayed()
        compose
            .onNodeWithText("Their Drive copies go to Drive's trash too", substring = true)
            .assertIsDisplayed()
        compose.onNodeWithContentDescription("Photo, 30 days left").assertIsDisplayed()
        compose.onNodeWithContentDescription("Photo, 2 days left").assertIsDisplayed()
        compose
            .onNodeWithContentDescription("Photo, Not backed up, 30 days left")
            .assertIsDisplayed()
    }

    @Test
    fun anItemOnlyInDriveIsNamedSinceThePhoneCantShowIt() {
        show(
            state(
                entry("a", name = "IMG_1.jpg"),
                entry("b", origin = TrashOrigin.ELSEWHERE, onPhone = false, name = "ph_1.png"),
            )
        )

        compose.onNodeWithText("ph_1.png").assertIsDisplayed()
        compose.onNodeWithContentDescription("Photo, ph_1.png, 30 days left").assertIsDisplayed()
        // On the phone it shows the photo itself.
        compose.onNodeWithText("IMG_1.jpg").assertDoesNotExist()
    }

    @Test
    fun aSelectionCanBeRestored() {
        show(state(entry("a"), entry("b")))

        compose.onNodeWithTag("trash-a").performClick()
        compose.onNodeWithText("1 selected").assertIsDisplayed()
        compose.onNodeWithText("Restore").performClick()

        assertEquals(listOf(listOf("a")), restored)
        compose.onNodeWithText("Trash").assertIsDisplayed()
    }

    @Test
    fun deletingForeverAsksFirstAndSaysWhatExistsNowhereElse() {
        show(state(entry("a"), entry("b", backedUp = false)))
        compose.onNodeWithTag("trash-a").performClick()
        compose.onNodeWithTag("trash-b").performClick()

        compose.onNodeWithText("Delete forever").performClick()
        assertTrue(deletedForever.isEmpty())
        compose.onNodeWithText("Delete 2 items forever?").assertIsDisplayed()
        compose
            .onNodeWithText(
                "They'll be removed from this phone and from Google Drive. This can't be undone."
            )
            .assertIsDisplayed()
        compose
            .onNodeWithText("1 of them was never backed up and exists nowhere else.")
            .assertIsDisplayed()
        compose.onNode(hasText("Delete forever") and hasAnyAncestor(isDialog())).performClick()

        assertEquals(listOf(listOf("a", "b")), deletedForever)
    }

    @Test
    fun cancellingTheConfirmationDeletesNothing() {
        show(state(entry("a")))

        compose.onNodeWithText("Empty trash").performClick()
        compose.onNodeWithText("Cancel").performClick()

        assertFalse(emptied)
        assertTrue(deletedForever.isEmpty())
    }

    @Test
    fun emptyingTheTrashNamesTheCount() {
        show(state(entry("a"), entry("b"), entry("c")))

        compose.onNodeWithText("Empty trash").performClick()
        compose.onNodeWithText("Empty the trash?").assertIsDisplayed()
        compose
            .onNodeWithText("3 items will be removed from this phone", substring = true)
            .assertIsDisplayed()
        compose.onNodeWithText("Delete forever").performClick()

        assertTrue(emptied)
    }

    @Test
    fun itemsDeletedElsewhereWaitForAnAnswerWithoutACountdown() {
        show(state(entry("w1", waiting = true), entry("w2", waiting = true), entry("a")))

        compose.onNodeWithText("2 items were deleted in another app").assertIsDisplayed()
        compose.onNodeWithText("Waiting for you").assertIsDisplayed()
        compose.onNodeWithTag("trash-w1").assertContentDescriptionEquals("Photo, Waiting for you")
        compose.onNodeWithText("Keep in Drive").performClick()

        assertEquals(listOf(listOf("w1", "w2") to VanishedDecision.KEEP_DRIVE_COPIES), decided)
    }

    @Test
    fun selectedWaitingItemsGetTheSameTwoChoices() {
        show(state(entry("w1", waiting = true), entry("w2", waiting = true)))

        compose.onNodeWithTag("trash-w2").performClick()
        compose.onNodeWithText("Move to trash").performClick()

        assertEquals(listOf(listOf("w2") to VanishedDecision.MOVE_DRIVE_COPIES_TO_TRASH), decided)
    }

    @Test
    fun aRestoreFromDriveShowsHowFarItIs() {
        val items =
            listOf(
                entry("a", origin = TrashOrigin.ELSEWHERE, onPhone = false, restoring = true),
                entry("b", origin = TrashOrigin.ELSEWHERE, onPhone = false, restoring = true),
            )
        show(
            state(*items.toTypedArray())
                .copy(
                    progress = RestoreProgress(2, 500_000, 2_000_000, "a"),
                    batch = RestoreBatch(total = 4, restored = 2, bytesLeft = 4_000_000),
                )
        )

        compose.onNodeWithText("Restoring 3 of 4", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Downloading from Drive and checking each file.").assertIsDisplayed()
        compose.onNodeWithTag("trash-b").assertContentDescriptionEquals("Photo, Restoring")
    }

    @Test
    fun aStoppedRestoreExplainsWhyAndWhatToDo() {
        show(
            state(entry("a", origin = TrashOrigin.ELSEWHERE, problem = RestoreProblem.NOT_WRITABLE))
        )

        compose.onNodeWithText("1 item couldn't be restored").assertIsDisplayed()
        compose.onNodeWithText("1 not restored").assertIsDisplayed()
        compose.onNodeWithText("See why").performClick()

        compose.onNodeWithText("Why these weren't restored").assertIsDisplayed()
        compose.onNodeWithText("Phone is full").assertIsDisplayed()
        compose.onNodeWithText("Free up space").assertIsDisplayed()
    }

    @Test
    fun anEmptyTrashSaysHowItWorks() {
        show(TrashState(loading = false))

        compose.onNodeWithText("Trash is empty").assertIsDisplayed()
        compose.onNodeWithText("Empty trash").assertDoesNotExist()
    }
}
