package com.iltan.settle.ui.timeline

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import com.iltan.settle.data.db.MediaEntry
import com.iltan.settle.domain.CloudState
import com.iltan.settle.domain.ItemStatus
import com.iltan.settle.domain.backup.BackupHeadline
import com.iltan.settle.domain.backup.BackupPause
import com.iltan.settle.domain.scan.MediaKind
import com.iltan.settle.domain.timeline.groupByDay
import com.iltan.settle.ui.AppMessages
import com.iltan.settle.ui.LocalAppMessages
import com.iltan.settle.ui.grid.CellBackup
import com.iltan.settle.ui.grid.MediaGridState
import com.iltan.settle.ui.grid.SPECIAL_FORMAT_MOTION_PHOTO
import com.iltan.settle.ui.grid.formatDuration
import com.iltan.settle.ui.theme.SettleTheme
import com.iltan.settle.ui.trash.ItemActions
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TimelineScreenTest {

    @get:Rule val compose = createComposeRule()

    private val day = 24 * 60 * 60 * 1000L

    private fun entry(
        id: String,
        takenAt: Long,
        kind: MediaKind = MediaKind.IMAGE,
        durationMs: Long = 0,
        specialFormat: Int = 0,
        favorite: Boolean = false,
        cloudState: CloudState = CloudState.NONE,
    ) =
        MediaEntry(
            id = id,
            kind = kind,
            mimeType = if (kind == MediaKind.VIDEO) "video/mp4" else "image/jpeg",
            takenAtMs = takenAt,
            durationMs = durationMs,
            specialFormat = specialFormat,
            isFavorite = favorite,
            width = 10,
            height = 10,
            volume = "external_primary",
            mediaStoreId = id.hashCode().toLong(),
            dateModifiedS = 0,
            cloudState = cloudState,
        )

    private fun stateOf(vararg entries: MediaEntry) =
        MediaGridState(
            sections = groupByDay(entries.toList(), ZoneId.systemDefault()) { it.takenAtMs },
            count = entries.size,
            loading = false,
        )

    private fun twoDays(): MediaGridState {
        val now = System.currentTimeMillis()
        return stateOf(entry("a", now), entry("b", now - 1), entry("c", now - day))
    }

    private val deleted = mutableListOf<List<String>>()
    private val backedUp = mutableListOf<List<String>>()
    private val favorited = mutableListOf<Pair<List<String>, Boolean>>()
    private var finishDeletion: () -> Unit = {}

    private val actions =
        ItemActions(
            delete = { request ->
                deleted += request.entries.map { it.id }
                finishDeletion = request.onDeleted
            },
            backUp = { entries -> backedUp += entries.map { it.id } },
            share = {},
            favorite = { entries, on -> favorited += entries.map { it.id } to on },
        )

    private fun show(
        state: MediaGridState,
        actions: ItemActions? = this.actions,
        status: TimelineStatus = TimelineStatus(),
        notices: NoticeHandlers = NoticeHandlers(),
        backup: (MediaEntry) -> CellBackup? = { null },
        onColumnsChange: ((Int) -> Unit)? = null,
    ) = setScreen {
        TimelineScreen(
            state = state,
            onOpen = {},
            onOpenSettings = {},
            actions = actions,
            status = status,
            notices = notices,
            backup = backup,
            onColumnsChange = onColumnsChange,
        )
    }

    /** The theme and the app's messages around [content], as SettleApp provides them. */
    private fun setScreen(content: @Composable () -> Unit) = compose.setContent {
        SettleTheme(dynamicColor = false) {
            val scope = rememberCoroutineScope()
            val messages = remember { AppMessages(SnackbarHostState(), scope) }
            CompositionLocalProvider(LocalAppMessages provides messages, content = content)
        }
    }

    @Test
    fun groupsItemsUnderDayHeadersAndDescribesEachCell() {
        val now = System.currentTimeMillis()
        show(
            stateOf(
                entry("a", now, kind = MediaKind.VIDEO, durationMs = 12_000),
                entry(
                    "b",
                    now - day,
                    specialFormat = SPECIAL_FORMAT_MOTION_PHOTO,
                    favorite = true,
                    cloudState = CloudState.VERIFIED,
                ),
            )
        )

        compose.onNodeWithText("Today").assertIsDisplayed()
        compose.onNodeWithText("Yesterday").assertIsDisplayed()
        compose.onNodeWithText("0:12").assertIsDisplayed()
        compose.onNodeWithTag("media-a").assertContentDescriptionContains("Video", substring = true)
        compose
            .onNodeWithTag("media-b")
            .assertContentDescriptionContains("Motion photo", substring = true)
            .assertContentDescriptionContains("Favorite", substring = true)
    }

    @Test
    fun quietStatusesShowOnlyWhileSelecting() {
        val now = System.currentTimeMillis()
        show(
            stateOf(entry("a", now), entry("b", now - 1)),
            status = TimelineStatus(accountEmail = "d@example.com"),
            backup = { e ->
                CellBackup(if (e.id == "a") ItemStatus.WAITING else ItemStatus.NEEDS_ATTENTION)
            },
        )

        // Needs attention always shows; waiting doesn't, until the user selects.
        compose
            .onNodeWithTag("media-b")
            .assertContentDescriptionContains("Backup needs attention", substring = true)
        compose
            .onNodeWithContentDescription("Waiting for backup", substring = true)
            .assertDoesNotExist()

        compose.onNodeWithTag("media-b").performTouchInput { longClick() }
        compose
            .onNodeWithTag("media-a")
            .assertContentDescriptionContains("Waiting for backup", substring = true)
    }

    @Test
    fun notBackedUpIsBadgedOnlyOnceDriveIsConnected() {
        var email by mutableStateOf<String?>(null)
        setScreen {
            run {
                TimelineScreen(
                    state = stateOf(entry("a", System.currentTimeMillis())),
                    onOpen = {},
                    onOpenSettings = {},
                    status = TimelineStatus(accountEmail = email),
                    backup = { CellBackup(ItemStatus.NOT_BACKED_UP) },
                )
            }
        }
        compose.onNodeWithContentDescription("Not backed up", substring = true).assertDoesNotExist()

        email = "d@example.com"
        compose
            .onNodeWithTag("media-a")
            .assertContentDescriptionContains("Not backed up", substring = true)
    }

    @Test
    fun emptyLibraryAfterScanShowsEmptyState() {
        show(MediaGridState(loading = false))

        compose.onNodeWithText("Nothing here yet").assertIsDisplayed()
        compose.onNodeWithText("Choose folders to show").assertIsDisplayed()
    }

    @Test
    fun durationsUseMinutesAndHours() {
        assertEquals("0:07", formatDuration(7_400))
        assertEquals("12:34", formatDuration((12 * 60 + 34) * 1000L))
        assertEquals("1:02:03", formatDuration((3600 + 2 * 60 + 3) * 1000L))
    }

    @Test
    fun aLongPressStartsSelectingAndTapsThenToggle() {
        show(twoDays())

        compose.onNodeWithTag("media-a").performTouchInput { longClick() }
        compose.onNodeWithText("1 selected").assertIsDisplayed()
        compose.onNodeWithTag("media-b").performClick()
        compose.onNodeWithText("2 selected").assertIsDisplayed()
        compose.onNodeWithTag("media-a").performClick()
        compose.onNodeWithText("1 selected").assertIsDisplayed()

        compose.onNodeWithContentDescription("Clear selection").performClick()
        compose.onNodeWithText("Photos").assertIsDisplayed()
    }

    @Test
    fun aDaysHeaderSelectsTheWholeDay() {
        show(twoDays())
        compose.onNodeWithTag("media-c").performTouchInput { longClick() }

        compose.onNodeWithText("Today").performClick()
        compose.onNodeWithText("3 selected").assertIsDisplayed()

        compose.onNodeWithText("Today").performClick()
        compose.onNodeWithText("1 selected").assertIsDisplayed()
    }

    @Test
    fun selectAllTakesEveryItem() {
        show(twoDays())
        compose.onNodeWithTag("media-a").performTouchInput { longClick() }

        compose.onNodeWithContentDescription("Select all").performClick()

        compose.onNodeWithText("3 selected").assertIsDisplayed()
    }

    @Test
    fun deleteGetsTheSelectionWhichEndsOnceDeleted() {
        show(twoDays())
        compose.onNodeWithTag("media-a").performTouchInput { longClick() }
        compose.onNodeWithTag("media-c").performClick()

        compose.onNodeWithText("Delete").performClick()
        assertEquals(listOf(listOf("a", "c")), deleted)
        // Still selected while Android asks.
        compose.onNodeWithText("2 selected").assertIsDisplayed()

        finishDeletion()
        compose.onNodeWithText("Photos").assertIsDisplayed()
    }

    @Test
    fun backUpTakesOnlyItemsNotInDriveAndEndsTheSelection() {
        val now = System.currentTimeMillis()
        show(stateOf(entry("a", now, cloudState = CloudState.VERIFIED), entry("b", now - 1)))
        compose.onNodeWithTag("media-a").performTouchInput { longClick() }
        compose.onNodeWithTag("media-b").performClick()

        compose.onNodeWithText("1 of 2 isn’t in Drive yet", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Back up").performClick()

        assertEquals(listOf(listOf("b")), backedUp)
        compose.onNodeWithText("Photos").assertIsDisplayed()
    }

    @Test
    fun favoriteMarksTheSelectionOrUnmarksItWhenAllAre() {
        val now = System.currentTimeMillis()
        show(stateOf(entry("a", now, favorite = true), entry("b", now - 1, favorite = true)))
        compose.onNodeWithTag("media-a").performTouchInput { longClick() }
        compose.onNodeWithTag("media-b").performClick()

        compose.onNodeWithText("Unfavorite").performClick()

        assertEquals(listOf(listOf("a", "b") to false), favorited)
    }

    @Test
    fun theChipSaysAllIsWellAndOpensBackup() {
        var opened = false
        setScreen {
            run {
                TimelineScreen(
                    state = twoDays(),
                    onOpen = {},
                    onOpenSettings = {},
                    status =
                        TimelineStatus(
                            headline = BackupHeadline.AllInDrive(backedUp = 3, upcoming = 0),
                            accountEmail = "d@example.com",
                        ),
                    onOpenBackup = { opened = true },
                )
            }
        }

        compose.onNodeWithText("All in Drive").performClick()

        assertTrue(opened)
    }

    @Test
    fun noChipBeforeBackupsStatusIsKnown() {
        show(twoDays(), status = TimelineStatus(headline = null, accountEmail = "d@example.com"))

        compose.onNodeWithText("All in Drive").assertDoesNotExist()
        compose.onNodeWithText("First backup tonight").assertDoesNotExist()
    }

    @Test
    fun aProblemShowsAsACardThenCollapsesIntoTheChip() {
        var dismissed by mutableStateOf(emptySet<String>())
        var signedIn = false
        setScreen {
            run {
                TimelineScreen(
                    state = twoDays(),
                    onOpen = {},
                    onOpenSettings = {},
                    status =
                        TimelineStatus(
                            headline = BackupHeadline.Paused(BackupPause.AUTH_NEEDED, 4),
                            accountEmail = "d@example.com",
                            dismissed = dismissed,
                        ),
                    notices =
                        NoticeHandlers(
                            signIn = { signedIn = true },
                            dismiss = { dismissed = dismissed + it },
                        ),
                )
            }
        }

        // The card replaces the chip while it shows.
        compose.onNodeWithText("Sign in to Google again").assertIsDisplayed()
        compose.onNodeWithText("Backup paused").assertDoesNotExist()

        compose.onNodeWithContentDescription("Dismiss").performClick()
        compose.onNodeWithText("Sign in to Google again").assertDoesNotExist()

        // The chip now opens the same fix in a popover.
        compose.onNodeWithText("Backup paused").performClick()
        compose.onNodeWithText("Backup details").assertIsDisplayed()
        compose.onNodeWithText("Sign in").performClick()
        assertTrue(signedIn)
    }

    @Test
    fun aMassDisappearanceAsksBeforeTouchingDriveAndCannotBeDismissed() {
        var kept = false
        show(
            twoDays(),
            status = TimelineStatus(breakerCount = 312),
            notices = NoticeHandlers(keepDriveCopies = { kept = true }),
        )

        compose.onNodeWithText("312 items disappeared from this phone").assertIsDisplayed()
        compose.onNodeWithContentDescription("Dismiss").assertDoesNotExist()
        compose.onNodeWithText("Keep Drive copies").performClick()

        assertTrue(kept)
    }

    @Test
    fun spreadingTwoFingersShowsFewerColumns() {
        val changes = mutableListOf<Int>()
        show(twoDays(), onColumnsChange = { changes += it })

        compose.onRoot().performTouchInput {
            pinch(
                start0 = center - Offset(20f, 0f),
                end0 = center - Offset(200f, 0f),
                start1 = center + Offset(20f, 0f),
                end1 = center + Offset(200f, 0f),
            )
        }

        assertEquals(3, changes.firstOrNull())
        compose.onNodeWithText("3 per row · by day").assertIsDisplayed()
    }
}
