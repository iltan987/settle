package com.iltan.settle.ui.timeline

import com.iltan.settle.domain.backup.BackupHeadline
import com.iltan.settle.domain.backup.BackupPause
import org.junit.Assert.assertEquals
import org.junit.Test

class BackupNoticesTest {

    @Test
    fun theFirstBackupIsAnnouncedOnlyWhileSomethingWaitsForIt() {
        assertEquals(
            listOf(BackupNotice.FirstBackup(602)),
            backupNotices(BackupHeadline.FirstBackupPending(602), breakerCount = 0),
        )
        assertEquals(
            emptyList<BackupNotice>(),
            backupNotices(BackupHeadline.FirstBackupPending(0), breakerCount = 0),
        )
    }

    @Test
    fun aMassDisappearanceComesBeforeEverythingElse() {
        assertEquals(
            listOf(BackupNotice.Mass(40), BackupNotice.Pause(BackupPause.BY_USER, 3)),
            backupNotices(BackupHeadline.Paused(BackupPause.BY_USER, 3), breakerCount = 40),
        )
    }
}
