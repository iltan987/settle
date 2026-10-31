package com.iltan.settle

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.util.Log
import com.iltan.settle.data.Library
import com.iltan.settle.data.backup.BackupMaintenance
import com.iltan.settle.data.backup.BackupNotifications
import com.iltan.settle.data.backup.BackupPlanner
import com.iltan.settle.data.backup.BackupPreferences
import com.iltan.settle.data.backup.BackupRunner
import com.iltan.settle.data.backup.BackupScheduler
import com.iltan.settle.data.backup.CloudBackup
import com.iltan.settle.data.db.SettleDatabase
import com.iltan.settle.data.drive.DriveAuth
import com.iltan.settle.data.drive.DriveRest
import com.iltan.settle.data.drive.DriveSandbox
import com.iltan.settle.data.drive.FixedToken
import com.iltan.settle.data.drive.HandshakeProbe
import com.iltan.settle.data.drive.MediaStoreOriginals
import com.iltan.settle.data.drive.RoomUploadStore
import com.iltan.settle.data.drive.UploadMeter
import com.iltan.settle.data.media.LibrarySync
import com.iltan.settle.data.media.MediaAccess
import com.iltan.settle.data.media.MediaChangeObserver
import com.iltan.settle.data.media.MediaStoreSource
import com.iltan.settle.data.trash.Deletion
import com.iltan.settle.data.trash.MediaStoreTrash
import com.iltan.settle.data.trash.TrashStore
import com.iltan.settle.data.trash.TrashWork
import com.iltan.settle.domain.backup.BackupEngine
import com.iltan.settle.domain.deletion.DriveChanges
import com.iltan.settle.domain.deletion.EXTERNAL_DELETION_WAIT_MILLIS
import com.iltan.settle.domain.deletion.RestoreDownload
import com.iltan.settle.domain.deletion.afterScan
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.Dispatcher
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response

/** The app's long-lived objects, wired by hand (ADR-007). One instance per process. */
class AppGraph(context: Context) {
    val appContext: Context = context.applicationContext

    /** Work that outlives any screen, such as scanning. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val database: SettleDatabase = SettleDatabase.create(appContext)

    val library = Library(database.libraryDao(), appContext.contentResolver)

    val librarySync =
        LibrarySync(
            source = MediaStoreSource(appContext),
            dao = database.libraryDao(),
            hasFullAccess = { MediaAccess.of(appContext) == MediaAccess.FULL },
            trashRules = { plan, now ->
                val ids = plan.missing + plan.updates.map { it.itemId }
                afterScan(
                    plan,
                    trashStore.items(ids).associateBy { it.itemId },
                    backupPreferences.current().externalDeletion,
                    now,
                    externalDeletionWaitMillis,
                )
            },
            // New or deleted media change what waits for backup and what Drive must hear.
            onChanged = { backupScheduler.reschedule() },
        )

    private val trashStore = TrashStore(database.trashDao())

    /** An hour, or a minute in debug builds so deletions made elsewhere can be tried (ADR-015). */
    private val externalDeletionWaitMillis
        get() = if (debuggable) 60_000L else EXTERNAL_DELETION_WAIT_MILLIS

    val http by lazy {
        // Timeouts apply per read or write, not per chunk, so slow networks still finish.
        OkHttpClient.Builder()
            // Up to six uploads and the pacer's delay probe go to the same host at once; OkHttp
            // allows five per host by default.
            .dispatcher(Dispatcher().apply { maxRequestsPerHost = 16 })
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .apply { if (debuggable) addNetworkInterceptor(::logTiming) }
            .build()
    }

    val debuggable = appContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    /** Debug builds: one line per Drive request, for measuring. Query strings stay out of logs. */
    private fun logTiming(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val started = System.nanoTime()
        val response = chain.proceed(request)
        val ms = (System.nanoTime() - started) / 1_000_000
        Log.d(
            "DriveHttp",
            "${request.method} ${request.url.encodedPath} -> ${response.code} " +
                "(${request.body?.contentLength() ?: 0} B up) in $ms ms",
        )
        return response
    }

    /**
     * Settle's Drive folder: `Settle`, or `Settle (debug)` in debug builds (ADR-008). A debug build
     * on an emulator signed in to the same account uses `Settle (emulator)`, so tests there never
     * touch what the phone's debug build uploaded.
     */
    val driveRootFolder: String =
        appContext.getString(
            if (appContext.resources.getBoolean(R.bool.drive_sandbox) && Build.HARDWARE == "ranchu")
                R.string.drive_root_folder_emulator
            else R.string.drive_root_folder
        )

