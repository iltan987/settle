package com.iltan.settle.domain.backup

import com.iltan.settle.domain.CloudState
import com.iltan.settle.domain.LocalState
import com.iltan.settle.domain.MediaItemId
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class BackupPlanTest {

    private val now = 1_800_000_000_000L
    private val settings = BackupSettings()
    private var nextId = 1L

    private fun candidate(
        addedAgo: Long = 2 * DEFAULT_GRACE_MILLIS,
        folderBacksUp: Boolean = true,
        local: LocalState = LocalState.PRESENT,
        cloud: CloudState = CloudState.NONE,
        takenAt: Long = now - addedAgo,
        size: Long = 100,
        problem: UploadProblem? = null,
        retryAt: Long? = null,
        attempts: Int = 0,
        requestedAt: Long? = null,
    ) =
        BackupCandidate(
            itemId = MediaItemId.generate(nowMillis = nextId++),
            localState = local,
            sizeBytes = size,
            takenAtMillis = takenAt,
            addedAtMillis = now - addedAgo,
            folderBacksUp = folderBacksUp,
            cloudState = cloud,
            problem = problem,
            retryAtMillis = retryAt,
            attempts = attempts,
            requestedAtMillis = requestedAt,
        )

    private fun decide(c: BackupCandidate, s: BackupSettings = settings) = backupDecision(c, s, now)

    @Test
    fun `only the camera folder is backed up by default, and the user's choice wins`() {
        backsUpByDefault("DCIM/Camera/") shouldBe true
        backsUpByDefault("DCIM/Screenshots/") shouldBe false
        backsUpByDefault("Pictures/WhatsApp/") shouldBe false
        folderBacksUp("DCIM/Screenshots/", userChoice = true) shouldBe true
        folderBacksUp("DCIM/Camera/", userChoice = false) shouldBe false
        folderBacksUp("DCIM/Camera/", userChoice = null) shouldBe true
    }

    @Test
    fun `a new item waits exactly until its grace period ends (R2)`() {
        val justAdded = candidate(addedAgo = 0)
        decide(justAdded) shouldBe BackupDecision.WaitUntil(now + DEFAULT_GRACE_MILLIS)
        decide(candidate(addedAgo = DEFAULT_GRACE_MILLIS - 1)) shouldBe
            BackupDecision.WaitUntil(now + 1)
        decide(candidate(addedAgo = DEFAULT_GRACE_MILLIS)) shouldBe BackupDecision.Ready
        decide(justAdded, settings.copy(graceMillis = 0)) shouldBe BackupDecision.Ready
    }

    @Test
    fun `an item no longer on the phone is never backed up (R2)`() {
        decide(candidate(local = LocalState.MISSING)) shouldBe BackupDecision.Skip
        decide(candidate(local = LocalState.TRASHED)) shouldBe BackupDecision.Skip
        decide(candidate(local = LocalState.MISSING, requestedAt = now)) shouldBe
            BackupDecision.Skip
    }

    @Test
    fun `verified items, folders that are off and automatic backup off skip`() {
        decide(candidate(cloud = CloudState.VERIFIED)) shouldBe BackupDecision.Skip
        decide(candidate(folderBacksUp = false)) shouldBe BackupDecision.Skip
        decide(candidate(), settings.copy(automatic = false)) shouldBe BackupDecision.Skip
    }

    @Test
    fun `a request skips the grace period, the folder choice and retry waits (R3)`() {
        val requested =
            candidate(
                addedAgo = 0,
                folderBacksUp = false,
                problem = UploadProblem.CHANGED_DURING_UPLOAD,
                retryAt = now + HOUR_MILLIS,
                requestedAt = now,
            )
        decide(requested) shouldBe BackupDecision.Ready
        decide(requested, settings.copy(automatic = false)) shouldBe BackupDecision.Ready
    }

    @Test
    fun `a failed item waits for its retry time`() {
        decide(candidate(problem = UploadProblem.OFFLINE, retryAt = now + 5)) shouldBe
            BackupDecision.WaitUntil(now + 5)
        decide(candidate(problem = UploadProblem.OFFLINE, retryAt = now)) shouldBe
            BackupDecision.Ready
    }

    @Test
    fun `a copy that failed verification is retried once, then left to the user`() {
        retryAtMillis(UploadProblem.CHECKSUM_MISMATCH, attempts = 1, now) shouldBe now + HOUR_MILLIS
        retryAtMillis(UploadProblem.CHECKSUM_MISMATCH, attempts = 2, now).shouldBeNull()
        decide(
            candidate(
                cloud = CloudState.SUSPECT,
                problem = UploadProblem.CHECKSUM_MISMATCH,
                attempts = 2,
            )
        ) shouldBe BackupDecision.NeedsAttention
        decide(
            candidate(
                cloud = CloudState.SUSPECT,
                problem = UploadProblem.CHECKSUM_MISMATCH,
                attempts = 2,
                requestedAt = now,
            )
        ) shouldBe BackupDecision.Ready
    }

    @Test
    fun `retry waits suit each problem`() {
        retryAtMillis(UploadProblem.AUTH_NEEDED, 1, now).shouldBeNull()
        retryAtMillis(UploadProblem.QUOTA_FULL, 1, now).shouldBeNull()
        retryAtMillis(UploadProblem.OFFLINE, 1, now) shouldBe now + 15 * 60 * 1000
        retryAtMillis(UploadProblem.CHANGED_DURING_UPLOAD, 1, now) shouldBe now + HOUR_MILLIS
        retryAtMillis(UploadProblem.ORIGINAL_UNAVAILABLE, 1, now) shouldBe now + 6 * HOUR_MILLIS
        (1..7).map { retryAtMillis(UploadProblem.FAILED, it, now)!! - now } shouldBe
            listOf(1L, 2, 4, 8, 16, 24, 24).map { it * HOUR_MILLIS }
    }

    @Test
    fun `the queue puts requests first in request order, then the newest photos`() {
        val old = candidate(takenAt = now - 3_000)
        val new = candidate(takenAt = now - 1_000)
        val secondRequest = candidate(takenAt = now - 5_000, requestedAt = now - 10)
        val firstRequest = candidate(takenAt = now - 9_000, requestedAt = now - 20)
        planBackup(listOf(old, secondRequest, new, firstRequest), settings, null, now)
            .queue shouldContainExactly listOf(firstRequest, secondRequest, new, old)
    }

    @Test
    fun `ready items run now on Wi-Fi and the charger, with their size as the estimate`() {
        val plan =
            planBackup(listOf(candidate(size = 10), candidate(size = 32)), settings, null, now)
        plan.automatic shouldBe PlannedRun(now, RunConditions(true, true), estimatedBytes = 42)
        plan.requested.shouldBeNull()
    }

    @Test
    fun `with nothing ready the automatic run waits for the earliest grace end`() {
        val plan =
            planBackup(
                listOf(candidate(addedAgo = 10), candidate(addedAgo = 20)),
                settings,
                null,
                now,
            )
        plan.queue.shouldBeEmpty()
        plan.waiting shouldBe 2
        plan.automatic shouldBe
            PlannedRun(now + DEFAULT_GRACE_MILLIS - 20, RunConditions(true, true), 0)
    }

    @Test
    fun `nothing to do schedules nothing`() {
        val plan = planBackup(listOf(candidate(cloud = CloudState.VERIFIED)), settings, null, now)
        plan.automatic.shouldBeNull()
        plan.requested.shouldBeNull()
    }

    @Test
    fun `settings shape the run conditions`() {
        val plan =
            planBackup(
                listOf(candidate()),
                settings.copy(onlyWhileCharging = false, useMobileData = true),
                null,
                now,
            )
        plan.automatic?.conditions shouldBe
            RunConditions(unmeteredNetwork = false, charging = false)
    }

    @Test
    fun `a request runs at once without the charger, but on Wi-Fi unless mobile data is allowed`() {
        val requested = candidate(size = 7, requestedAt = now)
        val plan = planBackup(listOf(requested), settings, null, now)
        plan.requested shouldBe PlannedRun(now, RunConditions(true, charging = false), 7)
        // The automatic run takes it too, if the requested run never got to finish it.
        plan.automatic shouldBe PlannedRun(now, RunConditions(true, charging = true), 7)
        planBackup(listOf(requested), settings.copy(useMobileData = true), null, now)
            .requested
            ?.conditions shouldBe RunConditions(unmeteredNetwork = false, charging = false)
    }

    @Test
    fun `a pause stops every run but still reports the queue (R6)`() {
        val items = listOf(candidate(), candidate(requestedAt = now))
        for (pause in BackupPause.entries) {
            val plan = planBackup(items, settings, pause, now)
            plan.pause shouldBe pause
            plan.automatic.shouldBeNull()
            plan.requested.shouldBeNull()
            plan.queue.size shouldBe 2
        }
        planBackup(items, settings.copy(paused = true), null, now).pause shouldBe
            BackupPause.BY_USER
    }

    @Test
    fun `items needing attention are counted, not queued`() {
        val plan =
            planBackup(
                listOf(
                    candidate(
                        cloud = CloudState.SUSPECT,
                        problem = UploadProblem.CHECKSUM_MISMATCH,
                        attempts = 2,
                    )
                ),
                settings,
                null,
                now,
            )
        plan.needsAttention shouldBe 1
        plan.queue.shouldBeEmpty()
        plan.automatic.shouldBeNull()
    }
}
