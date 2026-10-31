package com.iltan.settle

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import com.iltan.settle.data.drive.DriveRest
import com.iltan.settle.data.drive.MediaStoreOriginals
import com.iltan.settle.data.drive.RoomUploadStore
import com.iltan.settle.domain.MediaItemId
import com.iltan.settle.domain.backup.BackupEngine
import java.time.ZoneId
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Protocol

/**
 * Debug builds only: backs up the given MediaStore items, all of which must be test files in
 * `Pictures/SettleTest/`, with several uploads at once over HTTP/2 or HTTP/1.1, and logs the total
 * throughput and Drive's round-trip delay, idle and while uploading. For measuring before choosing
 * upload defaults (ADR-014).
 *
 * `adb shell am broadcast -a com.iltan.settle.debug.MEASURE -p com.iltan.settle.debug --es ids
 * 101,102,103 --ei parallel 4 --es protocol h1`
 */
class DebugMeasureReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val mediaStoreIds =
            intent.getStringExtra("ids").orEmpty().split(',').mapNotNull {
                it.trim().toLongOrNull()
            }
        val parallel = intent.getIntExtra("parallel", 1).coerceIn(1, 16)
        val http1 = intent.getStringExtra("protocol") == "h1"
        val graph = context.appGraph
        graph.appScope.launch {
            try {
                measure(graph, mediaStoreIds, parallel, http1)
            } catch (e: Exception) {
                Log.e(TAG, "Measurement failed", e)
            }
        }
    }

    private suspend fun measure(
        graph: AppGraph,
        mediaStoreIds: List<Long>,
        parallel: Int,
        http1: Boolean,
    ) {
        val dao = graph.database.backupDao()
        // Only generated test files: a wrong ID must never upload a real photo.
        val itemIds =
            mediaStoreIds
                .mapNotNull { dao.itemIdForMediaStoreId(it) }
                .filter { dao.item(it)?.relativePath == TEST_FOLDER }
        if (itemIds.size != mediaStoreIds.size) {
            Log.e(TAG, "Refused: only items in $TEST_FOLDER can be measured")
            return
        }
        val client =
            if (http1) graph.http.newBuilder().protocols(listOf(Protocol.HTTP_1_1)).build()
            else graph.http
        val drive = DriveRest(client, graph.driveAuth)
        val originals = MediaStoreOriginals(graph.appContext, dao)
        val store = RoomUploadStore(dao)

        val idle = List(5) { pingMillis(graph.drive).also { delay(500) } }
        Log.i(TAG, "Idle delay ms: $idle")

        val queue = Channel<String>(Channel.UNLIMITED)
        itemIds.forEach(queue::trySend)
        queue.close()
        val bytes = AtomicLong()
        val started = SystemClock.elapsedRealtime()
        coroutineScope {
            val probe = launch {
                val samples = mutableListOf<Long>()
                while (isActive) {
                    delay(2_000)
                    samples += pingMillis(graph.drive)
                    if (samples.size % 5 == 0) Log.i(TAG, "Loaded delay ms: ${samples.takeLast(5)}")
                }
            }
            List(parallel) {
                    launch {
                        val engine =
                            BackupEngine(
                                drive = drive,
                                originals = originals,
                                store = store,
                                clock = { System.currentTimeMillis() },
                                rootFolder = graph.driveRootFolder,
                                zone = ZoneId.systemDefault(),
                            )
                        for (itemId in queue) {
                            var size = 0L
                            val result =
                                engine.backUp(MediaItemId.parseOrNull(itemId)!!) { _, total ->
                                    size = total
                                }
                            bytes.addAndGet(size)
                            Log.i(TAG, "$itemId: $result")
                        }
                    }
                }
                .forEach { it.join() }
            probe.cancel()
        }
        val seconds = (SystemClock.elapsedRealtime() - started) / 1000.0
        Log.i(
            TAG,
            "%d items, %.1f MiB, %s x%d in %.1f s: %.2f MiB/s (%.1f Mbps)"
                .format(
                    Locale.ROOT,
                    itemIds.size,
                    bytes.get() / MIB,
                    if (http1) "HTTP/1.1" else "HTTP/2",
                    parallel,
                    seconds,
                    bytes.get() / MIB / seconds,
                    bytes.get() * 8 / 1e6 / seconds,
                ),
        )
    }

    private suspend fun pingMillis(drive: DriveRest): Long {
        val started = SystemClock.elapsedRealtime()
        drive.ping()
        return SystemClock.elapsedRealtime() - started
    }

    private companion object {
        const val TAG = "DebugMeasure"
        const val TEST_FOLDER = "Pictures/SettleTest/"
        const val MIB = 1024.0 * 1024.0
    }
}
