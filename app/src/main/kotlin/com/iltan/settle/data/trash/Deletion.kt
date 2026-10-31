package com.iltan.settle.data.trash

import android.content.IntentSender
import com.iltan.settle.data.backup.BackupPreferences
import com.iltan.settle.data.db.TrashDao
import com.iltan.settle.data.db.TrashedEntry
import com.iltan.settle.data.db.VanishedEntry
import com.iltan.settle.domain.MediaItemId
import com.iltan.settle.domain.deletion.DriveOp
import com.iltan.settle.domain.deletion.DriveOpKind
import com.iltan.settle.domain.deletion.ExternalDeletionPolicy
import com.iltan.settle.domain.deletion.TrashChanges
import com.iltan.settle.domain.deletion.TrashItem
import com.iltan.settle.domain.deletion.VanishedDecision
import com.iltan.settle.domain.deletion.VanishedReason
import com.iltan.settle.domain.deletion.decideVanished
import com.iltan.settle.domain.deletion.phoneFilesToTrash
import com.iltan.settle.domain.deletion.planDeleteForever
import com.iltan.settle.domain.deletion.planRestore
import com.iltan.settle.domain.deletion.trashedInSettle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * One system confirmation the screen must show, by starting [intentSender] for its result and
 * passing the outcome to [complete]. Android shows nothing when "Media management" is granted, but
 * the request must still be started.
 */
class PhoneStep(val intentSender: IntentSender, private val onResult: suspend (Boolean) -> Unit) {
    suspend fun complete(approved: Boolean) = onResult(approved)
}

/** What a deletion or restore asks of the screen: zero or more system confirmations, in order. */
data class DeletionRequest(val steps: List<PhoneStep>, val itemCount: Int)

/**
 * Deleting, restoring and the trash, for the screens (R12–R14, ADR-015). Phone changes go through
 * MediaStore's own requests; Settle's records change only once Android confirms them, and Drive
 * changes are queued so they work offline.
 */
class Deletion(
    private val dao: TrashDao,
    private val store: TrashStore,
    private val phone: PhoneTrash,
    private val preferences: BackupPreferences,
    private val betweenScans: suspend (suspend () -> Unit) -> Unit,
    /** Arms the jobs for queued Drive changes and replans backup. */
    private val reschedule: suspend () -> Unit,
    /** Starts the restore download job; false if Android refused it. */
    private val startRestore: suspend () -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    val trash: Flow<List<TrashedEntry>> = dao.observeTrash()

    /** Items that left the phone without Settle and wait for the user's decision. */
    val undecided: Flow<List<VanishedEntry>> = dao.observeUndecided()

    /** The mass disappearance waiting for the user (R14), as a count; 0 when there is none. */
    val breakerCount: Flow<Int> = undecided.map { list ->
        list.count { it.reason == VanishedReason.BREAKER }
    }

    val policy: Flow<ExternalDeletionPolicy> = preferences.choices.map { it.externalDeletion }

    fun canDeleteWithoutAsking(): Boolean = phone.canDeleteWithoutAsking()

    suspend fun setPolicy(policy: ExternalDeletionPolicy) = preferences.update {
        it.copy(externalDeletion = policy)
    }

    /** Moves these items to the trash on the phone and in Drive (R12). */
    suspend fun delete(itemIds: Collection<String>): DeletionRequest {
        // The state from before the request: see trashedInSettle.
        val items = items(itemIds)
        val files = phoneFiles(phoneFilesToTrash(items), items)
        val steps =
            phone.trashRequests(files, trashed = true).map { (batch, sender) ->
                val batchIds = batch.map { it.itemId }.toSet()
                PhoneStep(sender) { approved ->
                    if (approved) {
                        apply(
                            trashedInSettle(items.filter { it.itemId.value in batchIds }, clock())
                        )
                    }
                }
            }
        return DeletionRequest(steps, files.size)
    }

    /**
     * Brings these items back: out of the phone's trash, or downloaded from Drive when the phone no
     * longer has them. Also the Undo of [delete].
     */
    suspend fun restore(itemIds: Collection<String>): DeletionRequest {
        val items = items(itemIds)
        val plan = planRestore(items)
        if (plan.downloads.isNotEmpty()) {
            betweenScans {
                dao.applyTrashChanges(TrashChanges(ops = plan.downloadOps))
                dao.requestRestore(plan.downloads.map { it.value }, clock())
            }
            reschedule()
            startRestore()
        }
        val files = phoneFiles(plan.phoneUntrash, items)
        val steps =
            phone.trashRequests(files, trashed = false).map { (batch, sender) ->
                val batchIds = batch.map { it.itemId }.toSet()
                PhoneStep(sender) { approved ->
                    if (approved)
                        apply(planRestore(items.filter { it.itemId.value in batchIds }).untrashed)
                }
            }
        return DeletionRequest(steps, plan.phoneUntrash.size + plan.downloads.size)
    }

    /** Deletes these items for good, on the phone and in Drive. Only items in the trash. */
    suspend fun deleteForever(itemIds: Collection<String>): DeletionRequest {
        val items = items(itemIds)
        val plan = planDeleteForever(items)
        val files = phoneFiles(plan.phoneDelete, items)
        val onPhone = files.map { it.itemId }.toSet()
        // Items whose phone file is already gone need no confirmation from Android.
        val withoutPhone = items.filter { it.inTrash && it.itemId.value !in onPhone }
        if (withoutPhone.isNotEmpty()) apply(planDeleteForever(withoutPhone).deleted)
        val steps =
            phone.deleteRequests(files).map { (batch, sender) ->
                val batchIds = batch.map { it.itemId }.toSet()
                PhoneStep(sender) { approved ->
                    if (approved) {
                        apply(
                            planDeleteForever(items.filter { it.itemId.value in batchIds }).deleted
                        )
                    }
                }
            }
        return DeletionRequest(steps, plan.deleted.purge.size)
    }

    suspend fun emptyTrash(): DeletionRequest = deleteForever(dao.trashIds())

    /** The user's answer for items that left the phone without Settle; null means all of them. */
    suspend fun decide(itemIds: Collection<String>?, decision: VanishedDecision) {
        val ids =
            itemIds ?: (dao.undecided(VanishedReason.BREAKER) + dao.undecided(VanishedReason.ASK))
        apply(decideVanished(items(ids), decision, clock()))
    }

    /**
     * An upload ended. If its item was deleted meanwhile, the Drive file it may just have created
     * goes to the trash too: the trash queued at deletion time found no file yet.
     */
    suspend fun uploadEnded(itemId: String) {
        if (!dao.isInTrash(itemId)) return
        val file = dao.createdDriveFile(itemId) ?: return
        val id = MediaItemId.parseOrNull(itemId) ?: return
        apply(TrashChanges(ops = listOf(DriveOp(file, id, DriveOpKind.TRASH))))
    }

    private suspend fun items(itemIds: Collection<String>): List<TrashItem> =
        store.items(itemIds.mapNotNull { MediaItemId.parseOrNull(it) })

    private suspend fun phoneFiles(
        mediaStoreIds: List<Long>,
        items: List<TrashItem>,
    ): List<PhoneFile> {
        val wanted = mediaStoreIds.toSet()
        val ids = items.filter { it.mediaStoreId in wanted }.map { it.itemId.value }
        return dao.phoneFiles(ids).map { PhoneFile(it.id, it.kind, it.volume, it.mediaStoreId) }
    }

    private suspend fun apply(changes: TrashChanges) {
        if (changes.isEmpty) return
        betweenScans { dao.applyTrashChanges(changes) }
        reschedule()
    }
}
