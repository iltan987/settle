package com.iltan.settle.data.backup

import com.iltan.settle.data.drive.DriveRest
import com.iltan.settle.data.trash.TrashWork
import com.iltan.settle.domain.backup.BackupPause

/**
 * The maintenance job (every six hours): forgets items whose trash time is over, runs Drive changes
 * still queued (ADR-015), and lets backup resume once Drive has room again (R6).
 */
class BackupMaintenance(
    private val drive: DriveRest,
    private val preferences: BackupPreferences,
    private val notifications: BackupNotifications,
    private val trashWork: TrashWork? = null,
) {
    suspend fun run() {
        trashWork?.expire()
        trashWork?.runDriveChanges()
        if (preferences.current().blocked != BackupPause.QUOTA_FULL) return
        val about = drive.about()
        val limit = about.limitBytes ?: Long.MAX_VALUE
        if ((about.usageBytes ?: 0) < limit && preferences.unblock(BackupPause.QUOTA_FULL)) {
            notifications.clearPause()
        }
    }
}
