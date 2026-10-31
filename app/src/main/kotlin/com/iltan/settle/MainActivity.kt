package com.iltan.settle

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.iltan.settle.ui.SettleApp
import com.iltan.settle.ui.theme.SettleTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Edge-to-edge is enforced for targetSdk 35+; this call also keeps system bar icons
        // readable in light and dark themes.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val openSettings = intent.getBooleanExtra(EXTRA_OPEN_SETTINGS, false)
        setContent { SettleTheme { SettleApp(openSettings) } }
    }

    companion object {
        /** Set by backup notifications that need the user in Settings. */
        const val EXTRA_OPEN_SETTINGS = "com.iltan.settle.OPEN_SETTINGS"
    }

    override fun onResume() {
        super.onResume()
        // Catches changes made while Settle was in the background, and a just-granted permission.
        appGraph.librarySync.requestScan()
    }
}
