package com.iltan.settle.domain.deletion

import com.iltan.settle.domain.CloudState
import com.iltan.settle.domain.LocalState
import com.iltan.settle.domain.MediaItemId
import com.iltan.settle.domain.scan.IndexedRow
import com.iltan.settle.domain.scan.MediaKind
import com.iltan.settle.domain.scan.ScanPlan
import com.iltan.settle.domain.scan.ScanRow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class TrashRulesTest {

    private val now = 10_000_000L
    private var next = 0L

    private fun item(
        local: LocalState = LocalState.PRESENT,
        cloud: CloudState = CloudState.VERIFIED,
        superseded: List<String> = emptyList(),
        entry: TrashOrigin? = null,
        vanished: VanishedReason? = null,
        pendingOps: Int = 0,
        trashedAt: Long = now,
    ): TrashItem {
        val id = MediaItemId.generate(nowMillis = ++next)
        return TrashItem(
            itemId = id,
            localState = local,
            mediaStoreId =
                next.takeIf { local == LocalState.PRESENT || local == LocalState.TRASHED },
            cloudState = cloud,
            driveFiles =
                DriveFiles(current = "file$next".takeIf { cloud != CloudState.NONE }, superseded),
            entry = entry?.let { TrashEntry(id, it, trashedAt) },
            vanished = vanished?.let { Vanished(id, now - 1, it) },
            pendingOps = pendingOps,
        )
    }

    private fun row(item: TrashItem, trashed: Boolean) =
        ScanRow(
            volume = "external_primary",
            mediaStoreId = item.mediaStoreId ?: 500,
            relativePath = "DCIM/Camera/",
            displayName = "IMG.jpg",
            mimeType = "image/jpeg",
            kind = MediaKind.IMAGE,
            sizeBytes = 10,
            dateTakenMillis = 1,
            dateModifiedSeconds = 1,
            width = 1,
            height = 1,
            orientation = 0,
            durationMillis = 0,
            specialFormat = 0,
            isFavorite = false,
            isTrashed = trashed,
            generationModified = 9,
        )

    private fun scan(
        missing: List<TrashItem> = emptyList(),
        trashedElsewhere: List<TrashItem> = emptyList(),
        back: List<TrashItem> = emptyList(),
        breaker: Boolean = false,
    ) =
        ScanPlan(
            inserts = emptyList(),
            updates =
                trashedElsewhere.map { IndexedRow(it.itemId, row(it, trashed = true)) } +
                    back.map { IndexedRow(it.itemId, row(it, trashed = false)) },
            forgets = emptyList(),
            missing = missing.map { it.itemId },
            breakerTripped = breaker,
        )

    private fun after(
        plan: ScanPlan,
        items: List<TrashItem>,
        policy: ExternalDeletionPolicy = ExternalDeletionPolicy.MOVE_DRIVE_COPY_TO_TRASH,
    ) = afterScan(plan, items.associateBy { it.itemId }, policy, now)

    /**
     * Safety invariants (ADR-002, ADR-015): nothing in Drive is trashed or deleted for an item
     * whose original is on the phone, unless the user deleted it in Settle; nothing in Drive is
     * permanently deleted unless the item was in the trash.
     */
    private fun assertSafe(
        changes: TrashChanges,
        before: List<TrashItem>,
        deletedByUser: Set<MediaItemId> = emptySet(),
    ) {
        val byId = before.associateBy { it.itemId }
        for (op in changes.ops) {
            val item = byId.getValue(op.itemId)
            when (op.kind) {
                DriveOpKind.TRASH ->
                    check(item.localState != LocalState.PRESENT || op.itemId in deletedByUser) {
                        "Drive copy of $item trashed while its original is on the phone"
                    }
                DriveOpKind.DELETE ->
                    check(item.inTrash) { "Drive copy of $item deleted outside the trash" }
                DriveOpKind.UNTRASH -> {}
            }
        }
    }

    // Deleted in another app

    @Test
    fun `a backed-up file deleted elsewhere enters the trash and its Drive copy follows an hour later`() {
        val gone = item(local = LocalState.PRESENT, superseded = listOf("old"))
        val changes = after(scan(missing = listOf(gone)), listOf(gone))

        changes.entries.single().origin shouldBe TrashOrigin.ELSEWHERE
        changes.ops.map { it.driveFileId } shouldContainExactlyInAnyOrder listOf("file1", "old")
        changes.ops.forEach {
            it.kind shouldBe DriveOpKind.TRASH
            it.notBeforeMillis shouldBe now + EXTERNAL_DELETION_WAIT_MILLIS
        }
        assertSafe(changes, listOf(gone).map { it.copy(localState = LocalState.MISSING) })
    }

    @Test
    fun `a file another app moved to the phone's trash is a deletion elsewhere too`() {
        val trashed = item()
        val changes = after(scan(trashedElsewhere = listOf(trashed)), listOf(trashed))

        changes.entries.single().origin shouldBe TrashOrigin.ELSEWHERE
        changes.ops.single().kind shouldBe DriveOpKind.TRASH
    }

    @Test
    fun `with the Ask policy nothing happens in Drive until the user decides`() {
        val gone = item()
        val changes = after(scan(missing = listOf(gone)), listOf(gone), ExternalDeletionPolicy.ASK)

        changes.ops.shouldBeEmpty()
        changes.entries.shouldBeEmpty()
        changes.vanished.single().reason shouldBe VanishedReason.ASK
    }

    @Test
    fun `an item without a cloud copy deleted elsewhere is only listed in the trash`() {
        val trashed = item(cloud = CloudState.NONE)
        val changes =
            after(
                scan(trashedElsewhere = listOf(trashed)),
                listOf(trashed),
                ExternalDeletionPolicy.ASK,
            )

        changes.entries.single().origin shouldBe TrashOrigin.ELSEWHERE
        changes.ops.shouldBeEmpty()
        changes.vanished.shouldBeEmpty()
    }

    @Test
    fun `a mass disappearance waits for the user and touches nothing in Drive`() {
        val gone = List(3) { item() }
        val changes = after(scan(missing = gone, breaker = true), gone)

        changes.ops.shouldBeEmpty()
        changes.entries.shouldBeEmpty()
        changes.vanished.map { it.reason }.toSet() shouldBe setOf(VanishedReason.BREAKER)
        changes.vanished.size shouldBe 3
    }

    @Test
    fun `a mass disappearance taken in slices is still one, while Drive is untouched`() {
        // Earlier scans took 180 vanished files as deletions elsewhere; their Drive trash waits.
        val earlier =
            List(180) {
                item(local = LocalState.MISSING, entry = TrashOrigin.ELSEWHERE, pendingOps = 1)
            }
        val now = List(30) { item() }
        val plan = scan(missing = now).copy(breakerLimit = 200)

        val changes =
            afterScan(
                plan,
                now.associateBy { it.itemId },
                ExternalDeletionPolicy.MOVE_DRIVE_COPY_TO_TRASH,
                this.now,
                recentElsewhere = earlier,
            )

        changes.ops.shouldBeEmpty()
        changes.entries.shouldBeEmpty()
        changes.removeEntries shouldContainExactlyInAnyOrder earlier.map { it.itemId }
        changes.dropOps shouldContainExactlyInAnyOrder earlier.mapNotNull { it.driveFiles.current }
        changes.vanished.size shouldBe 210
        changes.vanished.map { it.reason }.toSet() shouldBe setOf(VanishedReason.BREAKER)
    }

    @Test
    fun `a few deletions elsewhere in a row stay deletions elsewhere`() {
        val earlier =
            List(100) {
                item(local = LocalState.MISSING, entry = TrashOrigin.ELSEWHERE, pendingOps = 1)
            }
        val now = List(30) { item() }

        val changes =
            afterScan(
                scan(missing = now).copy(breakerLimit = 200),
                now.associateBy { it.itemId },
                ExternalDeletionPolicy.MOVE_DRIVE_COPY_TO_TRASH,
                this.now,
                recentElsewhere = earlier,
            )

        changes.entries.size shouldBe 30
        changes.ops.size shouldBe 30
        changes.dropOps.shouldBeEmpty()
        changes.removeEntries.shouldBeEmpty()
    }

    @Test
    fun `an item already in the trash is not taken for a new deletion`() {
        val mine = item(local = LocalState.PRESENT, entry = TrashOrigin.SETTLE)
        val asked = item(vanished = VanishedReason.ASK)

        after(scan(missing = listOf(mine, asked)), listOf(mine, asked)).isEmpty shouldBe true
    }

    @Test
    fun `a file that comes back leaves the trash and its Drive copy is untrashed`() {
        val restoredElsewhere =
            item(local = LocalState.TRASHED, entry = TrashOrigin.SETTLE, superseded = listOf("old"))
        val foundAgain = item(local = LocalState.MISSING, entry = TrashOrigin.ELSEWHERE)
        val asked = item(local = LocalState.MISSING, vanished = VanishedReason.BREAKER)
        val items = listOf(restoredElsewhere, foundAgain, asked)

        val changes = after(scan(back = items), items)

        changes.removeEntries shouldContainExactlyInAnyOrder
            listOf(restoredElsewhere.itemId, foundAgain.itemId)
        changes.removeVanished shouldContainExactly listOf(asked.itemId)
        // Only current copies: superseded ones were meant to go to the trash anyway.
        changes.ops.map { it.driveFileId to it.kind } shouldContainExactlyInAnyOrder
            listOf("file1" to DriveOpKind.UNTRASH, "file2" to DriveOpKind.UNTRASH)
        changes.entries.shouldBeEmpty()
    }

    @Test
    fun `a scan never trashes Drive copies of files still on the phone`() {
        val onPhone = List(5) { item() }
        assertSafe(after(scan(back = onPhone), onPhone), onPhone)
    }

    // Deleting in Settle

    @Test
    fun `deleting in Settle trashes the phone file and every Drive file at once`() {
        val backedUp = item(superseded = listOf("old"))
        val localOnly = item(cloud = CloudState.NONE)
        val items = listOf(backedUp, localOnly)

        phoneFilesToTrash(items) shouldContainExactly listOf(1L, 2L)
        val changes = trashedInSettle(items, now)

        changes.entries.map { it.origin }.toSet() shouldBe setOf(TrashOrigin.SETTLE)
        changes.entries.forEach { it.expiresAtMillis shouldBe now + TRASH_RETENTION_MILLIS }
        changes.ops.map { it.driveFileId } shouldContainExactlyInAnyOrder listOf("file1", "old")
        changes.ops.forEach { it.notBeforeMillis shouldBe 0 }
        changes.markTrashed shouldContainExactlyInAnyOrder items.map { it.itemId }
        assertSafe(changes, items, deletedByUser = items.map { it.itemId }.toSet())
    }

    @Test
    fun `items already in the trash are not deleted again`() {
        val inTrash = item(local = LocalState.TRASHED, entry = TrashOrigin.SETTLE)

        phoneFilesToTrash(listOf(inTrash)).shouldBeEmpty()
        trashedInSettle(listOf(inTrash), now).isEmpty shouldBe true
    }

    @Test
    fun `deleting replaces what a scan in between made of it`() {
        val before = item()
        // A scan saw the trashed row first and queued the delayed trash of a deletion elsewhere.
        val scanned = after(scan(trashedElsewhere = listOf(before)), listOf(before))
        val mine = trashedInSettle(listOf(before), now)

        mine.entries.single().origin shouldBe TrashOrigin.SETTLE
        mine.ops.single().driveFileId shouldBe scanned.ops.single().driveFileId
        mine.ops.single().notBeforeMillis shouldBe 0
    }

    // Restoring

    @Test
    fun `restoring takes the phone file out of the trash and untrashes the current Drive copy`() {
        val inTrash =
            item(local = LocalState.TRASHED, entry = TrashOrigin.SETTLE, superseded = listOf("old"))
        val plan = planRestore(listOf(inTrash))

        plan.phoneUntrash shouldContainExactly listOf(1L)
        plan.untrashed.removeEntries shouldContainExactly listOf(inTrash.itemId)
        plan.untrashed.markPresent shouldContainExactly listOf(inTrash.itemId)
        plan.untrashed.ops.map { it.driveFileId to it.kind } shouldContainExactly
            listOf("file1" to DriveOpKind.UNTRASH)
        plan.downloads.shouldBeEmpty()
    }

    @Test
    fun `an item whose phone file is gone is downloaded from Drive`() {
        val elsewhere = item(local = LocalState.MISSING, entry = TrashOrigin.ELSEWHERE)
        val plan = planRestore(listOf(elsewhere))

        plan.phoneUntrash.shouldBeEmpty()
        plan.downloads shouldContainExactly listOf(elsewhere.itemId)
        plan.downloadOps.single().kind shouldBe DriveOpKind.UNTRASH
    }

    @Test
    fun `an item with nothing left to restore from is reported`() {
        val gone =
            item(
                local = LocalState.MISSING,
                cloud = CloudState.UPLOADING,
                entry = TrashOrigin.SETTLE,
            )
        planRestore(listOf(gone)).unrestorable shouldContainExactly listOf(gone.itemId)
    }

    @Test
    fun `restoring ignores items that aren't in the trash`() {
        val plan =
            planRestore(
                listOf(item(), item(local = LocalState.MISSING, vanished = VanishedReason.ASK))
            )

        plan.phoneUntrash.shouldBeEmpty()
        plan.downloads.shouldBeEmpty()
        plan.unrestorable.shouldBeEmpty()
    }

    // Deleting forever

    @Test
    fun `delete forever removes phone and Drive files of items in the trash only`() {
        val inTrash =
            item(local = LocalState.TRASHED, entry = TrashOrigin.SETTLE, superseded = listOf("old"))
        val expiredOnPhone = item(local = LocalState.MISSING, entry = TrashOrigin.ELSEWHERE)
        val onPhone = item()
        val items = listOf(inTrash, expiredOnPhone, onPhone)

        val plan = planDeleteForever(items)

        plan.phoneDelete shouldContainExactly listOf(inTrash.mediaStoreId!!)
        plan.deleted.ops.map { it.driveFileId } shouldContainExactlyInAnyOrder
            listOf("file1", "old", "file2")
        plan.deleted.ops.forEach { it.kind shouldBe DriveOpKind.DELETE }
        plan.deleted.purge shouldContainExactlyInAnyOrder
            listOf(inTrash.itemId, expiredOnPhone.itemId)
        assertSafe(plan.deleted, items)
    }

    // The user's decision

    @Test
    fun `moving vanished items to the trash queues their Drive copies at once`() {
        val gone = List(2) { item(local = LocalState.MISSING, vanished = VanishedReason.BREAKER) }
        val changes = decideVanished(gone, VanishedDecision.MOVE_DRIVE_COPIES_TO_TRASH, now)

        changes.entries.size shouldBe 2
        changes.removeVanished shouldContainExactlyInAnyOrder gone.map { it.itemId }
        changes.ops.forEach {
            it.kind shouldBe DriveOpKind.TRASH
            it.notBeforeMillis shouldBe 0
        }
        assertSafe(changes, gone)
    }

    @Test
    fun `keeping Drive copies only stops the question`() {
        val gone = item(local = LocalState.MISSING, vanished = VanishedReason.BREAKER)
        val changes = decideVanished(listOf(gone), VanishedDecision.KEEP_DRIVE_COPIES, now)

        changes.ops.shouldBeEmpty()
        changes.vanished.single().kept shouldBe true
    }

    @Test
    fun `a vanished file that is back on the phone is never moved`() {
        val back = item(local = LocalState.PRESENT, vanished = VanishedReason.BREAKER)
        decideVanished(listOf(back), VanishedDecision.MOVE_DRIVE_COPIES_TO_TRASH, now)
            .isEmpty shouldBe true
    }

    // Expiry

    @Test
    fun `Settle forgets an item only when its time is over and nothing of it is left`() {
        val old = now - TRASH_RETENTION_MILLIS
        val done = item(local = LocalState.MISSING, entry = TrashOrigin.SETTLE, trashedAt = old)
        val stillOnPhone =
            item(local = LocalState.TRASHED, entry = TrashOrigin.SETTLE, trashedAt = old)
        val trashNeverRan =
            item(
                local = LocalState.MISSING,
                entry = TrashOrigin.ELSEWHERE,
                trashedAt = old,
                pendingOps = 1,
            )
        val recent =
            item(local = LocalState.MISSING, entry = TrashOrigin.SETTLE, trashedAt = now - 1)
        val kept = item(local = LocalState.MISSING, vanished = VanishedReason.BREAKER)

        expiredItems(
            listOf(done, stillOnPhone, trashNeverRan, recent, kept),
            now,
        ) shouldContainExactly listOf(done.itemId)
    }
}
