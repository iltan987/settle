package com.iltan.settle.data.backup

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.iltan.settle.appGraph
import kotlinx.coroutines.launch

/** The Pause button on the progress notification. */
class BackupActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != PAUSE) return
        val graph = context.appGraph
        val pending = goAsync()
        graph.appScope.launch {
            try {
                graph.backupPreferences.update {
                    it.copy(settings = it.settings.copy(paused = true))
                }
                graph.backupScheduler.stopUploads()
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val PAUSE = "com.iltan.settle.action.PAUSE_BACKUP"
    }
}

/** Jobs that watch for new media don't survive a reboot; schedule them again. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val graph = context.appGraph
        val pending = goAsync()
        graph.appScope.launch {
            try {
                graph.backupScheduler.reschedule()
            } finally {
                pending.finish()
            }
        }
    }
}
