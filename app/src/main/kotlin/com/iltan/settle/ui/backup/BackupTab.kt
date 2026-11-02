package com.iltan.settle.ui.backup

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.iltan.settle.R
import com.iltan.settle.ui.chrome.AccountButton
import com.iltan.settle.ui.chrome.SettingsColumn
import com.iltan.settle.ui.chrome.SettleTopBar
import com.iltan.settle.ui.settings.BackupSettingsContent

/**
 * The Backup tab. Until its own design (J) is built, it holds the Backup page of Settings, so the
 * tab bar already has its final shape.
 */
@Composable
fun BackupTab(
    accountEmail: String?,
    onOpenSettings: () -> Unit,
    onOpenFolders: () -> Unit,
    onSignIn: () -> Unit,
) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize()) {
            SettleTopBar(stringResource(R.string.nav_backup)) {
                AccountButton(accountEmail, onOpenSettings)
            }
            Box(Modifier.weight(1f)) {
                SettingsColumn {
                    BackupSettingsContent(
                        onOpenFolders = onOpenFolders,
                        onOpenAttention = {},
                        onConnect = onOpenSettings,
                        onSignIn = onSignIn,
                    )
                }
            }
        }
    }
}
