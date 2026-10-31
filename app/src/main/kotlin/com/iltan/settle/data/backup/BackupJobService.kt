package com.iltan.settle.data.backup

import android.app.job.JobParameters
import android.app.job.JobService
import android.util.Log
import com.iltan.settle.appGraph
import com.iltan.settle.domain.backup.BackupPause
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Every background job of backup (ADR-014): the automatic run on Wi-Fi and the charger, the run the
 * user started, a library scan when new media appear, and daily maintenance. Each job is a
 * coroutine; stopping the job cancels it, and uploads continue from the server's offset next time.
 */
class BackupJobService : JobService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = ConcurrentHashMap<Int, Job>()

    override fun onStartJob(params: JobParameters): Boolean {
        val graph = appGraph
        if (params.jobId == BackupScheduler.REQUESTED_JOB) {
            // Android requires the notification within seconds of the start.
            setNotification(
                params,
                BackupNotifications.PROGRESS_ID,
                graph.backupNotifications.progress(0, 0, 0),
                JOB_END_NOTIFICATION_POLICY_REMOVE,
            )
        }
        val work: suspend () -> Unit =
            when (params.jobId) {
                BackupScheduler.AUTOMATIC_JOB -> ({ graph.backupRunner.run(RunKind.AUTOMATIC) })
                BackupScheduler.REQUESTED_JOB -> ({ runRequested(params) })
                BackupScheduler.MEDIA_JOB -> ({ graph.librarySync.scan() })
                BackupScheduler.MAINTENANCE_JOB -> ({ graph.backupMaintenance.run() })
                else -> return false
            }
        running[params.jobId] = scope.launch {
            try {
                work()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Job ${params.jobId} failed", e)
            }
            running.remove(params.jobId)
            jobFinished(params, false)
            afterRun(uploaded = params.jobId in UPLOAD_JOBS)
        }
        return true
    }

    /** Runs what the user asked for, with progress in the notification, until none is left. */
    private suspend fun runRequested(params: JobParameters) {
        val graph = appGraph
        val total = graph.backupPlanner.plan().requested?.estimatedBytes ?: 0
        val reporter = scope.launch {
            while (true) {
                val plan = graph.backupPlanner.plan()
                val left = plan.queue.filter { it.requestedAtMillis != null }
                val inFlight = graph.backupRunner.progress.value.values.sumOf { it.sentBytes }
                val sent = (total - left.sumOf { it.sizeBytes } + inFlight).coerceIn(0, total)
                setNotification(
                    params,
                    BackupNotifications.PROGRESS_ID,
                    graph.backupNotifications.progress(left.size, sent, total),
                    JOB_END_NOTIFICATION_POLICY_REMOVE,
                )
                updateTransferredNetworkBytes(params, 0, sent)
                delay(PROGRESS_EVERY_MILLIS)
            }
        }
        try {
            // Requests made while the run was going are taken in the same job.
            while (graph.backupRunner.run(RunKind.REQUESTED) == RunEnd.DONE) {
                if (graph.backupPlanner.plan().requested == null) break
            }
        } finally {
            reporter.cancel()
        }
    }

    /** Plans the next run; after uploads, tells the user if backup now waits for them. */
    private suspend fun afterRun(uploaded: Boolean) {
        val graph = appGraph
        graph.backupScheduler.reschedule()
        if (!uploaded) return
        val blocked = graph.backupPreferences.current().blocked
        if (blocked == BackupPause.AUTH_NEEDED || blocked == BackupPause.QUOTA_FULL) {
            graph.backupNotifications.showPause(blocked)
        }
    }

    override fun onStopJob(params: JobParameters): Boolean {
        Log.i(TAG, "Job ${params.jobId} stopped: reason ${params.stopReason}")
        running.remove(params.jobId)?.cancel()
        return when (params.jobId) {
            // Re-armed by the next reschedule.
            BackupScheduler.MEDIA_JOB -> false
            // Stopped by the user in Task Manager: they start it again themselves.
            BackupScheduler.REQUESTED_JOB -> params.stopReason != JobParameters.STOP_REASON_USER
            else -> true
        }
    }

    override fun onNetworkChanged(params: JobParameters) {
        appGraph.backupRunner.onNetworkChanged(params.network)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "BackupJobs"
        const val PROGRESS_EVERY_MILLIS = 2_000L
        val UPLOAD_JOBS = setOf(BackupScheduler.AUTOMATIC_JOB, BackupScheduler.REQUESTED_JOB)
    }
}
