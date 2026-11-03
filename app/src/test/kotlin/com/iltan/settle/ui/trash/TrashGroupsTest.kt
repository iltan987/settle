package com.iltan.settle.ui.trash

import com.iltan.settle.data.db.TrashedEntry
import com.iltan.settle.domain.CloudState
import com.iltan.settle.domain.LocalState
import com.iltan.settle.domain.deletion.RestoreProblem
import com.iltan.settle.domain.deletion.TrashOrigin
import com.iltan.settle.domain.deletion.VanishedReason
import com.iltan.settle.domain.scan.MediaKind
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrashGroupsTest {

    private val day = 24 * 60 * 60 * 1000L
    private val today = 20_000 * day

    private fun entry(
        id: String,
        origin: TrashOrigin? = TrashOrigin.SETTLE,
        trashedAt: Long? = today,
        waiting: Boolean = false,
        restoring: Boolean = false,
        problem: RestoreProblem? = null,
        path: String = "DCIM/Camera/",
    ) =
        TrashedEntry(
            id = id,
            kind = MediaKind.IMAGE,
            mimeType = "image/jpeg",
            takenAtMs = 0,
            durationMs = 0,
            volume = "external_primary",
            mediaStoreId = null,
            dateModifiedS = 0,
            localState = LocalState.MISSING,
            cloudState = CloudState.VERIFIED,
            origin = if (waiting) null else origin,
            trashedAtMs = if (waiting) null else trashedAt,
            expiresAtMs = trashedAt?.plus(30 * day),
            restoreRequestedAtMs = if (restoring || problem != null) today else null,
            restoreProblem = problem,
            relativePath = path,
            sizeBytes = 1_000,
            waitingReason = if (waiting) VanishedReason.ASK else null,
        )

    @Test
    fun groupsAreByDayAndWayOfDeletingWithWaitingItemsFirst() {
        val groups =
            trashGroups(
                listOf(
                    entry("a"),
                    entry("b", origin = TrashOrigin.ELSEWHERE),
                    entry("c"),
                    entry("d", trashedAt = today - 3 * day),
                    entry("e", origin = TrashOrigin.DISAPPEARED, trashedAt = today - 3 * day),
                    entry("w", waiting = true),
                ),
                ZoneOffset.UTC,
            )

        val first = LocalDate.ofEpochDay(20_000)
        assertEquals(
            listOf(
                GroupKind.WAITING to null,
                GroupKind.SETTLE to first,
                GroupKind.ELSEWHERE to null,
                GroupKind.SETTLE to first.minusDays(3),
                GroupKind.DISAPPEARED to null,
            ),
            groups.map { it.kind to it.day },
        )
        assertEquals(listOf("a", "c"), groups[1].items.map { it.id })
    }

    @Test
    fun deletionsInAnotherAppOnDifferentDaysShareOneGroup() {
        val groups =
            trashGroups(
                listOf(
                    entry("new", origin = TrashOrigin.ELSEWHERE),
                    entry("old", origin = TrashOrigin.ELSEWHERE, trashedAt = today - 3 * day),
                ),
                ZoneOffset.UTC,
            )

        assertEquals(listOf(GroupKind.ELSEWHERE), groups.map { it.kind })
        assertEquals(listOf("new", "old"), groups.single().items.map { it.id })
    }

    @Test
    fun daysLeftRoundUpAndNeverGoBelowZero() {
        val entry = entry("a")
        assertEquals(30, daysLeft(entry, today))
        assertEquals(1, daysLeft(entry, today + 29 * day + 1))
        assertEquals(0, daysLeft(entry, today + 31 * day))
    }

    @Test
    fun aRestoreCountsWhatCameBackAndEndsWithWhereItWent() {
        val tracker = RestoreTracker()
        val a = entry("a", restoring = true)
        val b = entry("b", restoring = true)

        val started = tracker.update(listOf(a, b))
        assertEquals(
            RestoreBatch(total = 2, restored = 0, bytesLeft = 2_000, bytesTotal = 2_000),
            started.batch,
        )

        val half = tracker.update(listOf(b))
        assertEquals(listOf("a"), half.justRestored.map { it.id })
        assertEquals(
            RestoreBatch(total = 2, restored = 1, bytesLeft = 1_000, bytesTotal = 2_000),
            half.batch,
        )
        assertNull(half.outcome)

        val done = tracker.update(emptyList())
        assertEquals(
            RestoreOutcome(restored = 2, folder = "DCIM/Camera/", failed = 0),
            done.outcome,
        )
        assertNull(done.batch)
        // Over: nothing more to report.
        assertNull(tracker.update(emptyList()).outcome)
    }

    @Test
    fun aRestoreThatPartlyFailedSaysSoAndNamesNoFolderForSeveral() {
        val tracker = RestoreTracker()
        tracker.update(
            listOf(
                entry("a", restoring = true),
                entry("b", restoring = true, path = "Pictures/"),
                entry("c", restoring = true),
            )
        )

        val done = tracker.update(listOf(entry("c", problem = RestoreProblem.NOT_WRITABLE)))

        assertEquals(RestoreOutcome(restored = 2, folder = null, failed = 1), done.outcome)
    }
}