    val driveAuth by lazy {
        DriveAuth(appContext, database.backupDao()) { token ->
            DriveRest(http, FixedToken(token)).about().email
        }
    }

    val drive by lazy { DriveRest(http, driveAuth) }

    /**
     * Uploads get connections of their own, one per upload over HTTP/1.1 as rclone does, so each
     * has its own TCP window and the pacer's delay probe never waits behind upload data (ADR-014).
     */
    private val uploadHttp by lazy {
        http.newBuilder().protocols(listOf(Protocol.HTTP_1_1)).build()
    }

    private val uploadMeter = UploadMeter()

    private val uploadDrive by lazy { DriveRest(uploadHttp, driveAuth, meter = uploadMeter) }

    private val originals by lazy { MediaStoreOriginals(appContext, database.backupDao()) }

    /** Whether originals can be read with their location (R1). */
    fun canReadOriginals(): Boolean = originals.canReadOriginals()

    val backupPreferences by lazy { BackupPreferences(database.backupDao()) }

    val backupPlanner by lazy {
        BackupPlanner(database.backupDao(), backupPreferences, originals::canReadOriginals)
    }

    val backupRunner: BackupRunner by lazy {
        val store = RoomUploadStore(database.backupDao())
        BackupRunner(
            dao = database.backupDao(),
            planner = backupPlanner,
            preferences = backupPreferences,
            newEngine = { folderLock ->
                BackupEngine(
                    drive = uploadDrive,
                    originals = originals,
                    store = store,
                    clock = { System.currentTimeMillis() },
                    rootFolder = driveRootFolder,
                    zone = ZoneId.systemDefault(),
                    log = { Log.w("BackupEngine", it) },
                    folderLock = folderLock,
                )
            },
            meter = uploadMeter,
            measureDelay = HandshakeProbe()::millis,
            log = { Log.i("BackupRunner", it) },
            onUploadEnded = { deletion.uploadEnded(it) },
        )
    }

    val backupScheduler: BackupScheduler by lazy {
        BackupScheduler(
            appContext,
            database.backupDao(),
            backupPlanner,
            backupRunner,
            nextDriveChangeAt = { database.trashDao().nextOpDueAt() },
            restoreOnMobileData = { backupPreferences.current().settings.useMobileData },
        )
    }

    private val mediaStoreTrash by lazy { MediaStoreTrash(appContext) }

    val deletion: Deletion by lazy {
        Deletion(
            dao = database.trashDao(),
            store = trashStore,
            phone = mediaStoreTrash,
            preferences = backupPreferences,
            betweenScans = { librarySync.betweenScans(it) },
            reschedule = { backupScheduler.reschedule() },
            startRestore = { backupScheduler.startRestore() },
        )
    }

    val trashWork by lazy {
        TrashWork(
            dao = database.trashDao(),
            store = trashStore,
            driveChanges =
                DriveChanges(drive, trashStore, { System.currentTimeMillis() }) {
                    Log.w("DriveChanges", it)
                },
            restoreDownload =
                RestoreDownload(
                    drive = drive,
                    target = mediaStoreTrash,
                    uploads = RoomUploadStore(database.backupDao()),
                    downloads = trashStore,
                    log = { Log.w("RestoreDownload", it) },
                ),
            preferences = backupPreferences,
            betweenScans = { librarySync.betweenScans(it) },
            log = { Log.i("TrashWork", it) },
        )
    }

    val backupNotifications by lazy { BackupNotifications(appContext).also { it.createChannels() } }

    val backupMaintenance by lazy {
        BackupMaintenance(drive, backupPreferences, backupNotifications, trashWork)
    }

    val cloudBackup by lazy {
        CloudBackup(
            backupRunner,
            backupScheduler,
            database.backupDao(),
            backupPreferences,
            originals,
        )
    }

    /** Only in debug builds. */
    val driveSandbox: DriveSandbox? by lazy {
        if (appContext.resources.getBoolean(R.bool.drive_sandbox)) {
            DriveSandbox(drive, database.backupDao(), driveRootFolder)
        } else null
    }

    fun start() {
        librarySync.start(appScope)
        MediaChangeObserver { librarySync.requestScan() }.register(appContext.contentResolver)
        // A force stop clears every job; building the Drive client stays off the main thread.
        appScope.launch { backupScheduler.reschedule() }
    }
}
