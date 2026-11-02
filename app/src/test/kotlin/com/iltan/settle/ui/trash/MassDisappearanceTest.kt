package com.iltan.settle.ui.trash

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.iltan.settle.data.db.VanishedEntry
import com.iltan.settle.domain.deletion.VanishedReason
import com.iltan.settle.domain.scan.MediaKind
import com.iltan.settle.ui.theme.SettleTheme
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MassDisappearanceTest {

    @get:Rule val compose = createComposeRule()

    private val zone = ZoneOffset.UTC

    private fun millis(year: Int) =
        LocalDate.of(year, 6, 1).atStartOfDay(zone).toInstant().toEpochMilli()

    private fun item(n: Int, year: Int, card: Boolean = true, path: String = "DCIM/Camera/") =
        VanishedEntry(
            id = "item$n",
            kind = MediaKind.IMAGE,
            takenAtMs = millis(year),
            displayName = "IMG_$n.jpg",
            reason = VanishedReason.BREAKER,
            sinceMs = millis(2026) + n,
            volume = if (card) "1234-5678" else "external_primary",
            relativePath = path,
        )

    private val items =
        listOf(item(1, 2019), item(2, 2024), item(3, 2021), item(4, 2022, card = false))

    @Test
    fun theFactsSayWhenWhereAndWhichYears() {
        val facts = massFacts(items, zone)

        assertEquals(4, facts.count)
        assertEquals(millis(2026) + 1, facts.noticedAt?.toInstant()?.toEpochMilli())
        assertTrue(facts.mostlyOnCard)
        assertEquals("DCIM/Camera/", facts.mostlyIn)
        assertEquals(2019 to 2024, facts.firstYear to facts.lastYear)
    }

    private var kept = false
    private var moved = false
    private var checked = false

    private fun show() = compose.setContent {
        SettleTheme {
            MassReviewScreen(
                items = items,
                onBack = {},
                onSeeAll = {},
                onKeep = { kept = true },
                onCheckAgain = { checked = true },
                onMove = { moved = true },
                zone = zone,
            )
        }
    }

    @Test
    fun itLeadsWithWhatIsSafeAndOffersTheSafestChoiceFirst() {
        show()

        compose
            .onNodeWithText("4 photos and videos disappeared from this phone at once")
            .assertIsDisplayed()
        compose.onNodeWithText("Their Drive copies are untouched.", substring = true).assertExists()
        compose.onNodeWithText("Mostly from the SD card · 2019 to 2024").assertExists()
        compose.onNodeWithText("Keep Drive copies").performClick()
        compose.onNodeWithText("Check again").performClick()

        assertTrue(kept && checked)
    }

    @Test
    fun movingDriveCopiesAsksFirstAndCanBeCancelled() {
        show()

        compose.onNodeWithText("Move Drive copies to trash…").performClick()
        compose.onNodeWithText("Move 4 Drive copies to the trash?").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(false, moved)

        compose.onNodeWithText("Move Drive copies to trash…").performClick()
        compose.onNode(hasText("Move to trash") and hasAnyAncestor(isDialog())).performClick()
        assertTrue(moved)
    }
}
