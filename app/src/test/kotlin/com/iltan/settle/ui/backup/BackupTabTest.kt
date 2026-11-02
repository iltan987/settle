package com.iltan.settle.ui.backup

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.iltan.settle.data.backup.BackupChoices
import com.iltan.settle.data.backup.BackupCounts
import com.iltan.settle.data.backup.UploadProgress
import com.iltan.settle.data.db.MediaEntry
import com.iltan.settle.data.db.PendingEntry
import com.iltan.settle.domain.CloudState
import com.iltan.settle.domain.backup.BackupDecision
import com.iltan.settle.domain.backup.BackupHeadline
import com.iltan.settle.domain.backup.BackupPause
import com.iltan.settle.domain.backup.RunProgress
import com.iltan.settle.domain.backup.UploadProblem
import com.iltan.settle.domain.scan.MediaKind
import com.iltan.settle.ui.theme.SettleTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class BackupTabTest {

    @get:Rule val compose = createComposeRule()

    private val calls = mutableListOf<String>()

    private val actions =
        BackupTabActions(
            connect = { calls += "connect" },
            setPaused = { calls += "paused $it" },
            backUpEverythingNow = { calls += "everything" },
            tryAgain = { calls += "again $it" },
            open = { calls += "open ${it.entry.id}" },
            viewTrash = { calls += "trash" },
            reviewMass = { calls += "mass" },
        )

    private fun show(state: BackupTabState, decisions: Map<String, BackupDecision> = emptyMap()) =
        compose.setContent {
            SettleTheme {
                BackupTab(
                    state = state,
                    accountEmail = "me@example.com",
                    actions = actions,
                    onOpenSettings = {},
                    uploads = remember { mutableStateOf(emptyMap<String, UploadProgress>()) },
                    decisions = remember { mutableStateOf(decisions) },
                )
            }
        }

    private fun counts(waiting: Int = 0, queued: Int = 0, pause: BackupPause? = null) =
        BackupCounts(pause, waiting, queued, needsAttention = 0, backedUp = 256)

    @Test
    fun allInDriveWithItemsWaitingOffersToBackUpEverythingAndShowsTheirBadges() {
        val waiting = pending("w1")
        val queued = pending("q1")
        show(
            BackupTabState(
                loaded = true,
                headline = BackupHeadline.AllInDrive(256, 2),
                counts = counts(waiting = 1, queued = 1),
                sections = BackupSections(waiting = listOf(waiting), queued = listOf(queued)),
            ),
            mapOf("w1" to BackupDecision.WaitUntil(0), "q1" to BackupDecision.Ready),
        )

        compose.onNodeWithText("All in Drive").assertIsDisplayed()
        compose
            .onNodeWithText("256 items are safe and checked.", substring = true)
            .assertIsDisplayed()
        compose.onNodeWithText("Waiting · 1").assertIsDisplayed()
        compose.onNodeWithText("Queued · 1").assertIsDisplayed()
        compose.onNodeWithText("Tonight, on Wi-Fi and the charger").assertIsDisplayed()
        compose.onNodeWithText("Start").performClick()
        compose.onNodeWithTag("media-q1").performClick()

        assertEquals(listOf("everything", "open q1"), calls)
    }

    @Test
    fun aRunShowsItsProgressAndPauseAndHidesBackingUpEverything() {
        show(
            BackupTabState(
                loaded = true,
                headline =
                    BackupHeadline.BackingUp(RunProgress(12, 40, 30_000_000, 100_000_000, 70_000)),
                counts = counts(queued = 28),
                run = RunProgress(12, 40, 30_000_000, 100_000_000, 70_000),
                sections = BackupSections(inRun = listOf(pending("r1"))),
            )
        )

        compose.onNodeWithText("Backing up 12 of 40").assertIsDisplayed()
        compose.onNodeWithText("/s", substring = true).assertIsDisplayed()
        compose.onNodeWithText("About 17 minutes left").assertIsDisplayed()
        compose.onNodeWithText("In this backup · 1 left").assertIsDisplayed()
        compose.onNodeWithText("Back up everything now").assertDoesNotExist()
        compose.onNodeWithText("Pause").performClick()

        assertEquals(listOf("paused true"), calls)
    }

    @Test
    fun pausedByTheUserOffersResumeAndSaysQueuedItemsWaitForIt() {
        show(
            BackupTabState(
                loaded = true,
                headline = BackupHeadline.Paused(BackupPause.BY_USER, 9),
                counts = counts(queued = 9, pause = BackupPause.BY_USER),
                sections = BackupSections(queued = listOf(pending("q1"))),
            )
        )

        compose.onNodeWithText("Backup paused by you").assertIsDisplayed()
        compose
            .onNodeWithText("9 items wait on this phone, safe and unchanged.")
            .assertIsDisplayed()
        compose.onNodeWithText("After you resume").assertIsDisplayed()
        compose.onNodeWithText("Back up everything now").assertDoesNotExist()
        compose.onNodeWithText("Resume").performClick()

        assertEquals(listOf("paused false"), calls)
    }

    @Test
    fun itemsThatNeedAttentionSayWhyAndThatTheyStayOnThePhone() {
        val broken = pending("a1", MediaKind.VIDEO, UploadProblem.ORIGINAL_UNAVAILABLE)
        show(
            BackupTabState(
                loaded = true,
                headline = BackupHeadline.NeedsAttention(2),
                counts = counts(),
                sections = BackupSections(attention = listOf(broken)),
                restoreProblems = 1,
            )
        )

        compose.onNodeWithText("2 items need attention").assertIsDisplayed()
        compose.onNodeWithText("Couldn't read this file").assertIsDisplayed()
        compose.onNodeWithText("It stays on this phone.", substring = true).assertIsDisplayed()
        compose.onNodeWithText("1 item couldn't be restored").assertIsDisplayed()
        compose.onNodeWithText("Try again").performClick()
        compose.onNodeWithText("1 item couldn't be restored").performClick()

        assertEquals(listOf("again a1", "trash"), calls)
    }

    @Test
    fun aMassDisappearanceIsReviewedFromHereToo() {
        show(
            BackupTabState(
                loaded = true,
                headline = BackupHeadline.AllInDrive(256, 0),
                counts = counts(),
                massCount = 312,
            )
        )

        compose.onNodeWithText("Review").performClick()

        assertEquals(listOf("mass"), calls)
    }

    @Test
    fun withNothingComingItExplainsTheRhythm() {
        show(
            BackupTabState(
                loaded = true,
                headline = BackupHeadline.AllInDrive(602, 0),
                counts = counts(),
                lastBackupAtMs = System.currentTimeMillis(),
            )
        )

        compose.onNodeWithText("Last backup today at", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Nothing waiting").assertIsDisplayed()
        compose
            .onNodeWithText("New photos and videos back up 24 hours after they appear.")
            .assertIsDisplayed()
    }

    @Test
    fun beforeDriveIsConnectedItOffersToConnectAndTeachesHowBackupWorks() {
        show(
            BackupTabState(
                loaded = true,
                headline = BackupHeadline.Paused(BackupPause.NOT_CONNECTED, 0),
                counts = counts(pause = BackupPause.NOT_CONNECTED),
                choices = BackupChoices(),
            )
        )

        compose.onNodeWithText("Backup is off").assertIsDisplayed()
        compose.onNodeWithText("How backup works").assertIsDisplayed()
        compose.onNodeWithText("Connect").performClick()

        assertEquals(listOf("connect"), calls)
    }

    private fun pending(
        id: String,
        kind: MediaKind = MediaKind.IMAGE,
        problem: UploadProblem? = null,
    ) =
        PendingEntry(
            MediaEntry(
                id = id,
                kind = kind,
                mimeType = "image/jpeg",
                takenAtMs = 1_700_000_000_000,
                durationMs = 68_000,
                specialFormat = 0,
                isFavorite = false,
                width = 10,
                height = 10,
                volume = "external_primary",
                mediaStoreId = id.hashCode().toLong(),
                dateModifiedS = 0,
                cloudState = CloudState.NONE,
            ),
            relativePath = "DCIM/Camera/",
            sizeBytes = 1_000,
            problem = problem,
        )
}
