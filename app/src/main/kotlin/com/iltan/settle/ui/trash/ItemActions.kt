package com.iltan.settle.ui.trash

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
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
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.iltan.settle.R
import com.iltan.settle.appGraph
import com.iltan.settle.data.contentUri
import com.iltan.settle.data.db.MediaEntry
import com.iltan.settle.data.trash.DeletionRequest
import com.iltan.settle.domain.CloudState
import com.iltan.settle.ui.AppMessages
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
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

/**
 * A deletion asked for on a screen: [onDeleted] runs once the items are in the trash, e.g. to end a
 * selection, and [onRestored] after Undo brought them back.
 */
class DeleteRequest(
    val entries: List<MediaEntry>,
    val onRestored: () -> Unit = {},
    val onDeleted: () -> Unit = {},
)

/** What can be done with photos and videos chosen in a grid or open in the viewer. */
class ItemActions(
    /** Moves the items to the trash, with Undo (R12). */
    val delete: (DeleteRequest) -> Unit,
    val backUp: (List<MediaEntry>) -> Unit,
    val share: (List<MediaEntry>) -> Unit,
    /** Marks the items as favourites, or unmarks them with false. */
    val favorite: (entries: List<MediaEntry>, favorite: Boolean) -> Unit = { _, _ -> },
)

/** The item actions of the whole app, provided by [SettleApp][com.iltan.settle.ui.SettleApp]. */
val LocalItemActions =
    staticCompositionLocalOf<ItemActions> { error("ItemActions are provided by SettleApp") }

/** Whether to explain Android's confirmation before a delete (E1): once, and only if it asks. */
fun explainBeforeDeleting(canDeleteWithoutAsking: Boolean, explainerShown: Boolean): Boolean =
    !canDeleteWithoutAsking && !explainerShown

/** The snackbar's text after moving items to the trash, as a plural and its quantity (E3, E5). */
data class DeletedMessage(val plurals: Int, val quantity: Int)

fun deletedMessage(count: Int, notBackedUp: Int): DeletedMessage =
    when (notBackedUp) {
        0 -> DeletedMessage(R.plurals.trash_moved, count)
        // "These weren't backed up": no number to repeat when it is all of them.
        count -> DeletedMessage(R.plurals.trash_moved_none_backed_up, count)
        else -> DeletedMessage(R.plurals.trash_moved_not_backed_up, notBackedUp)
    }

/**
 * Delete with Undo, back up now, and share, reporting through the app's [messages]. Remembered at
 * the root, above navigation, so a deletion's system dialog and its Undo outlive the screen it
 * started on. The first delete explains Android's confirmation once (E1).
 */
@Composable
fun rememberItemActions(messages: AppMessages): ItemActions {
    val context = LocalContext.current
    val resources = LocalResources.current
    val graph = context.appGraph
    val scope = rememberCoroutineScope()
    // The deletion under way, for its message and callbacks once Android has confirmed it.
    var deleting by remember { mutableStateOf<DeleteRequest?>(null) }
    // A deletion waiting for the user to read the explainer.
    var explaining by remember { mutableStateOf<DeleteRequest?>(null) }
    val explainerShown by remember {
        graph.uiPreferences.prefs.map { it.mediaExplainerShown }
    }
        .collectAsStateWithLifecycle(initialValue = null)

    // The deletion being undone, told once Android has put its items back.
    var restoring by remember { mutableStateOf<DeleteRequest?>(null) }
    val undoRunner = rememberDeletionRunner { _, approved ->
        val request = restoring ?: return@rememberDeletionRunner
        restoring = null
        if (!approved) return@rememberDeletionRunner
        messages.note(resources.getString(R.string.trash_restored))
        request.onRestored()
    }
    val deleteRunner = rememberDeletionRunner { _, approved ->
        // On Deny nothing happened, so there is nothing to say (E2).
        val request = deleting ?: return@rememberDeletionRunner
        deleting = null
        if (!approved) return@rememberDeletionRunner
        request.onDeleted()
        val entries = request.entries
        val message =
            deletedMessage(entries.size, entries.count { it.cloudState != CloudState.VERIFIED })
        messages.offer(
            resources.getQuantityString(message.plurals, message.quantity, message.quantity),
            resources.getString(R.string.trash_undo),
        ) {
            restoring = request
            scope.launch { undoRunner(graph.deletion.restore(entries.map { it.id })) }
        }
    }
    fun run(request: DeleteRequest) {
        deleting = request
        scope.launch { deleteRunner(graph.deletion.delete(request.entries.map { it.id })) }
    }
    val openMediaAccess =
        rememberLauncherForActivityResult(StartActivityForResult()) {
            // Granted or not, the delete goes on; Android asks if it still has to.
            explaining?.let(::run)
            explaining = null
        }
    explaining?.let { request ->
        MediaAccessSheet(
            onAllow = {
                graph.appScope.launch { graph.uiPreferences.mediaExplainerShown() }
                openMediaAccess.launch(
                    Intent(
                        Settings.ACTION_REQUEST_MANAGE_MEDIA,
                        "package:${context.packageName}".toUri(),
                    )
                )
            },
            onKeepConfirming = {
                graph.appScope.launch { graph.uiPreferences.mediaExplainerShown() }
                explaining = null
                run(request)
            },
            // Closed without a choice: nothing is deleted, and it explains again next time.
            onDismiss = { explaining = null },
        )
    }
    val currentExplainerShown by rememberUpdatedState(explainerShown)
    return remember(graph) {
        ItemActions(
            delete = { request ->
                val explain =
                    explainBeforeDeleting(
                        graph.deletion.canDeleteWithoutAsking(),
                        // Until the preference is read, assume it was shown: never block deleting.
                        currentExplainerShown ?: true,
                    )
                if (explain) explaining = request else run(request)
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
            favorite = { entries, favorite ->
                graph.appScope.launch { graph.library.setFavorites(entries, favorite) }
            },
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
