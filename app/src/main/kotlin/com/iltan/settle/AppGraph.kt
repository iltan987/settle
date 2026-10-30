package com.iltan.settle

import android.content.Context
import com.iltan.settle.data.db.SettleDatabase
import com.iltan.settle.data.media.LibrarySync
import com.iltan.settle.data.media.MediaAccess
import com.iltan.settle.data.media.MediaChangeObserver
import com.iltan.settle.data.media.MediaStoreSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** The app's long-lived objects, wired by hand (ADR-007). One instance per process. */
class AppGraph(context: Context) {
    private val appContext = context.applicationContext

    /** Work that outlives any screen, such as scanning. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val database: SettleDatabase = SettleDatabase.create(appContext)

    val librarySync =
        LibrarySync(
            source = MediaStoreSource(appContext),
            dao = database.libraryDao(),
            hasFullAccess = { MediaAccess.of(appContext) == MediaAccess.FULL },
        )

    fun start() {
        librarySync.start(appScope)
        MediaChangeObserver { librarySync.requestScan() }.register(appContext.contentResolver)
    }
}
