package com.iltan.settle

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
        val openSettings = intent.getBooleanExtra(EXTRA_OPEN_SETTINGS, false)
        val reconnect = intent.getBooleanExtra(EXTRA_RECONNECT, false)
        setContent { SettleTheme { SettleApp(openSettings, reconnect) } }
    }

    companion object {
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
