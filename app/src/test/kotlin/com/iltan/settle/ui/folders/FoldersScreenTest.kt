package com.iltan.settle.ui.folders

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.iltan.settle.data.db.FolderSummary
import com.iltan.settle.domain.scan.MediaKind
import com.iltan.settle.ui.theme.SettleTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
// Phone-sized, so the folder tiles fit on screen.
@Config(qualifiers = "w411dp-h891dp")
class FoldersScreenTest {

    @get:Rule val compose = createComposeRule()

    private fun folder(path: String, count: Int, hidden: Boolean = false) =
        FolderSummary(
            relativePath = path,
            itemCount = count,
            latestTakenAtMs = 0,
            hiddenFromTimeline = hidden,
            coverKind = MediaKind.IMAGE,
            coverVolume = "external_primary",
            coverMediaStoreId = path.hashCode().toLong(),
            coverDateModifiedS = 0,
        )

    @Test
    fun namesUseTheLastSegmentAndDisambiguateClashes() {
        val names =
            folderNames(
                listOf("DCIM/Camera/", "Pictures/WhatsApp/", "Movies/WhatsApp/", ""),
                rootName = "Phone storage",
            )

        assertEquals(
            mapOf(
                "DCIM/Camera/" to "Camera",
                "Pictures/WhatsApp/" to "WhatsApp (Pictures)",
                "Movies/WhatsApp/" to "WhatsApp (Movies)",
                "" to "Phone storage",
            ),
            names,
        )
    }

    @Test
    fun showsFavoritesAndFoldersAndTogglesVisibility() {
        var toggled: Pair<String, Boolean>? = null
        var opened: String? = null
        compose.setContent {
            SettleTheme {
                FoldersScreen(
                    state =
                        FoldersState(
                            folders =
                                listOf(
                                    folder("DCIM/Camera/", 254),
                                    folder("Pictures/WhatsApp/", 20, hidden = true),
                                ),
                            loading = false,
                        ),
                    onOpenFolder = { path, _ -> opened = path },
                    onOpenFavorites = {},
                    onSetHidden = { path, hidden -> toggled = path to hidden },
                )
            }
        }

        compose.onNodeWithText("Favorites").assertIsDisplayed()
        compose.onNodeWithText("254 items").assertIsDisplayed()
        compose.onNodeWithContentDescription("Hidden from Photos").assertIsDisplayed()

        compose.onNodeWithText("Camera").performClick()
        assertEquals("DCIM/Camera/", opened)

        compose.onNodeWithContentDescription("Options for WhatsApp").performClick()
        compose.onNodeWithText("Show in Photos").performClick()
        assertEquals("Pictures/WhatsApp/" to false, toggled)
    }
}
