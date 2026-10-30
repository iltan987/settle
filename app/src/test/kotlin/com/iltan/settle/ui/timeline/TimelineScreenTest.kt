package com.iltan.settle.ui.timeline

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import com.iltan.settle.data.db.MediaEntry
import com.iltan.settle.domain.scan.MediaKind
import com.iltan.settle.domain.timeline.groupByDay
import com.iltan.settle.ui.grid.MediaGridState
import com.iltan.settle.ui.grid.SPECIAL_FORMAT_MOTION_PHOTO
import com.iltan.settle.ui.grid.formatDuration
import com.iltan.settle.ui.theme.SettleTheme
import java.time.ZoneId
import org.junit.Assert.assertEquals
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
                entry("b", now - day, specialFormat = SPECIAL_FORMAT_MOTION_PHOTO, favorite = true),
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
}
