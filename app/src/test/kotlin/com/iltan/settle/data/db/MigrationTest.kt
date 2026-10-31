package com.iltan.settle.data.db

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.core.app.ApplicationProvider
import com.iltan.settle.domain.CloudState
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Upgrading keeps the index and adds empty backup tables. The old database is built from its
 * committed schema, as `MigrationTestHelper` does; Room then migrates it and validates every table
 * against the current entities when it opens. (The helper itself reads schemas from app assets,
 * which local tests don't have.)
 */
@RunWith(RobolectricTestRunner::class)
class MigrationTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "migration-test.db"

    /** Creates an empty database exactly as schema [version] describes it. */
    private fun createDatabase(version: Int) {
        val schema =
            Json.parseToJsonElement(
                    File("schemas/${SettleDatabase::class.qualifiedName}/$version.json").readText()
                )
                .jsonObject
                .getValue("database")
                .jsonObject
        val statements = buildList {
            schema.getValue("entities").jsonArray.forEach { element ->
                val entity = element.jsonObject
                val table = entity.getValue("tableName").jsonPrimitive.content
                fun sql(json: JsonElement) =
                    json.jsonObject
                        .getValue("createSql")
                        .jsonPrimitive
                        .content
                        .replace("\${TABLE_NAME}", table)
                add(sql(entity))
                entity["indices"]?.jsonArray?.forEach { add(sql(it)) }
            }
            schema.getValue("setupQueries").jsonArray.forEach { add(it.jsonPrimitive.content) }
            add("PRAGMA user_version = $version")
        }
        AndroidSQLiteDriver().open(context.getDatabasePath(name).path).use { connection ->
            statements.forEach(connection::execSQL)
        }
    }

    @Test
    fun version1DataSurvivesTheUpgradeToVersion2() = runTest {
        createDatabase(1)
        AndroidSQLiteDriver().open(context.getDatabasePath(name).path).use {
            it.execSQL(
                """
                INSERT INTO media_item (id, kind, taken_at_ms, mime_type, width, height,
                    orientation, duration_ms, special_format, is_favorite, first_seen_at_ms,
                    cloud_state)
                VALUES ('01ITEM', 'IMAGE', 5, 'image/jpeg', 1, 1, 0, 0, 0, 1, 5, 'NONE')
                """
            )
            it.execSQL(
                """
                INSERT INTO local_file (item_id, volume, media_store_id, relative_path,
                    display_name, size_bytes, date_modified_s, generation_modified, local_state)
                VALUES ('01ITEM', 'external_primary', 7, 'DCIM/Camera/', 'IMG.jpg', 10, 1, 1,
                    'PRESENT')
                """
            )
        }

        val db =
            Room.databaseBuilder<SettleDatabase>(context, name)
                .setDriver(AndroidSQLiteDriver())
                .build()
        try {
            val entry = db.libraryDao().timeline().first().single()
            assertEquals("01ITEM", entry.id)
            assertEquals(CloudState.NONE, entry.cloudState)
            assertNull(db.backupDao().upload("01ITEM"))
            assertNull(db.backupDao().accountEmail())
        } finally {
            db.close()
        }
    }

    @Test
    fun version2DataSurvivesTheUpgradeToVersion3() = runTest {
        createDatabase(2)
        AndroidSQLiteDriver().open(context.getDatabasePath(name).path).use {
            it.execSQL(
                """
                INSERT INTO media_item (id, kind, taken_at_ms, mime_type, width, height,
                    orientation, duration_ms, special_format, is_favorite, first_seen_at_ms,
                    cloud_state)
                VALUES ('01ITEM', 'IMAGE', 5, 'image/jpeg', 1, 1, 0, 0, 0, 0, 5, 'VERIFIED')
                """
            )
            it.execSQL(
                """
                INSERT INTO local_file (item_id, volume, media_store_id, relative_path,
                    display_name, size_bytes, date_modified_s, generation_modified, local_state)
                VALUES ('01ITEM', 'external_primary', 7, 'DCIM/Screenshots/', 'IMG.jpg', 10, 1,
                    1, 'PRESENT')
                """
            )
            it.execSQL(
                """
                INSERT INTO folder_setting (relative_path, hidden_from_timeline)
                VALUES ('DCIM/Screenshots/', 1)
                """
            )
            it.execSQL(
                """
                INSERT INTO upload (item_id, drive_file_id, state, size_bytes, date_modified_s,
                    confirmed_bytes, sha256, md5, verified_at_ms)
                VALUES ('01ITEM', 'drive1', 'VERIFIED', 10, 1, 10, 'aa', 'bb', 9)
                """
            )
        }

        val db =
            Room.databaseBuilder<SettleDatabase>(context, name)
                .setDriver(AndroidSQLiteDriver())
                .build()
        try {
            assertEquals(
                FolderSettingEntity("DCIM/Screenshots/", hiddenFromTimeline = true, backUp = null),
                db.libraryDao().folderSetting("DCIM/Screenshots/"),
            )
            assertEquals(CloudState.VERIFIED, db.backupDao().upload("01ITEM")?.state)
            assertEquals(1, db.backupDao().backedUpCount().first())
            assertNull(db.backupDao().settings())
            assertNull(db.backupDao().retry("01ITEM"))
        } finally {
            db.close()
        }
    }
}
