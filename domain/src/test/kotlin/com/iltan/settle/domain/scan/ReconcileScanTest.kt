package com.iltan.settle.domain.scan

import com.iltan.settle.domain.CloudState
import com.iltan.settle.domain.LocalState
import com.iltan.settle.domain.MediaItemId
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ReconcileScanTest {

    private val volume = "external_primary"
    private var nextId = 0L

    private fun newId(): MediaItemId = MediaItemId.generate(nowMillis = ++nextId)

    private fun row(
        id: Long,
        name: String = "IMG_$id.jpg",
        path: String = "DCIM/Camera/",
        size: Long = 1_000 + id,
        taken: Long = 1_700_000_000_000 + id,
        generation: Long = id,
        trashed: Boolean = false,
    ) =
        ScanRow(
            volume = volume,
            mediaStoreId = id,
            relativePath = path,
            displayName = name,
            mimeType = "image/jpeg",
            kind = MediaKind.IMAGE,
            sizeBytes = size,
            dateTakenMillis = taken,
            dateModifiedSeconds = taken / 1000,
            width = 4000,
            height = 3000,
            orientation = 0,
            durationMillis = 0,
            specialFormat = 0,
            isFavorite = false,
            isTrashed = trashed,
            generationModified = generation,
        )

    private fun ScanRow.known(
        state: LocalState = localState,
        cloud: CloudState = CloudState.NONE,
        itemId: MediaItemId = newId(),
    ) =
        KnownFile(
            itemId = itemId,
            volume = volume,
            mediaStoreId = mediaStoreId.takeIf { state != LocalState.MISSING },
            displayName = displayName,
            sizeBytes = sizeBytes,
            takenAtMillis = takenAtMillis,
            generationModified = generationModified,
            localState = state,
            cloudState = cloud,
        )

    private fun full(vararg rows: ScanRow) =
        VolumeScan(volume, rows.toList(), rows.map { it.mediaStoreId }.toSet())

    private fun reconcile(known: List<KnownFile>, scan: VolumeScan) =
        reconcileScan(known, scan, ::newId)

    @Test
    fun `first scan inserts every row with a new item ID`() {
        val plan = reconcile(emptyList(), full(row(1), row(2)))

        plan.inserts.map { it.row.mediaStoreId } shouldContainExactly listOf(1L, 2L)
        plan.inserts.map { it.itemId }.toSet().size shouldBe 2
        plan.updates.shouldBeEmpty()
        plan.forgets.shouldBeEmpty()
    }

    @Test
    fun `unchanged rows produce no changes`() {
        val rows = listOf(row(1), row(2))
        val plan = reconcile(rows.map { it.known() }, full(*rows.toTypedArray()))

        plan.isEmpty shouldBe true
    }

    @Test
    fun `changed row updates the same item`() {
        val old = row(1).known()
        val plan = reconcile(listOf(old), full(row(1, size = 5_000, generation = 9)))

        plan.updates.single().itemId shouldBe old.itemId
        plan.updates.single().row.sizeBytes shouldBe 5_000
        plan.inserts.shouldBeEmpty()
    }

    @Test
    fun `incremental scan leaves unchanged files alone and inserts new ones`() {
        val a = row(1).known()
        val b = row(2).known()
        val scan = VolumeScan(volume, rows = listOf(row(3)), presentIds = setOf(1, 2, 3))

        val plan = reconcile(listOf(a, b), scan)

        plan.inserts.single().row.mediaStoreId shouldBe 3
        plan.updates.shouldBeEmpty()
        plan.forgets.shouldBeEmpty()
        plan.missing.shouldBeEmpty()
    }

    @Test
    fun `file deleted before reaching the cloud is forgotten`() {
        val a = row(1).known()
        val b = row(2).known()
        val scan = VolumeScan(volume, rows = emptyList(), presentIds = setOf(2))

        val plan = reconcile(listOf(a, b), scan)

        plan.forgets shouldContainExactly listOf(a.itemId)
        plan.missing.shouldBeEmpty()
        plan.breakerTripped shouldBe false
    }

    @Test
    fun `backed-up file that vanishes is marked missing, never forgotten`() {
        val a = row(1).known(cloud = CloudState.VERIFIED)
        val scan = VolumeScan(volume, rows = emptyList(), presentIds = emptySet())

        val plan = reconcile(listOf(a), scan)

        plan.missing shouldContainExactly listOf(a.itemId)
        plan.forgets.shouldBeEmpty()
    }

    @Test
    fun `moved file keeps its item ID`() {
        val old = row(1, name = "beach.jpg", size = 42, taken = 7).known()
        val moved = row(50, name = "beach.jpg", size = 42, taken = 7, path = "Pictures/Trip/")
        val scan = VolumeScan(volume, rows = listOf(moved), presentIds = setOf(50))

        val plan = reconcile(listOf(old), scan)

        plan.updates.single() shouldBe IndexedRow(old.itemId, moved)
        plan.inserts.shouldBeEmpty()
        plan.forgets.shouldBeEmpty()
    }

    @Test
    fun `system-trashed file stays known as trashed`() {
        val old = row(1).known()
        val plan = reconcile(listOf(old), full(row(1, generation = 2, trashed = true)))

        plan.updates.single().itemId shouldBe old.itemId
        plan.updates.single().row.localState shouldBe LocalState.TRASHED
        plan.forgets.shouldBeEmpty()
    }

    @Test
    fun `restoring from system trash is an update even if the generation is unchanged`() {
        val old = row(1).known(state = LocalState.TRASHED)
        val plan = reconcile(listOf(old), full(row(1)))

        plan.updates.single().row.localState shouldBe LocalState.PRESENT
    }

    @Test
    fun `mass disappearance trips the breaker and forgets nothing`() {
        val known = (1L..300L).map { row(it).known() }
        val scan = VolumeScan(volume, rows = emptyList(), presentIds = emptySet())

        val plan = reconcile(known, scan)

        plan.breakerTripped shouldBe true
        plan.forgets.shouldBeEmpty()
        plan.missing.size shouldBe 300
    }

    @Test
    fun `breaker allows up to max(200, 10 percent) disappearances`() {
        val known = (1L..3_000L).map { row(it).known() }
        val keep = (301L..3_000L).toSet()

        val atLimit = reconcile(known, VolumeScan(volume, emptyList(), keep))
        atLimit.breakerTripped shouldBe false
        atLimit.forgets.size shouldBe 300

        val overLimit = reconcile(known, VolumeScan(volume, emptyList(), keep - 301L))
        overLimit.breakerTripped shouldBe true
        overLimit.forgets.shouldBeEmpty()
    }

    @Test
    fun `a file vanishing from the trash has expired and is not a deletion elsewhere`() {
        val backedUp = row(1, trashed = true).known(cloud = CloudState.VERIFIED)
        val localOnly = row(2, trashed = true).known()
        val scan = VolumeScan(volume, rows = emptyList(), presentIds = emptySet())

        val plan = reconcile(listOf(backedUp, localOnly), scan)

        plan.expired shouldContainExactly listOf(backedUp.itemId)
        plan.forgets shouldContainExactly listOf(localOnly.itemId)
        plan.missing.shouldBeEmpty()
    }

    @Test
    fun `expiring trash never trips the breaker`() {
        val trashed = (1L..300L).map { row(it, trashed = true).known(cloud = CloudState.VERIFIED) }
        val onPhone = (301L..400L).map { row(it).known() }
        val scan = VolumeScan(volume, emptyList(), presentIds = (301L..400L).toSet())

        val plan = reconcile(trashed + onPhone, scan)

        plan.breakerTripped shouldBe false
        plan.expired.size shouldBe 300
    }

    @Test
    fun `while the breaker is tripped expired files are not forgotten either`() {
        val onPhone = (1L..300L).map { row(it).known() }
        val trashed = row(301, trashed = true).known()
        val scan = VolumeScan(volume, rows = emptyList(), presentIds = emptySet())

        val plan = reconcile(onPhone + trashed, scan)

        plan.breakerTripped shouldBe true
        plan.forgets.shouldBeEmpty()
        plan.expired shouldContainExactly listOf(trashed.itemId)
    }

    @Test
    fun `missing file found again becomes present`() {
        val old = row(1).known(state = LocalState.MISSING)
        val plan = reconcile(listOf(old), full(row(1)))

        plan.updates.single().itemId shouldBe old.itemId
        plan.updates.single().row.localState shouldBe LocalState.PRESENT
        plan.missing.shouldBeEmpty()
    }

    @Test
    fun `already missing file is not counted or reported again`() {
        val old = row(1).known(state = LocalState.MISSING, cloud = CloudState.VERIFIED)
        val plan = reconcile(listOf(old), full(row(2)))

        plan.missing.shouldBeEmpty()
        plan.forgets.shouldBeEmpty()
        plan.inserts.single().row.mediaStoreId shouldBe 2
    }

    @Test
    fun `after a MediaStore rebuild files are matched by name, size and date, not by ID`() {
        val a = row(1, name = "a.jpg").known()
        val b = row(2, name = "b.jpg").known()
        // The rebuilt index reused ID 1 for b.jpg and gave a.jpg a new ID.
        val rebuilt =
            listOf(
                row(1, name = "b.jpg", size = b.sizeBytes, taken = b.takenAtMillis),
                row(7, name = "a.jpg", size = a.sizeBytes, taken = a.takenAtMillis),
                row(8, name = "new.jpg"),
            )
        val scan =
            VolumeScan(volume, rebuilt, rebuilt.map { it.mediaStoreId }.toSet(), idsTrusted = false)

        val plan = reconcile(listOf(a, b), scan)

        plan.updates.associate { it.itemId to it.row.mediaStoreId } shouldBe
            mapOf(b.itemId to 1L, a.itemId to 7L)
        plan.inserts.single().row.displayName shouldBe "new.jpg"
        plan.forgets.shouldBeEmpty()
    }

    @Test
    fun `missing file does not claim a new file that reuses its old ID`() {
        val old = row(1, name = "old.jpg").known(state = LocalState.MISSING)
        val plan = reconcile(listOf(old), full(row(1, name = "other.jpg")))

        plan.inserts.single().row.displayName shouldBe "other.jpg"
        plan.updates.shouldBeEmpty()
    }

    @Test
    fun `evicted files and other volumes are ignored`() {
        val evicted = row(1).known(state = LocalState.EVICTED, cloud = CloudState.VERIFIED)
        val sdCard = row(2).known().copy(volume = "1234-ABCD")
        val plan = reconcile(listOf(evicted, sdCard), full())

        plan.isEmpty shouldBe true
    }

    @Test
    fun `identical files moved together each keep one item`() {
        val a = row(1, name = "x.jpg", size = 5, taken = 5).known()
        val b = row(2, name = "x.jpg", size = 5, taken = 5).known()
        val moved =
            listOf(
                row(3, name = "x.jpg", size = 5, taken = 5),
                row(4, name = "x.jpg", size = 5, taken = 5),
            )
        val scan = VolumeScan(volume, moved, setOf(3, 4))

        val plan = reconcile(listOf(a, b), scan)

        plan.updates.map { it.itemId } shouldContainExactlyInAnyOrder listOf(a.itemId, b.itemId)
        plan.inserts.shouldBeEmpty()
    }

    @Test
    fun `taken time falls back to modification time`() {
        row(1, taken = 0).copy(dateModifiedSeconds = 1_234).takenAtMillis shouldBe 1_234_000
        row(1).copy(dateTakenMillis = null, dateModifiedSeconds = 5).takenAtMillis shouldBe 5_000
    }

    @Test
    fun `a file Samsung moved into its trash folder keeps its own folder`() {
        folderBeforeTrash(".trash-storage/Pictures/SettleTest/") shouldBe "Pictures/SettleTest/"
        folderBeforeTrash("DCIM/Camera/") shouldBe "DCIM/Camera/"
    }
}
