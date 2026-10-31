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
        check(isSandboxFolder(rootFolder)) { "Only a debug build's own folder may be reset" }
        val id = dao.folderId(rootFolder) ?: drive.findFolder(rootFolder, parentId = null)
        if (id != null) drive.trash(id)
        dao.forgetAllUploads()
    }

    companion object {
        /** The phone's and the emulator's debug folders; never the real `Settle/`. */
        fun isSandboxFolder(name: String): Boolean =
            name == "Settle (debug)" || name == "Settle (emulator)"
    }
}
