package com.iltan.settle

import android.content.Context
import android.util.Log
import com.iltan.settle.data.Library
import com.iltan.settle.data.db.SettleDatabase
import com.iltan.settle.data.drive.CloudBackup
import com.iltan.settle.data.drive.DriveAuth
import com.iltan.settle.data.drive.DriveRest
import com.iltan.settle.data.drive.DriveSandbox
import com.iltan.settle.data.drive.FixedToken
import com.iltan.settle.data.drive.MediaStoreOriginals
import com.iltan.settle.data.drive.RoomUploadStore
import com.iltan.settle.data.media.LibrarySync
import com.iltan.settle.data.media.MediaAccess
import com.iltan.settle.data.media.MediaChangeObserver
import com.iltan.settle.data.media.MediaStoreSource
import com.iltan.settle.domain.backup.BackupEngine
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/** The app's long-lived objects, wired by hand (ADR-007). One instance per process. */
class AppGraph(context: Context) {
    private val appContext = context.applicationContext

    /** Work that outlives any screen, such as scanning. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val database: SettleDatabase = SettleDatabase.create(appContext)

    val library = Library(database.libraryDao(), appContext.contentResolver)

    val librarySync =
        LibrarySync(
            source = MediaStoreSource(appContext),
            dao = database.libraryDao(),
            hasFullAccess = { MediaAccess.of(appContext) == MediaAccess.FULL },
        )

    private val http by lazy {
        // Timeouts apply per read or write, not per chunk, so slow networks still finish.
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    /** Settle's Drive folder: `Settle`, or `Settle (debug)` in debug builds (ADR-008). */
    val driveRootFolder: String = appContext.getString(R.string.drive_root_folder)

    val driveAuth by lazy {
        DriveAuth(appContext, database.backupDao()) { token ->
            DriveRest(http, FixedToken(token)).about().email
        }
    }

    val drive by lazy { DriveRest(http, driveAuth) }

    val cloudBackup by lazy {
        val originals = MediaStoreOriginals(appContext, database.backupDao())
        val engine =
            BackupEngine(
                drive = drive,
                originals = originals,
                store = RoomUploadStore(database.backupDao()),
                clock = { System.currentTimeMillis() },
                rootFolder = driveRootFolder,
                zone = ZoneId.systemDefault(),
                log = { Log.w("BackupEngine", it) },
            )
        CloudBackup(engine, database.backupDao(), originals)
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
        // Building the Drive client stays off the main thread.
        appScope.launch { cloudBackup.start(appScope) }
    }
}
