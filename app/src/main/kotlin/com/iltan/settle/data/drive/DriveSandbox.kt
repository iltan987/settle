package com.iltan.settle.data.drive

import com.iltan.settle.data.db.BackupDao

/**
 * Debug builds only (ADR-008): moves the debug Drive folder to the Drive trash, where it stays
 * restorable for 30 days, and forgets every upload, so backup tests can start over.
 */
class DriveSandbox(
    private val drive: DriveRest,
    private val dao: BackupDao,
    private val rootFolder: String,
) {
    suspend fun reset() {
        check(rootFolder.endsWith("(debug)")) { "Only the debug folder may be reset" }
        val id = dao.folderId(rootFolder) ?: drive.findFolder(rootFolder, parentId = null)
        if (id != null) drive.trash(id)
        dao.forgetAllUploads()
    }
}
