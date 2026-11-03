package com.iltan.settle

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.iltan.settle.ui.SettleApp
import com.iltan.settle.ui.theme.SettleTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Edge-to-edge is enforced for targetSdk 35+; this call also keeps system bar icons
        // readable in light and dark themes.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val request = openRequest(intent)
        setContent { SettleTheme { SettleApp(request.openSettings, request.reconnect) } }
    }

    /** Where Settle opens: the timeline, or for a notification, Settings or Google's sign-in. */
    data class OpenRequest(val openSettings: Boolean = false, val reconnect: Boolean = false)

    companion object {
        /**
         * The alias notifications open Settle through (manifest). Only Settle can start it, so its
         * extras are honoured; on the exported launcher activity any app could set them.
         */
        val NOTIFICATION_TARGET: String =
            MainActivity::class.java.name.substringBeforeLast('.') + ".NotificationTarget"

        /** What [intent] asks for, if it came from one of Settle's notifications. */
        fun openRequest(intent: Intent): OpenRequest =
            if (intent.component?.className != NOTIFICATION_TARGET) OpenRequest()
            else {
                OpenRequest(
                    openSettings = intent.getBooleanExtra(EXTRA_OPEN_SETTINGS, false),
                    reconnect = intent.getBooleanExtra(EXTRA_RECONNECT, false),
                )
            }

        /** Set by backup notifications that need the user in Settings. */
        const val EXTRA_OPEN_SETTINGS = "com.iltan.settle.OPEN_SETTINGS"

        /** Set by the "reconnect" notification: Settings starts Google's account picker. */
        const val EXTRA_RECONNECT = "com.iltan.settle.RECONNECT"
    }

    override fun onResume() {
        super.onResume()
        // Catches changes made while Settle was in the background, and a just-granted permission.
        appGraph.librarySync.requestScan()
        appGraph.deviceSetup.refresh()
        appGraph.appScope.launch { appGraph.deletion.resumeRestores() }
    }
}
