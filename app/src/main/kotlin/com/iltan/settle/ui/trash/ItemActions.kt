package com.iltan.settle.ui.trash

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts.StartIntentSenderForResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import com.iltan.settle.R
import com.iltan.settle.appGraph
import com.iltan.settle.data.contentUri
import com.iltan.settle.data.db.MediaEntry
import com.iltan.settle.data.trash.DeletionRequest
import com.iltan.settle.domain.CloudState
import com.iltan.settle.ui.AppMessages
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Runs a [DeletionRequest]'s system confirmations one after another and then calls [onDone] with
 * whether all were approved. Android shows nothing when "Media management" is granted.
 */
@Composable
fun rememberDeletionRunner(
    onDone: (request: DeletionRequest, approved: Boolean) -> Unit
): (DeletionRequest) -> Unit {
    // Settle's records must change even if the screen goes away meanwhile.
    val appScope = LocalContext.current.appGraph.appScope
    val done by rememberUpdatedState(onDone)
    var pending by remember { mutableStateOf<Pair<DeletionRequest, Int>?>(null) }
    var waiting by remember { mutableStateOf<Pair<DeletionRequest, Int>?>(null) }
    val launcher =
        rememberLauncherForActivityResult(StartIntentSenderForResult()) { result ->
            val (request, index) = waiting ?: return@rememberLauncherForActivityResult
            waiting = null
            val approved = result.resultCode == Activity.RESULT_OK
            appScope.launch {
                request.steps[index].complete(approved)
                withContext(Dispatchers.Main) {
                    if (approved && index + 1 < request.steps.size) {
                        pending = request to index + 1
                    } else {
                        done(request, approved)
                    }
                }
            }
        }
    LaunchedEffect(pending) {
        val (request, index) = pending ?: return@LaunchedEffect
        pending = null
        waiting = request to index
        launcher.launch(IntentSenderRequest.Builder(request.steps[index].intentSender).build())
    }
    return { request ->
        if (request.steps.isEmpty()) done(request, true) else pending = request to 0
    }
}

/** What can be done with photos and videos chosen in a grid or open in the viewer. */
class ItemActions(
    /**
     * Moves the items to the trash; [onDeleted] runs once they are there, e.g. to end a selection.
     */
    val delete: (entries: List<MediaEntry>, onDeleted: () -> Unit) -> Unit,
    val backUp: (List<MediaEntry>) -> Unit,
    val share: (List<MediaEntry>) -> Unit,
)

/** The item actions of the whole app, provided by [SettleApp][com.iltan.settle.ui.SettleApp]. */
val LocalItemActions =
    staticCompositionLocalOf<ItemActions> { error("ItemActions are provided by SettleApp") }

/**
 * Delete with Undo, back up now, and share, reporting through the app's [messages]. Remembered at
 * the root, above navigation, so a deletion's system dialog and its Undo outlive the screen it
 * started on.
 */
@Composable
fun rememberItemActions(messages: AppMessages): ItemActions {
    val context = LocalContext.current
    val resources = LocalResources.current
    val graph = context.appGraph
    val scope = rememberCoroutineScope()
    val undoRunner = rememberDeletionRunner { _, _ -> }
    // The deletion under way: its entries, for the message, and what to do once it is done.
    var deleting by remember { mutableStateOf(emptyList<MediaEntry>() to {}) }
    val deleteRunner = rememberDeletionRunner { _, approved ->
        if (!approved) return@rememberDeletionRunner
        val (entries, deleted) = deleting
        deleted()
        val notBackedUp = entries.count { it.cloudState != CloudState.VERIFIED }
        val message =
            if (notBackedUp == 0) {
                resources.getQuantityString(R.plurals.trash_moved, entries.size, entries.size)
            } else {
                resources.getQuantityString(
                    R.plurals.trash_moved_not_backed_up,
                    notBackedUp,
                    notBackedUp,
                )
            }
        messages.offer(message, resources.getString(R.string.trash_undo)) {
            scope.launch { undoRunner(graph.deletion.restore(entries.map { it.id })) }
        }
    }
    return remember(graph) {
        ItemActions(
            delete = { entries, onDeleted ->
                deleting = entries to onDeleted
                scope.launch { deleteRunner(graph.deletion.delete(entries.map { it.id })) }
            },
            backUp = { entries ->
                scope.launch {
                    graph.cloudBackup.backUpNow(entries.map { it.id })
                    messages.show(
                        resources.getQuantityString(
                            R.plurals.backup_requested,
                            entries.size,
                            entries.size,
                        )
                    )
                }
            },
            share = { entries -> share(context, entries) },
        )
    }
}

private fun share(context: Context, entries: List<MediaEntry>) {
    if (entries.isEmpty()) return
    val uris = entries.map { it.contentUri }
    val types = entries.map { it.mimeType }.distinct()
    val type =
        types.singleOrNull()
            ?: types.map { it.substringBefore('/') }.distinct().singleOrNull()?.let { "$it/*" }
            ?: "*/*"
    val send =
        if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.single())
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE)
                .putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList<Uri>(uris))
        }
    send.setType(type).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    // Granted explicitly through ClipData: Android 18 stops adding it for sends by itself.
    send.clipData =
        ClipData.newRawUri(null, uris.first()).apply {
            uris.drop(1).forEach { addItem(ClipData.Item(it)) }
        }
    context.startActivity(Intent.createChooser(send, null))
}
