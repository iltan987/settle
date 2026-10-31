package com.iltan.settle.ui.settings

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.iltan.settle.R
import com.iltan.settle.appGraph

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var language by remember { mutableStateOf(AppLanguage.current(context)) }
    val versionName = remember {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    }

    val snackbar = remember { SnackbarHostState() }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painterResource(R.drawable.ic_arrow_back),
                            contentDescription = stringResource(R.string.navigate_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState())) {
            SectionTitle(R.string.settings_library)
            LibrarySection()
            SectionTitle(R.string.settings_drive)
            DriveSettings(snackbar)
            SectionTitle(R.string.settings_backup)
            BackupSettings()
            SectionTitle(R.string.settings_language)
            Column(Modifier.selectableGroup()) {
                AppLanguage.entries.forEach { option ->
                    ListItem(
                        selected = option == language,
                        onClick = {
                            language = option
                            AppLanguage.set(context, option)
                        },
                        leadingContent = {
                            RadioButton(selected = option == language, onClick = null)
                        },
                        content = { Text(stringResource(option.label)) },
                    )
                }
            }
            Text(
                stringResource(R.string.settings_version, versionName),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}

@Composable
private fun SectionTitle(title: Int) {
    Text(
        stringResource(title),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp),
    )
}

@Composable
private fun LibrarySection() {
    val graph = LocalContext.current.appGraph
    val count by remember {
        graph.library.onPhoneCount()
    }
        .collectAsStateWithLifecycle(initialValue = 0)
    val lastScan by remember { graph.library.lastScanAt() }.collectAsStateWithLifecycle(null)
    val sync by graph.librarySync.state.collectAsStateWithLifecycle()

    ListItem(
        content = { Text(pluralStringResource(R.plurals.settings_library_count, count, count)) },
        supportingContent = {
            Text(
                lastScan?.let {
                    stringResource(
                        R.string.settings_last_scan,
                        DateUtils.getRelativeTimeSpanString(it).toString(),
                    )
                } ?: stringResource(R.string.settings_never_scanned)
            )
        },
    )
    ListItem(
        onClick = { graph.librarySync.requestScan(full = true) },
        enabled = !sync.scanning,
        content = { Text(stringResource(R.string.settings_rescan)) },
    )
}

private val AppLanguage.label: Int
    get() =
        when (this) {
            AppLanguage.SYSTEM -> R.string.language_system
            AppLanguage.ENGLISH -> R.string.language_english
            AppLanguage.TURKISH -> R.string.language_turkish
        }
