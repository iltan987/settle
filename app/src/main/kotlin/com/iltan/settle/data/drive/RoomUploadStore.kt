package com.iltan.settle.data.drive

import com.iltan.settle.data.db.BackupDao
import com.iltan.settle.data.db.DriveFolderEntity
import com.iltan.settle.data.db.SupersededFileEntity
import com.iltan.settle.data.db.UploadEntity
import com.iltan.settle.domain.MediaItemId
import com.iltan.settle.domain.backup.BackupItem
import com.iltan.settle.domain.backup.FileSnapshot
import com.iltan.settle.domain.backup.UploadRecord
import com.iltan.settle.domain.backup.UploadStore

/** The backup rules' persistent state, in Room. */
class RoomUploadStore(private val dao: BackupDao) : UploadStore {

    override suspend fun item(itemId: MediaItemId): BackupItem? =
        dao.item(itemId.value)?.let {
            BackupItem(
                id = itemId,
                kind = it.kind,
                displayName = it.displayName,
                mimeType = it.mimeType,
                relativePath = it.relativePath,
                takenAtMillis = it.takenAtMs,
                width = it.width,
                height = it.height,
                specialFormat = it.specialFormat,
            )
        }

    override suspend fun record(itemId: MediaItemId): UploadRecord? =
        dao.upload(itemId.value)?.toRecord()

    override suspend fun save(record: UploadRecord) = dao.saveUpload(record.toEntity())

    override suspend fun recordSuperseded(itemId: MediaItemId, driveFileId: String) =
        dao.addSuperseded(SupersededFileEntity(driveFileId, itemId.value))

    override suspend fun folderId(path: String): String? = dao.folderId(path)

    override suspend fun saveFolderId(path: String, id: String) =
        dao.upsertFolder(DriveFolderEntity(path, id))

    override suspend fun forgetFolderIds() = dao.deleteFolders()
}

fun UploadEntity.toRecord() =
    UploadRecord(
        itemId = checkNotNull(MediaItemId.parseOrNull(itemId)) { "Bad item ID $itemId" },
        driveFileId = driveFileId,
        state = state,
        snapshot = FileSnapshot(sizeBytes, dateModifiedS),
        sessionUri = sessionUri,
        sessionStartedAtMillis = sessionStartedAtMs,
        confirmedBytes = confirmedBytes,
        sha256 = sha256,
        md5 = md5,
        verifiedAtMillis = verifiedAtMs,
        problem = problem,
    )

fun UploadRecord.toEntity() =
    UploadEntity(
        itemId = itemId.value,
        driveFileId = driveFileId,
        state = state,
        sizeBytes = snapshot.sizeBytes,
        dateModifiedS = snapshot.dateModifiedSeconds,
        sessionUri = sessionUri,
        sessionStartedAtMs = sessionStartedAtMillis,
        confirmedBytes = confirmedBytes,
        sha256 = sha256,
        md5 = md5,
        verifiedAtMs = verifiedAtMillis,
        problem = problem,
    )
