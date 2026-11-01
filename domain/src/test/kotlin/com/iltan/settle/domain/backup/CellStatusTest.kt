package com.iltan.settle.domain.backup

import com.iltan.settle.domain.CloudState
import com.iltan.settle.domain.ItemStatus
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class CellStatusTest {

    @Test
    fun followsThePlansDecision() {
        cellStatus(CloudState.NONE, BackupDecision.WaitUntil(5), false).shouldBe(ItemStatus.WAITING)
        cellStatus(CloudState.NONE, BackupDecision.Ready, false).shouldBe(ItemStatus.QUEUED)
        cellStatus(CloudState.NONE, BackupDecision.Skip, false).shouldBe(ItemStatus.NOT_BACKED_UP)
        cellStatus(CloudState.NONE, BackupDecision.NeedsAttention, false)
            .shouldBe(ItemStatus.NEEDS_ATTENTION)
    }

    @Test
    fun onlyALiveUploadIsUploading() {
        cellStatus(CloudState.NONE, BackupDecision.Ready, uploading = true)
            .shouldBe(ItemStatus.UPLOADING)
        // An upload that stopped halfway is queued again, as the plan says.
        cellStatus(CloudState.UPLOADING, BackupDecision.Ready, uploading = false)
            .shouldBe(ItemStatus.QUEUED)
    }

    @Test
    fun aVerifiedItemIsBackedUp() {
        cellStatus(CloudState.VERIFIED, null, false).shouldBe(ItemStatus.BACKED_UP)
    }

    @Test
    fun aCopyThatFailedVerificationIsRetriedRatherThanFlagged() {
        // The engine uploads a suspect copy again by itself; only the plan's verdict asks the user.
        cellStatus(CloudState.SUSPECT, BackupDecision.Ready, false).shouldBe(ItemStatus.QUEUED)
    }

    @Test
    fun backedUpNeverShowsAndNotBackedUpOnlyOnceConnected() {
        cellShowsStatus(ItemStatus.BACKED_UP, connected = true, selecting = true).shouldBe(false)
        cellShowsStatus(ItemStatus.NOT_BACKED_UP, connected = false, selecting = true)
            .shouldBe(false)
        cellShowsStatus(ItemStatus.NOT_BACKED_UP, connected = true, selecting = false)
            .shouldBe(true)
    }

    @Test
    fun waitingAndQueuedShowOnlyWhileSelecting() {
        for (status in listOf(ItemStatus.WAITING, ItemStatus.QUEUED)) {
            cellShowsStatus(status, connected = true, selecting = false).shouldBe(false)
            cellShowsStatus(status, connected = true, selecting = true).shouldBe(true)
        }
    }

    @Test
    fun uploadingAndAttentionAlwaysShow() {
        for (status in listOf(ItemStatus.UPLOADING, ItemStatus.NEEDS_ATTENTION)) {
            cellShowsStatus(status, connected = true, selecting = false).shouldBe(true)
        }
    }
}
