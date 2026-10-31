package com.iltan.settle.domain.deletion

import com.iltan.settle.domain.MediaItemId
import com.iltan.settle.domain.backup.DriveFailure
import com.iltan.settle.domain.backup.FakeDrive
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class DriveChangesTest {

    private val drive = FakeDrive()
    private val store = FakeDriveOpStore()
    private var now = 1_000_000L
    private val item = MediaItemId.generate(nowMillis = 1)
    private val changes = DriveChanges(drive, store, clock = { now })

    private fun file(id: String) {
        drive.files[id] = FakeDrive.StoredFile(id, "$id.jpg", parentId = null, isFolder = false)
    }

    @Test
    fun `due operations run and leave the queue`() = runTest {
        file("a")
        file("b")
        file("c")
        drive.trashed += "b"
        store.enqueue(
            DriveOp("a", item, DriveOpKind.TRASH),
            DriveOp("b", item, DriveOpKind.UNTRASH),
            DriveOp("c", item, DriveOpKind.DELETE),
        )

        changes.run() shouldBe
            DriveChangesResult.Done(nextDueAtMillis = null, lostCopies = emptyList())

        drive.trashed shouldBe setOf("a")
        ("c" in drive.files) shouldBe false
        store.ops.values.shouldBeEmpty()
    }

    @Test
    fun `an operation that isn't due yet waits`() = runTest {
        file("a")
        store.enqueue(DriveOp("a", item, DriveOpKind.TRASH, notBeforeMillis = now + 60_000))

        changes.run() shouldBe DriveChangesResult.Done(now + 60_000, emptyList())
        drive.trashed.shouldBeEmpty()

        now += 60_000
        changes.run()
        drive.trashed shouldBe setOf("a")
    }

    @Test
    fun `a file that is already gone counts as trashed or deleted`() = runTest {
        store.enqueue(DriveOp("a", item, DriveOpKind.TRASH), DriveOp("b", item, DriveOpKind.DELETE))

        changes.run().shouldBeInstanceOf<DriveChangesResult.Done>().lostCopies.shouldBeEmpty()
        store.ops.values.shouldBeEmpty()
    }

    @Test
    fun `a copy that can't be untrashed because it is gone is reported`() = runTest {
        val op = DriveOp("a", item, DriveOpKind.UNTRASH)
        store.enqueue(op)

        changes.run().shouldBeInstanceOf<DriveChangesResult.Done>().lostCopies shouldContainExactly
            listOf(op)
        store.ops.values.shouldBeEmpty()
    }

    @Test
    fun `a failure waits and tries again, longer each time`() = runTest {
        file("a")
        store.enqueue(DriveOp("a", item, DriveOpKind.TRASH))
        drive.failNext("setTrashed", DriveFailure.TRANSIENT, times = 2)

        changes.run() shouldBe DriveChangesResult.Done(now + 60_000, emptyList())
        now += 60_000
        changes.run() shouldBe DriveChangesResult.Done(now + 120_000, emptyList())
        now += 120_000
        changes.run()

        drive.trashed shouldBe setOf("a")
        store.ops.values.shouldBeEmpty()
    }

    @Test
    fun `a lost answer is harmless because every operation can run twice`() = runTest {
        file("a")
        store.enqueue(DriveOp("a", item, DriveOpKind.TRASH))
        drive.loseNextAnswer("setTrashed")

        changes.run()
        now += 60_000
        changes.run()

        drive.trashed shouldBe setOf("a")
        store.ops.values.shouldBeEmpty()
    }

    @Test
    fun `sign-in trouble stops the run and keeps the queue`() = runTest {
        file("a")
        store.enqueue(DriveOp("a", item, DriveOpKind.TRASH))
        drive.failNext("setTrashed", DriveFailure.AUTH_NEEDED)

        changes.run() shouldBe DriveChangesResult.AuthNeeded
        store.ops.values.single().attempts shouldBe 0
    }

    @Test
    fun `retry waits are capped at six hours`() {
        DriveChanges.retryDelayMillis(1) shouldBe 60_000
        DriveChanges.retryDelayMillis(30) shouldBe 6 * 60 * 60 * 1000L
    }
}
