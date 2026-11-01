package com.iltan.settle.data

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.iltan.settle.data.db.SettleDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class UiPreferencesTest {
    private val db =
        Room.inMemoryDatabaseBuilder<SettleDatabase>(
                ApplicationProvider.getApplicationContext<Context>()
            )
            .setDriver(AndroidSQLiteDriver())
            .build()
    private var now = 1_800_000_000_000L
    private val prefs = UiPreferences(db.uiDao()) { now }

    @After fun tearDown() = db.close()

    @Test
    fun defaultsUntilChangedAndColumnsStayBetweenThreeAndSix() = runTest {
        assertEquals(4, prefs.prefs.first().gridColumns)
        assertFalse(prefs.prefs.first().mediaExplainerShown)

        prefs.setGridColumns(9)
        assertEquals(6, prefs.prefs.first().gridColumns)
        prefs.setGridColumns(1)
        assertEquals(3, prefs.prefs.first().gridColumns)

        prefs.mediaExplainerShown()
        val after = prefs.prefs.first()
        assertTrue(after.mediaExplainerShown)
        assertEquals(3, after.gridColumns)
    }

    @Test
    fun aDismissedNoticeComesBackADayLaterOrWhenItsProblemReturns() = runTest {
        prefs.dismiss("pause:QUOTA_FULL")
        assertEquals(setOf("pause:QUOTA_FULL"), prefs.dismissed.first())

        now += UiPreferences.NOTICE_BACK_AFTER_MILLIS
        assertEquals(emptySet<String>(), prefs.dismissed.first())

        prefs.dismiss("pause:QUOTA_FULL")
        prefs.noticesNow(emptyList())
        assertEquals(emptySet<String>(), prefs.dismissed.first())
    }
}
