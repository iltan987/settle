package com.iltan.settle.ui.backup

import com.iltan.settle.data.db.MediaEntry
import com.iltan.settle.data.db.PendingEntry
import com.iltan.settle.domain.CloudState
import com.iltan.settle.domain.backup.BackupDecision
import com.iltan.settle.domain.scan.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Test

class BackupSectionsTest {

    private val items = listOf("up", "bad", "late", "soon", "ready", "skip").map(::pending)

    private val decisions =
        mapOf(
            "up" to BackupDecision.Ready,
            "bad" to BackupDecision.NeedsAttention,
            "late" to BackupDecision.WaitUntil(2_000),
            "soon" to BackupDecision.WaitUntil(1_000),
            "ready" to BackupDecision.Ready,
            "skip" to BackupDecision.Skip,
        )

    private fun ids(list: List<PendingEntry>) = list.map { it.entry.id }

    @Test
    fun betweenRunsItemsSortIntoAttentionWaitingAndQueuedAndUnchosenOnesAreLeftOut() {
        val sections = backupSections(items, decisions, uploading = emptySet(), running = false)

        assertEquals(listOf("bad"), ids(sections.attention))
        assertEquals(emptyList<String>(), ids(sections.inRun))
        assertEquals(listOf("late", "soon"), ids(sections.waiting))
        assertEquals(1_000L, sections.waitingUntilMs)
        assertEquals(listOf("up", "ready"), ids(sections.queued))
    }

    @Test
    fun duringARunWhatUploadsComesFirstAndTheQueueJoinsIt() {
        val sections = backupSections(items, decisions, uploading = setOf("ready"), running = true)

        assertEquals(listOf("ready", "up"), ids(sections.inRun))
        assertEquals(emptyList<String>(), ids(sections.queued))
        assertEquals(listOf("late", "soon"), ids(sections.waiting))
    }

    @Test
    fun nothingPendingIsEmpty() {
        assertEquals(true, backupSections(emptyList(), emptyMap(), emptySet(), false).isEmpty)
    }

    private fun pending(id: String) =
        PendingEntry(
            MediaEntry(
                id = id,
                kind = MediaKind.IMAGE,
                mimeType = "image/jpeg",
                takenAtMs = 0,
                durationMs = 0,
                specialFormat = 0,
                isFavorite = false,
                width = 1,
                height = 1,
                volume = "external_primary",
                mediaStoreId = 1,
                dateModifiedS = 0,
                cloudState = CloudState.NONE,
            ),
            relativePath = "DCIM/Camera/",
            sizeBytes = 1,
            problem = null,
        )
}
