package com.iltan.settle.ui.backup

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.iltan.settle.R
import com.iltan.settle.ui.LocalAppMessages
import com.iltan.settle.ui.chrome.AccountButton
import com.iltan.settle.ui.chrome.BottomBarHeight
import com.iltan.settle.ui.chrome.SettleTopBar
import com.iltan.settle.ui.settings.BackupSettings
import com.iltan.settle.ui.settings.DriveSettings

/**
 * The Backup tab. Until its own design (J) is built, it holds the Drive and backup sections that
 * Settings has, so the tab bar already has its final shape.
 */
@Composable
fun BackupTab(accountEmail: String?, onOpenSettings: () -> Unit) {
    val bottom =
        WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + BottomBarHeight
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize()) {
            SettleTopBar(stringResource(R.string.nav_backup)) {
                AccountButton(accountEmail, onOpenSettings)
            }
            Box(Modifier.weight(1f)) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = bottom)) {
                    DriveSettings(LocalAppMessages.current.host)
                    BackupSettings()
                }
            }
        }
    }
}
