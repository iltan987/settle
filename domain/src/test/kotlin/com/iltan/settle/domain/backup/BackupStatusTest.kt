package com.iltan.settle.domain.backup

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class BackupStatusTest {

    private fun pause(
        connected: Boolean = true,
        blocked: BackupPause? = null,
        originals: Boolean = true,
        byUser: Boolean = false,
    ) = backupPause(connected, blocked, { originals }, byUser)

    @Test
    fun nothingStopsBackupByDefault() {
        pause().shouldBeNull()
    }

    @Test
    fun notConnectedComesFirstEvenWithAStaleBlock() {
        pause(
                connected = false,
                blocked = BackupPause.AUTH_NEEDED,
                originals = false,
                byUser = true,
            )
            .shouldBe(BackupPause.NOT_CONNECTED)
    }

    @Test
    fun locationAccessIsNotAskedWhenNotConnected() {
        backupPause(false, null, { error("asked") }, false).shouldBe(BackupPause.NOT_CONNECTED)
    }

    @Test
    fun googleProblemsComeBeforeLocationAccessAndTheUsersPause() {
        pause(blocked = BackupPause.AUTH_NEEDED, originals = false, byUser = true)
            .shouldBe(BackupPause.AUTH_NEEDED)
        pause(blocked = BackupPause.QUOTA_FULL, originals = false, byUser = true)
            .shouldBe(BackupPause.QUOTA_FULL)
    }

    @Test
    fun locationAccessComesBeforeTheUsersPause() {
        pause(originals = false, byUser = true).shouldBe(BackupPause.NO_ORIGINAL_ACCESS)
        pause(byUser = true).shouldBe(BackupPause.BY_USER)
    }

    private val run = RunProgress(12, 40, 300, 1000, bytesPerSecond = 100)

    private fun inputs(
        pause: BackupPause? = null,
        attention: Int = 0,
        backedUp: Int = 256,
        waiting: Int = 3,
        queued: Int = 6,
        run: RunProgress? = null,
    ) = BackupStatusInputs(pause, attention, backedUp, waiting, queued, run)

    @Test
    fun attentionOutranksEveryPauseAndARun() {
        backupHeadline(inputs(attention = 2, pause = BackupPause.QUOTA_FULL, run = run))
            .shouldBe(BackupHeadline.NeedsAttention(2))
    }

    @Test
    fun aPauseOutranksARunAndCountsWhatWaits() {
        backupHeadline(inputs(pause = BackupPause.BY_USER, run = run))
            .shouldBe(BackupHeadline.Paused(BackupPause.BY_USER, waitingItems = 9))
    }

    @Test
    fun aRunShowsWhenNothingIsWrong() {
        backupHeadline(inputs(run = run)).shouldBe(BackupHeadline.BackingUp(run))
    }

    @Test
    fun neverAllInDriveBeforeTheFirstVerifiedUpload() {
        backupHeadline(inputs(backedUp = 0)).shouldBe(BackupHeadline.FirstBackupPending(9))
    }

    @Test
    fun allInDriveCountsWhatIsSafeAndWhatComes() {
        backupHeadline(inputs()).shouldBe(BackupHeadline.AllInDrive(256, upcoming = 9))
    }

    @Test
    fun aRunsShareIsByBytesAndItsTimeLeftByTheSpeed() {
        run.fraction.shouldBe(0.3f)
        run.secondsLeft.shouldBe(7)
        run.copy(totalBytes = 0).fraction.shouldBe(0.3f)
        run.copy(bytesPerSecond = null).secondsLeft.shouldBeNull()
        run.copy(sentBytes = 2000).fraction.shouldBe(1f)
        run.copy(sentBytes = 2000).secondsLeft.shouldBe(0)
    }
}
