package com.iltan.settle.domain.backup

import com.iltan.settle.domain.CloudState
import com.iltan.settle.domain.MediaItemId

/** Why a backup stopped without a verified copy. Shown to the user; nothing is lost. */
enum class UploadProblem {
    AUTH_NEEDED,
    QUOTA_FULL,
    /** Network or Drive kept failing; trying again later continues where it stopped. */
    OFFLINE,
    ORIGINAL_UNAVAILABLE,
    CHANGED_DURING_UPLOAD,
    CHECKSUM_MISMATCH,
    FAILED,
}

/** The state of one item's upload, persisted after every step (ADR-002, ADR-004). */
data class UploadRecord(
    val itemId: MediaItemId,
    /** Reserved with `files.generateIds` before the first byte is sent. */
    val driveFileId: String,
    /** RESERVED, UPLOADING, UPLOADED, VERIFIED or SUSPECT. */
    val state: CloudState,
    /** The original as it was when this upload started. */
    val snapshot: FileSnapshot,
    val sessionUri: String? = null,
    val sessionStartedAtMillis: Long? = null,
    /** Bytes the server confirmed. Only for progress; the server's answer always wins. */
    val confirmedBytes: Long = 0,
    /** Digests of all bytes sent, set once the last chunk is accepted. */
    val sha256: String? = null,
    val md5: String? = null,
    val verifiedAtMillis: Long? = null,
    val problem: UploadProblem? = null,
) {
    /** Drive has created the file, so its reserved ID can't be used for other bytes. */
    val fileExists: Boolean
        get() =
            state == CloudState.UPLOADED ||
                state == CloudState.VERIFIED ||
                state == CloudState.SUSPECT
}

/** Digests computed over the bytes read from the phone. */
data class LocalDigest(val sizeBytes: Long, val sha256: String, val md5: String)

enum class Verification {
    /** Size, SHA-256 and MD5 all equal. */
    MATCH,
    /** Nothing disagrees, but Drive hasn't reported every value yet. */
    PENDING,
    MISMATCH,
}

/**
 * ADR-004: a copy is verified only when Drive's size, SHA-256 and MD5 all equal the bytes sent. MD5
 * alone never verifies; a missing value means "ask again later", a different one means "don't trust
 * this copy".
 */
fun verify(local: LocalDigest, remote: RemoteFile): Verification {
    val pairs =
        listOf(
            remote.sizeBytes?.toString() to local.sizeBytes.toString(),
            remote.sha256?.lowercase() to local.sha256,
            remote.md5?.lowercase() to local.md5,
        )
    return when {
        pairs.any { (theirs, ours) -> theirs != null && theirs != ours } -> Verification.MISMATCH
        pairs.any { (theirs, _) -> theirs == null } -> Verification.PENDING
        else -> Verification.MATCH
    }
}

/**
 * Exponential backoff as Drive's guide describes it: 2ⁿ⁻¹ seconds plus up to one second of jitter,
 * capped at [maxMillis].
 */
fun backoffMillis(attempt: Int, jitterMillis: Long, maxMillis: Long = 32_000): Long {
    require(attempt >= 1) { "Attempts count from 1" }
    val base = 1_000L shl (attempt - 1).coerceAtMost(15)
    return base.coerceAtMost(maxMillis) + jitterMillis
}
