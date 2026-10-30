package com.iltan.settle.data.db

import android.content.Context
import androidx.room3.Database
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.sqlite.driver.AndroidSQLiteDriver

@Database(
    entities =
        [
            MediaItemEntity::class,
            LocalFileEntity::class,
            FolderSettingEntity::class,
            ScanCursorEntity::class,
        ],
    version = 1,
)
abstract class SettleDatabase : RoomDatabase() {
    abstract fun libraryDao(): LibraryDao

    companion object {
        // The framework SQLite driver: no native library to ship, and the same engine the
        // Robolectric tests run on (ADR-012).
        fun create(context: Context): SettleDatabase =
            Room.databaseBuilder<SettleDatabase>(context, "settle.db")
                .setDriver(AndroidSQLiteDriver())
                .build()
    }
}
