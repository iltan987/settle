package com.iltan.settle.domain.backup

import com.iltan.settle.domain.CloudState
import com.iltan.settle.domain.ItemStatus

/**
 * The status a grid cell shows for an item whose original is on the phone (R11). It follows the
 * backup plan's [decision] for the item, so the grid and the app's backup status count the same
 * items. Only an upload going on right now is Uploading; an interrupted one waits or is queued
 * again, as the plan says.
 */
fun cellStatus(cloud: CloudState, decision: BackupDecision?, uploading: Boolean): ItemStatus =
    when {
        uploading -> ItemStatus.UPLOADING
        cloud == CloudState.VERIFIED -> ItemStatus.BACKED_UP
        else ->
            when (decision) {
                BackupDecision.Ready -> ItemStatus.QUEUED
                is BackupDecision.WaitUntil -> ItemStatus.WAITING
                BackupDecision.NeedsAttention -> ItemStatus.NEEDS_ATTENTION
                // Not chosen for backup, or automatic backup is off.
                BackupDecision.Skip,
                null -> ItemStatus.NOT_BACKED_UP
            }
    }

/** Whether a grid cell shows [status] (B5): some statuses only matter in some places. */
fun cellShowsStatus(status: ItemStatus, connected: Boolean, selecting: Boolean): Boolean =
    when (status) {
        // Backed up is the normal state, so it has no mark.
        ItemStatus.BACKED_UP -> false
        // Before Drive is connected every cell would carry one; the notice explains it instead.
        ItemStatus.NOT_BACKED_UP -> connected
        // Quiet, expected states: only when choosing what to act on.
        ItemStatus.WAITING,
        ItemStatus.QUEUED -> selecting
        else -> true
    }
