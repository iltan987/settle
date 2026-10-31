package com.iltan.settle.data.backup

import android.app.job.JobScheduler
import android.content.Context
import android.net.NetworkCapabilities
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.iltan.settle.data.db.DriveAccountEntity
import com.iltan.settle.data.db.ScanCursorEntity
import com.iltan.settle.data.db.SettleDatabase
import com.iltan.settle.data.drive.UploadMeter
import com.iltan.settle.domain.MediaItemId
import com.iltan.settle.domain.backup.DEFAULT_GRACE_MILLIS
import com.iltan.settle.domain.scan.IndexedRow
import com.iltan.settle.domain.scan.MediaKind
import com.iltan.settle.domain.scan.ScanPlan
import com.iltan.settle.domain.scan.ScanRow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BackupSchedulerTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val jobs = context.getSystemService(JobScheduler::class.java)
    private lateinit var db: SettleDatabase
    private val now = 1_800_000_000_000L
    private var nextId = 0L
    /** What JobScheduler would say is executing right now. */
    private val executing = mutableSetOf<Int>()

    @Before
    fun setUp() {
        db =
            Room.inMemoryDatabaseBuilder<SettleDatabase>(context)
                .setDriver(AndroidSQLiteDriver())
                .build()
    }

    @After fun tearDown() = db.close()

    private fun scheduler(): BackupScheduler {
        val dao = db.backupDao()
        val preferences = BackupPreferences(dao)
        val planner = BackupPlanner(dao, preferences, canReadOriginals = { true }, clock = { now })
        val runner =
            BackupRunner(
                dao = dao,
                planner = planner,
                preferences = preferences,
                newEngine = { error("No uploads in these tests") },
                meter = UploadMeter(),
                measureDelay = { null },
                clock = { now },
            )
        return BackupScheduler(context, dao, planner, runner, { now }, { it in executing })
    }

    private suspend fun item(addedAtMillis: Long) {
        val id = MediaItemId.generate(nowMillis = ++nextId)
        val row =
            ScanRow(
                volume = "external_primary",
                mediaStoreId = nextId,
                relativePath = "DCIM/Camera/",
                displayName = "IMG_$nextId.jpg",
                mimeType = "image/jpeg",
                kind = MediaKind.IMAGE,
                sizeBytes = 1_000,
                dateTakenMillis = addedAtMillis,
                dateModifiedSeconds = 1,
                width = 1,
                height = 1,
                orientation = 0,
                durationMillis = 0,
                specialFormat = 0,
                isFavorite = false,
                isTrashed = false,
                generationModified = nextId,
                dateAddedSeconds = addedAtMillis / 1000,
            )
        db.libraryDao()
            .applyScan(
                ScanPlan(listOf(IndexedRow(id, row)), emptyList(), emptyList(), emptyList(), false),
                ScanCursorEntity("external_primary", "v1", nextId, 1),
                nowMs = 1,
            )
    }

    private suspend fun connect() =
        db.backupDao().setAccount(DriveAccountEntity(email = "owner@example.com"))

    private val automatic
        get() = jobs.getPendingJob(BackupScheduler.AUTOMATIC_JOB)

    @Test
    fun aReadyItemSchedulesTheAutomaticJobOnUnmeteredWifiAndTheCharger() = runTest {
        connect()
        item(addedAtMillis = 0)

        scheduler().reschedule()

        val job = checkNotNull(automatic)
        assertEquals(0, job.minLatencyMillis)
        assertTrue(job.isRequireCharging)
        assertTrue(job.isRequireBatteryNotLow)
        assertTrue(job.isPersisted)
        assertTrue(
            job.requiredNetwork!!.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        )
        assertNotNull(jobs.getPendingJob(BackupScheduler.MEDIA_JOB))
        assertNotNull(jobs.getPendingJob(BackupScheduler.MAINTENANCE_JOB))
    }

    @Test
    fun anItemInItsGracePeriodSchedulesTheRunForWhenItEnds() = runTest {
        connect()
        item(addedAtMillis = now)

        scheduler().reschedule()

        assertEquals(DEFAULT_GRACE_MILLIS, automatic!!.minLatencyMillis)
    }

    @Test
    fun anAutomaticJobTheSystemIsAlreadyRunningIsNotReplaced() = runTest {
        connect()
        item(addedAtMillis = now)
        val scheduler = scheduler()
        scheduler.reschedule()
        item(addedAtMillis = 0)
        executing += BackupScheduler.AUTOMATIC_JOB

        // Settle's start-up planning, while the system already runs the automatic job.
        scheduler.reschedule()
        assertEquals(DEFAULT_GRACE_MILLIS, automatic!!.minLatencyMillis)

        // The automatic job itself, as it finishes, plans its next run.
        scheduler.reschedule(finishingJob = BackupScheduler.AUTOMATIC_JOB)
        assertEquals(0, automatic!!.minLatencyMillis)
    }

    @Test
    fun nothingToBackUpCancelsTheAutomaticJob() = runTest {
        connect()
        item(addedAtMillis = now)
        val scheduler = scheduler()
        scheduler.reschedule()
        db.backupDao().deleteAccount()

        scheduler.reschedule()

        assertNull(automatic)
    }
}
