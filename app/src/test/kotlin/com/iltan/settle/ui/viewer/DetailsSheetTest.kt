package com.iltan.settle.ui.viewer

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.iltan.settle.data.db.MediaDetails
import com.iltan.settle.data.db.MediaEntry
import com.iltan.settle.data.db.UploadEntity
import com.iltan.settle.data.media.Place
import com.iltan.settle.domain.CloudState
import com.iltan.settle.domain.backup.UploadProblem
import com.iltan.settle.domain.scan.MediaKind
import com.iltan.settle.ui.theme.SettleTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DetailsSheetTest {

    @get:Rule val compose = createComposeRule()

    private val entry =
        MediaEntry(
            id = "01ITEM",
            kind = MediaKind.IMAGE,
            mimeType = "image/jpeg",
            takenAtMs = 1_793_000_000_000L,
            durationMs = 0,
            specialFormat = 0,
            isFavorite = false,
            width = 4080,
            height = 3072,
            volume = "external_primary",
            mediaStoreId = 1,
            dateModifiedS = 0,
            cloudState = CloudState.VERIFIED,
        )

    private val facts =
        ItemFacts(
            MediaDetails(
                id = "01ITEM",
                displayName = "PXL_20261029_154212034.jpg",
                relativePath = "DCIM/Camera/",
                sizeBytes = 4_200_000,
                mimeType = "image/jpeg",
                takenAtMs = entry.takenAtMs,
                width = 4080,
                height = 3072,
            ),
            location = 40.95 to 40.98,
            place = Place("Ayder Yaylası", "Rize", "Ayder Yaylası, Çamlıhemşin, Rize"),
        )

    private val upload =
        UploadEntity(
            itemId = "01ITEM",
            driveFileId = "drive1",
            state = CloudState.VERIFIED,
            sizeBytes = 4_200_000,
            dateModifiedS = 0,
            sessionUri = null,
            sessionStartedAtMs = null,
            confirmedBytes = 4_200_000,
            sha256 = "ab12",
            md5 = null,
            verifiedAtMs = 1_793_050_000_000L,
            problem = null,
        )

    private val drive = mutableListOf<String>()
    private val maps = mutableListOf<String?>()
    private val actions = mutableListOf<ChipAction>()

    private fun show(status: CloudStatus, upload: UploadEntity? = this.upload) {
        compose.setContent {
            SettleTheme {
                val chip = viewerChip(status, "Camera", chargingOnly = true, nowMillis = 0)
                DetailsSheet(
                    DetailsContent(entry, facts, status, chip, upload, folder = "Camera"),
                    toBackup = false,
                    onDismiss = {},
                    onOpenMap = { _, _, label -> maps += label },
                    onOpenInDrive = { drive += it },
                    onAction = { actions += it },
                )
            }
        }
    }

    @Test
    fun itShowsTheFileItsFolderAndWhereItWasTaken() {
        show(CloudStatus.BackedUp("drive1"))

        compose.onNodeWithText("PXL_20261029_154212034.jpg").assertExists()
        compose.onNodeWithText("4080 × 3072", substring = true).assertExists()
        compose.onNodeWithText("DCIM/Camera · on this phone").assertExists()
        compose.onNodeWithText("Ayder Yaylası, Çamlıhemşin, Rize").performClick()

        assertEquals(listOf("Ayder Yaylası, Çamlıhemşin, Rize"), maps)
    }

    @Test
    fun aBackedUpItemSaysItsCopyWasCheckedAndOpensInDrive() {
        show(CloudStatus.BackedUp("drive1"))

        compose.onNodeWithText("Backed up to Google Drive").assertExists()
        compose
            .onNodeWithText("Size and checksums match the original · checked", substring = true)
            .assertExists()
        compose.onNodeWithText("Open in Google Drive").performScrollTo().performClick()

        assertEquals(listOf("drive1"), drive)
    }

    @Test
    fun technicalDetailsRevealTheChecksumAndDriveFileId() {
        show(CloudStatus.BackedUp("drive1"))

        compose.onNodeWithText("Technical details").performScrollTo().performClick()

        compose.onNodeWithText("ab12").assertExists()
        compose.onNodeWithText("drive1").assertExists()
    }

    @Test
    fun anUnreadableFileOffersToTryAgainHere() {
        show(CloudStatus.Problem(UploadProblem.ORIGINAL_UNAVAILABLE), upload = null)

        compose.onNodeWithText("Needs your attention").assertExists()
        compose.onNodeWithText("Back up again").performScrollTo().performClick()

        assertEquals(listOf(ChipAction.TRY_AGAIN), actions)
    }
}
