package com.iltan.settle.ui.trash

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import com.iltan.settle.R
import com.iltan.settle.appGraph
import com.iltan.settle.data.db.TrashedEntry
import com.iltan.settle.domain.LocalState
import com.iltan.settle.domain.deletion.VanishedDecision
import com.iltan.settle.ui.AppMessages
import com.iltan.settle.ui.folders.folderNames
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What can be done in the trash, each saying afterwards what it did (F3–F8, G). */
class TrashActions(
    /** Back to the phone: out of its trash, or downloaded from Drive (F3, F5). */
    val restore: (List<TrashedEntry>) -> Unit,
    /** For good, after the screen's confirmation (F4). */
    val deleteForever: (List<TrashedEntry>) -> Unit,
    val emptyTrash: () -> Unit,
    /**
     * The user's answer for items that left the phone without Settle (F8): these items, or with
     * null the mass disappearance (G). [onViewTrash] is offered once Drive copies moved.
     */
    val decide:
        (
            ids: List<String>?,
            count: Int,
            decision: VanishedDecision,
            onViewTrash: (() -> Unit)?,
        ) -> Unit,
)

/** The trash actions of the whole app, provided by [SettleApp][com.iltan.settle.ui.SettleApp]. */
val LocalTrashActions =
    staticCompositionLocalOf<TrashActions> { error("TrashActions are provided by SettleApp") }

/** Where restored items went: "Camera", or null when they went back to several folders. */
fun restoredFolder(entries: List<TrashedEntry>, rootName: String): String? {
    val path = entries.map { it.relativePath }.distinct().singleOrNull() ?: return null
    return folderNames(listOf(path), rootName)[path]
}

/** "3 items restored to Camera", or without the folder when they went to several (F3). */
fun restoredMessage(resources: Resources, count: Int, folder: String?): String =
    if (folder != null) {
        resources.getQuantityString(R.plurals.trash_restored_to, count, count, folder)
    } else {
        resources.getQuantityString(R.plurals.trash_restored_count, count, count)
    }

/**
 * Restore, Delete forever and the answers for items deleted elsewhere, remembered at the root like
 * [rememberItemActions], so Android's dialogs and the Undo they offer outlive the trash screen.
 */
@Composable
fun rememberTrashActions(messages: AppMessages): TrashActions {
    val context = LocalContext.current
    val resources = LocalResources.current
    val graph = context.appGraph
    val scope = rememberCoroutineScope()
    val root = resources.getString(R.string.folder_root)

    // The restore under way, for its message once Android has confirmed it.
    var restoring by remember { mutableStateOf<List<TrashedEntry>>(emptyList()) }
    var undoing by remember { mutableStateOf(false) }
    val undoRunner = rememberDeletionRunner { _, approved ->
        if (approved && undoing) messages.note(resources.getString(R.string.trash_moved_back))
        undoing = false
    }
    val restoreRunner = rememberDeletionRunner { _, approved ->
        val fromPhone = restoring
        restoring = emptyList()
        // Downloads from Drive show their progress in the trash and report when done (F5, F6).
        if (!approved || fromPhone.isEmpty()) return@rememberDeletionRunner
        messages.offer(
            restoredMessage(resources, fromPhone.size, restoredFolder(fromPhone, root)),
            resources.getString(R.string.trash_undo),
        ) {
            undoing = true
            scope.launch { undoRunner(graph.deletion.delete(fromPhone.map { it.id })) }
        }
    }
    // How many Delete forever removes, for its message once Android has confirmed it.
    var deleting by remember { mutableStateOf<Int?>(null) }
    val deleteRunner = rememberDeletionRunner { _, approved ->
        val count = deleting
        deleting = null
        if (!approved || count == null) return@rememberDeletionRunner
        messages.note(
            if (count == EMPTIED) resources.getString(R.string.trash_emptied)
            else resources.getQuantityString(R.plurals.trash_deleted_forever, count, count)
        )
    }
    return remember(graph) {
        TrashActions(
            restore = { entries ->
                restoring = entries.filter {
                    it.localState == LocalState.TRASHED && it.mediaStoreId != null
                }
                scope.launch { restoreRunner(graph.deletion.restore(entries.map { it.id })) }
            },
            deleteForever = { entries ->
                deleting = entries.size
                scope.launch { deleteRunner(graph.deletion.deleteForever(entries.map { it.id })) }
            },
            emptyTrash = {
                deleting = EMPTIED
                scope.launch { deleteRunner(graph.deletion.emptyTrash()) }
            },
            decide = { ids, count, decision, onViewTrash ->
                graph.appScope.launch {
                    graph.deletion.decide(ids, decision)
                    withContext(Dispatchers.Main) {
                        decidedMessage(
                            messages,
                            resources,
                            ids == null,
                            count,
                            decision,
                            onViewTrash,
                        )
                    }
                }
            },
        )
    }
}

private fun decidedMessage(
    messages: AppMessages,
    resources: Resources,
    mass: Boolean,
    count: Int,
    decision: VanishedDecision,
    onViewTrash: (() -> Unit)?,
) {
    when (decision) {
        VanishedDecision.KEEP_DRIVE_COPIES ->
            messages.show(
                resources.getQuantityString(
                    if (mass) R.plurals.mass_kept else R.plurals.waiting_kept,
                    count,
                    count,
                )
            )
        VanishedDecision.MOVE_DRIVE_COPIES_TO_TRASH -> {
            val text = resources.getQuantityString(R.plurals.drive_copies_moved, count, count)
            if (onViewTrash != null) {
                messages.show(text, resources.getString(R.string.trash_view), onViewTrash)
            } else {
                messages.show(text)
            }
        }
    }
}

/** [rememberTrashActions]' marker for "Empty trash", whose message names no count. */
private const val EMPTIED = -1
