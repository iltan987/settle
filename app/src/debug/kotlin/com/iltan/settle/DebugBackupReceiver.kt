package com.iltan.settle

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.launch

/**
 * Debug builds only: queues a backup of the item with the given MediaStore ID, for on-device tests
 * of items that are far down the timeline.
 *
 * `adb shell am broadcast -a com.iltan.settle.debug.BACK_UP -p com.iltan.settle.debug --el
 * media_store_id 24713`
 */
class DebugBackupReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val mediaStoreId = intent.getLongExtra("media_store_id", -1)
        val graph = context.appGraph
        val pending = goAsync()
        graph.appScope.launch {
            try {
                val itemId = graph.database.backupDao().itemIdForMediaStoreId(mediaStoreId)
                Log.i("DebugBackup", "MediaStore $mediaStoreId is item $itemId")
                itemId?.let { graph.cloudBackup.backUpNow(listOf(it)) }
            } finally {
                pending.finish()
            }
        }
    }
}
