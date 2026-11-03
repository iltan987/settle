package com.iltan.settle

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MainActivityTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun Intent.asking() =
        putExtra(MainActivity.EXTRA_OPEN_SETTINGS, true)
            .putExtra(MainActivity.EXTRA_RECONNECT, true)

    @Test
    fun settlesOwnNotificationsCanOpenSettingsAndSignIn() {
        val intent = Intent().setClassName(context, MainActivity.NOTIFICATION_TARGET).asking()

        assertEquals(
            MainActivity.OpenRequest(openSettings = true, reconnect = true),
            MainActivity.openRequest(intent),
        )
    }

    @Test
    fun anotherAppStartingSettleCantAskForEither() {
        // Any app may start the launcher activity, with any extras.
        val intent = Intent(context, MainActivity::class.java).asking()

        assertEquals(MainActivity.OpenRequest(), MainActivity.openRequest(intent))
    }
}
