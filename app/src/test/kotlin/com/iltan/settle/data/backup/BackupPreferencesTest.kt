package com.iltan.settle.data.backup

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.iltan.settle.data.db.SettleDatabase
import com.iltan.settle.domain.backup.BackupPause
import com.iltan.settle.domain.deletion.ExternalDeletionPolicy
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BackupPreferencesTest {

    private val db =
        Room.inMemoryDatabaseBuilder<SettleDatabase>(
                ApplicationProvider.getApplicationContext<Context>()
            )
            .setDriver(AndroidSQLiteDriver())
            .build()
    private val preferences = BackupPreferences(db.backupDao())

    @After fun tearDown() = db.close()

    @Test
    fun theDeletionPolicyDefaultsToMovingTheDriveCopyToTrash() = runTest {
        assertEquals(
            ExternalDeletionPolicy.MOVE_DRIVE_COPY_TO_TRASH,
            preferences.current().externalDeletion,
        )
    }

    @Test
    fun theDeletionPolicySurvivesOtherChanges() = runTest {
        preferences.update { it.copy(externalDeletion = ExternalDeletionPolicy.ASK) }

        preferences.block(BackupPause.QUOTA_FULL)
        preferences.unblock(BackupPause.QUOTA_FULL)
        preferences.update { it.copy(settings = it.settings.copy(paused = true)) }

        assertEquals(ExternalDeletionPolicy.ASK, preferences.current().externalDeletion)
    }
}
