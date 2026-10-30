package com.iltan.settle.domain

/** Where the original bytes are on the phone (ADR-002). */
enum class LocalState {
    /** The original is on the phone and visible to other apps. */
    PRESENT,
    /** The original is in the phone's system trash. */
    TRASHED,
    /** Settle removed the original after verifying the cloud copy; only a preview remains. */
    EVICTED,
    /** The original disappeared without Settle removing it, e.g. deleted in another app. */
    MISSING,
}

/** State of the original's copy in Google Drive (ADR-002). */
enum class CloudState {
    /** No cloud copy exists or has been planned yet. */
    NONE,
    /** A Drive file ID is reserved and the upload is queued. */
    RESERVED,
    UPLOADING,
    /** Drive accepted the bytes, but checksums are not confirmed yet. */
    UPLOADED,
    /** Size and checksums match the bytes that were sent (ADR-004). */
    VERIFIED,
    /** The cloud copy failed verification or an audit; it must not be relied on. */
    SUSPECT,
    TRASHED,
    DELETED,
}

/** The single status shown on an item in the timeline. */
enum class ItemStatus {
    /** On the phone, inside the grace period before backup. */
    WAITING,
    /** On the phone, eligible for backup and queued. */
    QUEUED,
    UPLOADING,
    /** On the phone and verified in the cloud. */
    BACKED_UP,
    /** Only a preview on the phone; the original is verified in the cloud. */
    CLOUD_ONLY,
    IN_TRASH,
    /** Something is wrong that the user should look at; never resolved destructively. */
    NEEDS_ATTENTION,
}

/**
 * Combines the independent sub-states of an item into the one status the UI shows.
 *
 * Pure function so every combination can be tested. Combinations that should never occur (for
 * example an evicted original without a verified cloud copy) map to [ItemStatus.NEEDS_ATTENTION]
 * instead of throwing, so the user sees the problem rather than a crash.
 */
fun deriveStatus(
    local: LocalState,
    cloud: CloudState,
    inTrash: Boolean,
    uploadEligibleAtMillis: Long,
    nowMillis: Long,
): ItemStatus {
    if (inTrash) return ItemStatus.IN_TRASH
    if (cloud == CloudState.SUSPECT) return ItemStatus.NEEDS_ATTENTION

    return when (local) {
        LocalState.PRESENT ->
            when (cloud) {
                CloudState.NONE,
                CloudState.RESERVED ->
                    if (nowMillis < uploadEligibleAtMillis) ItemStatus.WAITING
                    else ItemStatus.QUEUED
                CloudState.UPLOADING,
                CloudState.UPLOADED -> ItemStatus.UPLOADING
                CloudState.VERIFIED -> ItemStatus.BACKED_UP
                // The cloud copy is gone but the phone still has the original: back it up again.
                CloudState.TRASHED,
                CloudState.DELETED -> ItemStatus.QUEUED
                CloudState.SUSPECT -> ItemStatus.NEEDS_ATTENTION
            }
        LocalState.EVICTED ->
            if (cloud == CloudState.VERIFIED) ItemStatus.CLOUD_ONLY else ItemStatus.NEEDS_ATTENTION
        LocalState.TRASHED -> ItemStatus.IN_TRASH
        LocalState.MISSING -> ItemStatus.NEEDS_ATTENTION
    }
}
