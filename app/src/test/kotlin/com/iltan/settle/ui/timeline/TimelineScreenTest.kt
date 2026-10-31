package com.iltan.settle.ui.timeline

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.iltan.settle.data.db.MediaEntry
import com.iltan.settle.domain.CloudState
import com.iltan.settle.domain.scan.MediaKind
import com.iltan.settle.domain.timeline.groupByDay
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

    private fun show(state: MediaGridState) = compose.setContent {
        SettleTheme { TimelineScreen(state = state, onOpen = {}, onOpenSettings = {}) }
    }

    @Test
    fun groupsItemsUnderDayHeadersWithBadges() {
        val now = System.currentTimeMillis()
        val entries =
            listOf(
                entry("a", now, kind = MediaKind.VIDEO, durationMs = 12_000),
                entry(
                    "b",
                    now - day,
                    specialFormat = SPECIAL_FORMAT_MOTION_PHOTO,
                    favorite = true,
                    cloudState = CloudState.VERIFIED,
                ),
            )
        show(
            MediaGridState(
                sections = groupByDay(entries, ZoneId.systemDefault()) { it.takenAtMs },
                count = 2,
                loading = false,
            )
        )

        compose.onNodeWithText("2 items").assertIsDisplayed()
        compose.onNodeWithText("Today").assertIsDisplayed()
        compose.onNodeWithText("Yesterday").assertIsDisplayed()
        compose.onNodeWithText("0:12").assertIsDisplayed()
        compose.onNodeWithContentDescription("Motion photo").assertIsDisplayed()
        compose.onNodeWithContentDescription("Favorite").assertIsDisplayed()
        compose.onNodeWithContentDescription("Backed up").assertIsDisplayed()
    }

    @Test
    fun emptyLibraryAfterScanShowsEmptyState() {
        show(MediaGridState(loading = false))

        compose.onNodeWithText("Nothing here yet").assertIsDisplayed()
    }

    @Test
    fun durationsUseMinutesAndHours() {
        assertEquals("0:07", formatDuration(7_400))
        assertEquals("12:34", formatDuration((12 * 60 + 34) * 1000L))
        assertEquals("1:02:03", formatDuration((3600 + 2 * 60 + 3) * 1000L))
    }

    private fun twoDays(): MediaGridState {
        val now = System.currentTimeMillis()
        val entries = listOf(entry("a", now), entry("b", now - 1), entry("c", now - day))
        return MediaGridState(
            sections = groupByDay(entries, ZoneId.systemDefault()) { it.takenAtMs },
            count = 3,
            loading = false,
        )
    }

    private val deleted = mutableListOf<List<String>>()
    private val backedUp = mutableListOf<List<String>>()
    private var finishDeletion: () -> Unit = {}

    private val actions =
        ItemActions(
            delete = { entries, onDeleted ->
                deleted += entries.map { it.id }
                finishDeletion = onDeleted
            },
            backUp = { entries -> backedUp += entries.map { it.id } },
            share = {},
        )

    private fun showSelectable(
        state: MediaGridState,
        breakerCount: Int = 0,
        onKeep: () -> Unit = {},
    ) = compose.setContent {
        SettleTheme {
            TimelineScreen(
                state = state,
                onOpen = {},
                onOpenSettings = {},
                actions = actions,
                breakerCount = breakerCount,
                onBreakerKeep = onKeep,
            )
        }
    }

    @Test
    fun aLongPressStartsSelectingAndTapsThenToggle() {
        showSelectable(twoDays())

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
        showSelectable(twoDays())
        compose.onNodeWithTag("media-c").performTouchInput { longClick() }

        compose.onNodeWithText("Today").performClick()
        compose.onNodeWithText("3 selected").assertIsDisplayed()

        compose.onNodeWithText("Today").performClick()
        compose.onNodeWithText("1 selected").assertIsDisplayed()
    }

    @Test
    fun deleteAndBackUpGetTheSelectionWhichEndsOnceDeleted() {
        showSelectable(twoDays())
        compose.onNodeWithTag("media-a").performTouchInput { longClick() }
        compose.onNodeWithTag("media-c").performClick()

        compose.onNodeWithContentDescription("Delete").performClick()
        assertEquals(listOf(listOf("a", "c")), deleted)
        // Still selected while Android asks.
        compose.onNodeWithText("2 selected").assertIsDisplayed()

        finishDeletion()
        compose.onNodeWithText("Photos").assertIsDisplayed()
    }

    @Test
    fun backingUpASelectionEndsIt() {
        showSelectable(twoDays())
        compose.onNodeWithTag("media-b").performTouchInput { longClick() }

        compose.onNodeWithContentDescription("Back up now").performClick()

        assertEquals(listOf(listOf("b")), backedUp)
        compose.onNodeWithText("Photos").assertIsDisplayed()
    }

    @Test
    fun aMassDisappearanceAsksBeforeTouchingDrive() {
        var kept = false
        showSelectable(twoDays(), breakerCount = 312, onKeep = { kept = true })

        compose
            .onNodeWithText("312 photos and videos disappeared from this phone at once")
            .assertIsDisplayed()
        compose.onNodeWithText("Keep Drive copies").performClick()

        assertTrue(kept)
    }
}
