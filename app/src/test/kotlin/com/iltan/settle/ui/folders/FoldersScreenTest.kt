package com.iltan.settle.ui.folders

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.iltan.settle.data.db.FolderSummary
import com.iltan.settle.data.db.MediaEntry
import com.iltan.settle.domain.CloudState
import com.iltan.settle.domain.scan.MediaKind
import com.iltan.settle.domain.timeline.groupByDay
import com.iltan.settle.ui.AppMessages
import com.iltan.settle.ui.LocalAppMessages
import com.iltan.settle.ui.grid.MediaGridState
import com.iltan.settle.ui.theme.SettleTheme
import com.iltan.settle.ui.trash.DeleteRequest
import com.iltan.settle.ui.trash.ItemActions
import java.time.ZoneId
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

    private val calls = mutableListOf<String>()

    private val camera = folder("DCIM/Camera/", 254, backedUp = 200)
    private val whatsApp = folder("Pictures/WhatsApp/", 20, hidden = true)

    private fun setContent(content: @Composable (FolderChoices) -> Unit) = compose.setContent {
        SettleTheme {
            val scope = rememberCoroutineScope()
            val messages = remember { AppMessages(SnackbarHostState(), scope) }
            CompositionLocalProvider(LocalAppMessages provides messages) {
                content(
                    rememberFolderChoices(
                        { path, hidden -> calls += "hidden $path $hidden" },
                        { path, backUp -> calls += "backup $path $backUp" },
                    )
                )
            }
        }
    }

    private fun showFolders() = setContent { choices ->
        FoldersScreen(
            state = FoldersState(folders = listOf(camera, whatsApp), loading = false),
            onOpenFolder = { path, _ -> calls += "open $path" },
            onOpenFavorites = {},
            choices = choices,
            trashCount = 12,
        )
    }

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
    fun theTabShowsFavouritesTrashAndFoldersWithWhatTheyDo() {
        showFolders()

        compose.onNodeWithText("Favorites").assertIsDisplayed()
        compose.onNodeWithText("12 items").assertIsDisplayed()
        compose.onNodeWithText("254 items").assertIsDisplayed()
        compose.onNodeWithText("Backs up").assertIsDisplayed()
        compose.onNodeWithText("Hidden").assertIsDisplayed()

        compose.onNodeWithText("Camera").performClick()
        assertEquals(listOf("open DCIM/Camera/"), calls)
    }

    @Test
    fun longPressingAFolderOpensItsOptionsWhichApplyAtOnce() {
        showFolders()

        compose.onNodeWithText("WhatsApp").performTouchInput { longClick() }
        compose.onNodeWithText("Only Camera backs up unless you turn this on.").assertIsDisplayed()
        compose.onNodeWithText("Show in Photos").performClick()
        compose.onNodeWithText("Back up").performClick()

        assertEquals(
            listOf("hidden Pictures/WhatsApp/ false", "backup Pictures/WhatsApp/ true"),
            calls,
        )
    }

    @Test
    fun stoppingBackupForAFolderWithCopiesInDriveAsksFirst() {
        showFolders()

        compose.onNodeWithText("Camera").performTouchInput { longClick() }
        compose.onNodeWithText("Back up").performClick()
        compose.onNodeWithText("Stop backing up Camera?").assertIsDisplayed()
        assertEquals(emptyList<String>(), calls)

        compose.onNode(hasText("Stop backing up") and hasAnyAncestor(isDialog())).performClick()
        assertEquals(listOf("backup DCIM/Camera/ false"), calls)
    }

    @Test
    fun aFolderPageSaysWhetherItBacksUpAndOpensItsOptions() {
        showPage(camera)

        compose.onNodeWithText("254 items · DCIM/Camera").assertIsDisplayed()
        compose.onNodeWithText("Backs up").assertIsDisplayed()
        compose.onNodeWithText("Shown in Photos").assertIsDisplayed()
        compose.onNodeWithContentDescription("Folder options").performClick()
        compose.onNodeWithText("Copies its photos and videos to your Drive").assertIsDisplayed()
    }

    @Test
    fun aHiddenFolderPageExplainsAndOffersToShowItAgain() {
        showPage(whatsApp)

        compose.onNodeWithText("Hidden from Photos").assertIsDisplayed()
        compose.onNodeWithText("Show in Photos").performClick()

        assertEquals(listOf("hidden Pictures/WhatsApp/ false"), calls)
    }

    @Test
    fun itemsOnAFolderPageCanBeSelectedAndDeleted() {
        showPage(camera)

        compose.onNodeWithTag("media-a").performTouchInput { longClick() }
        compose.onNodeWithText("1 selected").assertIsDisplayed()
        compose.onNodeWithText("Delete").performClick()

        assertEquals(listOf("delete a"), calls)
    }

    private fun showPage(folder: FolderSummary) = setContent { choices ->
        val entries = listOf(entry("a"), entry("b"))
        CollectionScreen(
            title = "Camera",
            subtitle = folderSubtitle(folder),
            state =
                MediaGridState(
                    sections = groupByDay(entries, ZoneId.of("UTC")) { it.takenAtMs },
                    count = 2,
                    loading = false,
                ),
            emptyText = "",
            onOpen = {},
            onBack = {},
            actions =
                ItemActions(
                    delete = { r: DeleteRequest ->
                        calls += "delete " + r.entries.joinToString { it.id }
                    },
                    backUp = {},
                    share = {},
                ),
            page =
                FolderPage(
                    folder,
                    folderNames(listOf(folder.relativePath), "").values.single(),
                    choices,
                ),
        )
    }

    private fun entry(id: String) =
        MediaEntry(
            id = id,
            kind = MediaKind.IMAGE,
            mimeType = "image/jpeg",
            takenAtMs = 1_700_000_000_000,
            durationMs = 0,
            specialFormat = 0,
            isFavorite = false,
            width = 10,
            height = 10,
            volume = "external_primary",
            mediaStoreId = id.hashCode().toLong(),
            dateModifiedS = 0,
            cloudState = CloudState.VERIFIED,
        )

    private fun folder(path: String, count: Int, hidden: Boolean = false, backedUp: Int = 0) =
        FolderSummary(
            relativePath = path,
            itemCount = count,
            latestTakenAtMs = 0,
            hiddenFromTimeline = hidden,
            coverKind = MediaKind.IMAGE,
            coverVolume = "external_primary",
            coverMediaStoreId = path.hashCode().toLong(),
            coverDateModifiedS = 0,
            backedUpCount = backedUp,
        )
}
