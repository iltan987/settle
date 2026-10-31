package com.iltan.settle.data.backup

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.provider.MediaStore
import android.util.Log
import com.iltan.settle.data.db.BackupDao
import com.iltan.settle.data.db.BackupRequestEntity
import com.iltan.settle.domain.backup.PlannedRun
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Turns the backup plan into JobScheduler jobs (ADR-014). Called whenever the plan may have
 * changed: at app start (a force stop clears jobs), after scans, settings changes and runs, and
 * after boot.
 */
class BackupScheduler(
    context: Context,
    private val dao: BackupDao,
    private val planner: BackupPlanner,
    private val runner: BackupRunner,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Whether the system is running the job with this ID right now; replaced in tests. */
    isExecuting: ((jobId: Int) -> Boolean)? = null,
    /** When the next queued Drive change is due (ADR-015), or null when none is queued. */
    private val nextDriveChangeAt: suspend () -> Long? = { null },
    /** Restore downloads may use mobile data (the backup setting). */
    private val restoreOnMobileData: suspend () -> Boolean = { false },
) {
    private val jobs = context.getSystemService(JobScheduler::class.java)
    private val service = ComponentName(context, BackupJobService::class.java)
    private val lock = Mutex()
    private val isExecuting =
        isExecuting
            ?: { id ->
                jobs.getPendingJobReason(id) == JobScheduler.PENDING_JOB_REASON_EXECUTING
            }

    /**
     * Brings the automatic, media-watching and maintenance jobs in line with the plan. A job that
     * is ending passes its ID: it still counts as pending, but a trigger job must be armed again.
     */
    suspend fun reschedule(finishingJob: Int? = null) = lock.withLock {
        if (finishingJob == MEDIA_JOB || jobs.getPendingJob(MEDIA_JOB) == null) {
            jobs.schedule(mediaJob())
        }
        if (jobs.getPendingJob(MAINTENANCE_JOB)?.intervalMillis != MAINTENANCE_EVERY_MILLIS) {
            jobs.schedule(maintenanceJob())
        }
        scheduleDriveChanges(finishingJob)
        // A run under way plans its own next step when it ends; scheduling the automatic job's ID
        // again would stop it. When the automatic job starts Settle's process, the app's own
        // start-up planning can get here before the runner has begun, so JobScheduler is asked
        // too, except by the automatic job itself as it finishes.
        val automaticRunning = finishingJob != AUTOMATIC_JOB && isExecuting(AUTOMATIC_JOB)
        if (runner.running.value || automaticRunning) return@withLock
        val plan = planner.plan()
        val automatic = plan.automatic
        if (automatic == null) {
            jobs.cancel(AUTOMATIC_JOB)
        } else {
            jobs.schedule(automaticJob(automatic))
        }
        Log.i(
            TAG,
            "Plan: ${plan.queue.size} ready, ${plan.waiting} waiting, pause ${plan.pause}, " +
                "automatic ${automatic?.let { "in ${(it.notBeforeMillis - clock()) / 1000} s" }}",
        )
    }

    /** What became of "Back up now". */
    enum class NowResult {
        /** A user-initiated job runs the requests, or one already running takes them. */
        STARTED,
        /** No request needs a run: backup is paused (R6), or the items need no backup. */
        NOT_NEEDED,
        /** Android refused a user-initiated job; the automatic job takes the requests instead. */
        DEFERRED,
    }

    /**
     * Backs these items up now, in a user-initiated job that Android lets finish in the background
     * (R3). Only works while Settle is visible. The requests are kept either way.
     */
    suspend fun backUpNow(itemIds: Collection<String>): NowResult = lock.withLock {
        val now = clock()
        dao.addRequests(itemIds.map { BackupRequestEntity(it, now) })
        val run = planner.plan().requested ?: return@withLock NowResult.NOT_NEEDED
        // A running job picks up new requests before it ends.
        if (jobs.getPendingJob(REQUESTED_JOB) != null) return@withLock NowResult.STARTED
        val scheduled =
            jobs.canRunUserInitiatedJobs() &&
                jobs.schedule(requestedJob(run)) == JobScheduler.RESULT_SUCCESS
        if (scheduled) NowResult.STARTED else NowResult.DEFERRED
    }

    /**
     * Arms the job that runs queued Drive changes for when the next one is due. Rescheduling a job
     * that is running would stop it, so a running one arms the next itself as it finishes.
     */
    private suspend fun scheduleDriveChanges(finishingJob: Int?) {
        if (finishingJob != DRIVE_CHANGES_JOB && isExecuting(DRIVE_CHANGES_JOB)) return
        val dueAt = nextDriveChangeAt()
        if (dueAt == null) {
            // A finishing job ends by itself; cancelling it would only report it as stopped.
            if (finishingJob != DRIVE_CHANGES_JOB) jobs.cancel(DRIVE_CHANGES_JOB)
        } else {
            jobs.schedule(driveChangesJob(dueAt))
        }
    }

    /**
     * Downloads the items the user asked to restore, in a user-initiated job (ADR-015). Only works
     * while Settle is visible; returns false if Android refused, and the requests are kept.
     */
    suspend fun startRestore(): Boolean = lock.withLock {
        if (jobs.getPendingJob(RESTORE_JOB) != null) return@withLock true
        jobs.canRunUserInitiatedJobs() &&
            jobs.schedule(restoreJob(unmetered = !restoreOnMobileData())) ==
                JobScheduler.RESULT_SUCCESS
    }

    /** Stops both upload jobs, for "Pause"; records stay resumable. */
    fun stopUploads() {
        jobs.cancel(REQUESTED_JOB)
        jobs.cancel(AUTOMATIC_JOB)
    }

    private fun automaticJob(run: PlannedRun) =
        JobInfo.Builder(AUTOMATIC_JOB, service)
            .setRequiredNetwork(network(run.conditions.unmeteredNetwork))
            .setRequiresCharging(run.conditions.charging)
            .setRequiresBatteryNotLow(true)
            .setMinimumLatency((run.notBeforeMillis - clock()).coerceAtLeast(0L))
            .setEstimatedNetworkBytes(UNKNOWN_BYTES, bytesOrUnknown(run))
            .setPersisted(true)
            .build()

    private fun requestedJob(run: PlannedRun) =
        JobInfo.Builder(REQUESTED_JOB, service)
            .setUserInitiated(true)
            .setRequiredNetwork(network(run.conditions.unmeteredNetwork))
            .setEstimatedNetworkBytes(UNKNOWN_BYTES, bytesOrUnknown(run))
            .setPersisted(true)
            .build()

    /** New photos and videos start a scan even while Settle isn't running (R5). */
    private fun mediaJob() =
        JobInfo.Builder(MEDIA_JOB, service)
            .addTriggerContentUri(
                JobInfo.TriggerContentUri(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS,
                )
            )
            .addTriggerContentUri(
                JobInfo.TriggerContentUri(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS,
                )
            )
            // A burst of photos is one scan.
            .setTriggerContentUpdateDelay(TimeUnit.SECONDS.toMillis(10))
            .setTriggerContentMaxDelay(TimeUnit.MINUTES.toMillis(2))
            .build()

    /**
     * Every six hours: is Drive's space back, and is everything scheduled. On the phone, an
     * automatic job whose process was killed mid-run waited until conditions next changed (e.g. the
     * charger); planning again from here starts it within hours instead of the next morning.
     */
    private fun maintenanceJob() =
        JobInfo.Builder(MAINTENANCE_JOB, service)
            .setPeriodic(MAINTENANCE_EVERY_MILLIS, TimeUnit.HOURS.toMillis(1))
            .setRequiredNetwork(network(unmetered = false))
            .setRequiresBatteryNotLow(true)
            .setPersisted(true)
            .build()

    /** Small metadata requests; any network will do, and they must not wait for the charger. */
    private fun driveChangesJob(dueAtMillis: Long) =
        JobInfo.Builder(DRIVE_CHANGES_JOB, service)
            .setRequiredNetwork(network(unmetered = false))
            .setMinimumLatency((dueAtMillis - clock()).coerceAtLeast(0L))
            .setEstimatedNetworkBytes(SMALL_REQUEST_BYTES, SMALL_REQUEST_BYTES)
            .setPersisted(true)
            .build()

    private fun restoreJob(unmetered: Boolean) =
        JobInfo.Builder(RESTORE_JOB, service)
            .setUserInitiated(true)
            .setRequiredNetwork(network(unmetered))
            .setEstimatedNetworkBytes(UNKNOWN_BYTES, UNKNOWN_BYTES)
            .setPersisted(true)
            .build()

    private fun network(unmetered: Boolean) =
        NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            .apply { if (unmetered) addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) }
            .build()

    private fun bytesOrUnknown(run: PlannedRun): Long =
        run.estimatedBytes.takeIf { it > 0 } ?: UNKNOWN_BYTES

    companion object {
        private const val TAG = "BackupScheduler"
        private const val UNKNOWN_BYTES = JobInfo.NETWORK_BYTES_UNKNOWN.toLong()
        const val AUTOMATIC_JOB = 1
        const val REQUESTED_JOB = 2
        const val MEDIA_JOB = 3
        const val MAINTENANCE_JOB = 4
        const val DRIVE_CHANGES_JOB = 5
        const val RESTORE_JOB = 6
        private const val SMALL_REQUEST_BYTES = 4_096L
        private val MAINTENANCE_EVERY_MILLIS = TimeUnit.HOURS.toMillis(6)
    }
}
