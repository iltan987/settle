package com.iltan.settle.ui.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.iltan.settle.AppGraph
import com.iltan.settle.R
import com.iltan.settle.appGraph
import com.iltan.settle.data.system.SystemState
import com.iltan.settle.domain.backup.BackupPause
import com.iltan.settle.domain.deletion.ExternalDeletionPolicy
import com.iltan.settle.ui.LocalAppMessages
import com.iltan.settle.ui.chrome.RowButtonStyle
import com.iltan.settle.ui.chrome.RowTone
import com.iltan.settle.ui.chrome.SettingsGroup
import com.iltan.settle.ui.chrome.SettingsRow
import com.iltan.settle.ui.chrome.Trailing
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Settings' sub-pages, each pushed over the main page. */
enum class SettingsSection {
    BACKUP,
    BACKUP_FOLDERS,
    DELETION,
    KEEP_RUNNING,
    LANGUAGE,
    ABOUT,
}

/** The library's line on the main page. */
data class LibraryState(val count: Int = 0, val lastScanAtMs: Long? = null, val scanning: Boolean)

/** What the main page's rows do. */
class SettingsActions(
    val rescan: () -> Unit = {},
    val drive: DriveActions = DriveActions(),
    val open: (SettingsSection) -> Unit = {},
    val allowMediaManagement: () -> Unit = {},
)

/**
 * Settings (I1–I4): each row's sub carries its current value, so the page reads as a summary. Rows
 * and groups that aren't needed now are left out rather than greyed.
 */
@Composable
fun SettingsMainPage(
    library: LibraryState,
    drive: DriveSettingsState,
    backup: BackupSettingsState,
    policy: ExternalDeletionPolicy,
    canDeleteWithoutAsking: Boolean,
    system: SystemState,
    language: AppLanguage,
    version: String,
    actions: SettingsActions,
) {
    SettingsGroup(title = stringResource(R.string.settings_library)) {
        row {
            SettingsRow(
                title =
                    pluralStringResource(
                        R.plurals.settings_library_count,
                        library.count,
                        library.count,
                    ),
                sub =
                    when {
                        library.scanning -> stringResource(R.string.settings_checking)
                        library.lastScanAtMs != null ->
                            stringResource(
                                R.string.settings_last_scan,
                                whenText(library.lastScanAtMs),
                            )
                        else -> stringResource(R.string.settings_never_scanned)
                    },
                icon = R.drawable.ic_photo_library,
                trailing =
                    Trailing.Action(
                        stringResource(R.string.settings_rescan),
                        RowButtonStyle.OUTLINE,
                        actions.rescan,
                        enabled = !library.scanning,
                    ),
            )
        }
    }
    DriveGroup(drive, actions.drive)
    if (backup.loaded) {
        SettingsGroup(title = stringResource(R.string.settings_backup)) {
            row { BackupSummaryRow(backup) { actions.open(SettingsSection.BACKUP) } }
        }
    }
    SettingsGroup(title = stringResource(R.string.settings_deletion)) {
        row {
            SettingsRow(
                title = stringResource(R.string.deletion_policy_title),
                sub = stringResource(policy.label),
                icon = R.drawable.ic_delete,
                trailing = Trailing.Chevron,
                onClick = { actions.open(SettingsSection.DELETION) },
            )
        }
        if (!canDeleteWithoutAsking) {
            row { MediaManagementRow(actions.allowMediaManagement) }
        }
    }
    if (drive.email != null && system.keepRunningShown) {
        SettingsGroup(title = stringResource(R.string.keep_running_title)) {
            row { KeepRunningRow(system) { actions.open(SettingsSection.KEEP_RUNNING) } }
        }
    }
    SettingsGroup(title = stringResource(R.string.settings_general)) {
        row {
            SettingsRow(
                title = stringResource(R.string.settings_language),
                icon = R.drawable.ic_language,
                trailing = Trailing.Value(stringResource(language.label)),
                onClick = { actions.open(SettingsSection.LANGUAGE) },
            )
        }
        row {
            SettingsRow(
                title = stringResource(R.string.about_title),
                icon = R.drawable.ic_info,
                trailing = Trailing.Value(version),
                onClick = { actions.open(SettingsSection.ABOUT) },
            )
        }
    }
}

/** The Backup group's one line: counts or the pause, and what comes next. */
@Composable
private fun BackupSummaryRow(state: BackupSettingsState, onClick: () -> Unit) {
    val pause = state.pause
    val run = state.run
    when {
        pause == BackupPause.NOT_CONNECTED ->
            SettingsRow(
                title = stringResource(R.string.backup_off),
                sub = stringResource(R.string.backup_off_sub),
                icon = R.drawable.ic_cloud_off,
            )
        pause != null ->
            SettingsRow(
                title = stringResource(R.string.status_paused),
                sub =
                    listOf(
                            stringResource(pause.reasonText),
                            pluralStringResource(
                                R.plurals.backup_count_waiting,
                                state.upcoming,
                                state.upcoming,
                            ),
                        )
                        .joinToString(" · "),
                icon = R.drawable.ic_pause_circle_filled,
                tone = RowTone.WARN,
                trailing = Trailing.Chevron,
                onClick = onClick,
            )
        else ->
            SettingsRow(
                title = backedUpAndWaiting(state.backedUp, state.upcoming),
                sub =
                    when {
                        state.needsAttention > 0 ->
                            pluralStringResource(
                                R.plurals.status_needs_attention,
                                state.needsAttention,
                                state.needsAttention,
                            )
                        run != null ->
                            pluralStringResource(
                                R.plurals.header_backing_up,
                                run.totalItems,
                                run.doneItems,
                                run.totalItems,
                            )
                        state.upcoming > 0 -> nextBackup(state)
                        state.backedUp == 0 -> stringResource(R.string.backup_nothing_yet_sub)
                        else -> state.lastBackupAtMs?.let { lastBackup(it) }
                    },
                subTone = if (state.needsAttention > 0) RowTone.ERR else RowTone.PLAIN,
                icon = R.drawable.ic_cloud_done_filled,
                tone = RowTone.PRIMARY,
                trailing = Trailing.Chevron,
                onClick = onClick,
            )
    }
}

/** Why backup is paused, in a few words. */
private val BackupPause.reasonText: Int
    get() =
        when (this) {
            BackupPause.NOT_CONNECTED -> R.string.status_drive_not_connected
            BackupPause.AUTH_NEEDED -> R.string.pause_reason_sign_in
            BackupPause.QUOTA_FULL -> R.string.notice_drive_full_title
            BackupPause.NO_ORIGINAL_ACCESS -> R.string.pause_reason_location
            BackupPause.BY_USER -> R.string.pause_reason_by_you
        }

/** When the next automatic backup goes, by the user's choices. */
@Composable
internal fun nextBackup(state: BackupSettingsState): String {
    val settings = state.choices.settings
    return stringResource(
        when {
            !settings.automatic -> R.string.backup_next_manual
            settings.onlyWhileCharging && !settings.useMobileData -> R.string.backup_next_tonight
            settings.onlyWhileCharging -> R.string.backup_next_charger
            !settings.useMobileData -> R.string.backup_next_wifi
            else -> R.string.backup_next_soon
        }
    )
}

/** "today at 09:58", "yesterday at 21:40", or "25 October at 03:12". */
@Composable
internal fun whenText(atMillis: Long): String {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val zone = ZoneId.systemDefault()
    val at = Instant.ofEpochMilli(atMillis).atZone(zone)
    val time = DateFormat.getTimeFormat(context).format(atMillis)
    val today = LocalDate.now(zone)
    return when (at.toLocalDate()) {
        today -> stringResource(R.string.when_today, time)
        today.minusDays(1) -> stringResource(R.string.when_yesterday, time)
        else ->
            stringResource(
                R.string.when_date,
                at.format(
                    DateTimeFormatter.ofPattern(
                        DateFormat.getBestDateTimePattern(locale, "dMMMM"),
                        locale,
                    )
                ),
                time,
            )
    }
}

// Wiring: each page with its view model, Android's settings pages and permission requests.

private fun settingsViewModel(graph: AppGraph) =
    BackupSettingsViewModel(
        dao = graph.database.backupDao(),
        library = graph.library,
        preferences = graph.backupPreferences,
        scheduler = graph.backupScheduler,
        cloudBackup = graph.cloudBackup,
        notifications = graph.backupNotifications,
        counts = graph.backupStatus.counts,
        run = graph.backupStatus.run,
    )

private fun driveViewModel(graph: AppGraph) =
    DriveSettingsViewModel(
        dao = graph.database.backupDao(),
        auth = graph.driveAuth,
        drive = graph.drive,
        sandbox = graph.driveSandbox,
        folder = graph.driveRootFolder,
        onAccountChanged = { connected ->
            if (connected && graph.backupPreferences.unblock(BackupPause.AUTH_NEEDED)) {
                graph.backupNotifications.clearPause()
                // Backups the user asked for continue now, while Settle is in front.
                graph.cloudBackup.backUpNow(emptyList())
            }
            graph.backupScheduler.reschedule()
        },
        needsReconnect =
            graph.backupPreferences.choices.map { it.blocked == BackupPause.AUTH_NEEDED },
    )

/** The main Settings page, wired to the app. */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpen: (SettingsSection) -> Unit,
    /** Opened to sign in again: start Google's account picker at once. */
    reconnect: Boolean = false,
    onReconnectStarted: () -> Unit = {},
) {
    val context = LocalContext.current
    val graph = context.appGraph
    val resources = LocalResources.current
    val messages = LocalAppMessages.current
    val drive = viewModel { driveViewModel(graph) }
    val backup = viewModel { settingsViewModel(graph) }
    val driveState by drive.state.collectAsStateWithLifecycle()
    val backupState by backup.state.collectAsStateWithLifecycle()
    val consent by drive.consent.collectAsStateWithLifecycle()
    val count by remember { graph.library.onPhoneCount() }.collectAsStateWithLifecycle(0)
    val lastScan by remember { graph.library.lastScanAt() }.collectAsStateWithLifecycle(null)
    val sync by graph.librarySync.state.collectAsStateWithLifecycle()
    val system by graph.deviceSetup.state.collectAsStateWithLifecycle()
    val policy by
        graph.deletion.policy.collectAsStateWithLifecycle(
            ExternalDeletionPolicy.MOVE_DRIVE_COPY_TO_TRASH
        )
    var silent by remember { mutableStateOf(graph.deletion.canDeleteWithoutAsking()) }
    // The user comes back from Special app access with it changed.
    LifecycleResumeEffect(Unit) {
        silent = graph.deletion.canDeleteWithoutAsking()
        onPauseOrDispose {}
    }
    val version = remember {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    }
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
            drive.onConsentResult(it.resultCode, it.data)
        }
    LaunchedEffect(reconnect, driveState.needsReconnect) {
        if (reconnect && driveState.needsReconnect) {
            onReconnectStarted()
            drive.connect()
        }
    }
    LaunchedEffect(consent) {
        consent?.let {
            drive.consentLaunched()
            launcher.launch(IntentSenderRequest.Builder(it).build())
        }
    }
    LaunchedEffect(driveState.notice) {
        driveState.notice?.let {
            drive.noticeShown()
            messages.note(resources.getString(it.message))
        }
    }
    SettingsPage(stringResource(R.string.settings_title), onBack) {
        SettingsMainPage(
            library = LibraryState(count, lastScan, sync.scanning),
            drive = driveState,
            backup = backupState,
            policy = policy,
            canDeleteWithoutAsking = silent,
            system = system,
            language = AppLanguage.current(context),
            version = version,
            actions =
                SettingsActions(
                    rescan = { graph.librarySync.requestScan(full = true) },
                    drive =
                        DriveActions(
                            connect = drive::connect,
                            disconnect = drive::disconnect,
                            openFolder = { openDriveFolder(context, it) },
                            resetSandbox = drive::resetSandbox,
                        ),
                    open = onOpen,
                    allowMediaManagement = { openMediaManagement(context) },
                ),
        )
    }
}

/** Settings → Backup, wired to the app. */
@Composable
fun BackupSettingsScreen(
    onBack: () -> Unit,
    onOpenFolders: () -> Unit,
    onOpenAttention: () -> Unit,
    onConnect: () -> Unit,
    onSignIn: () -> Unit,
) {
    SettingsPage(stringResource(R.string.settings_backup), onBack) {
        BackupSettingsContent(onOpenFolders, onOpenAttention, onConnect, onSignIn)
    }
}

/** The Backup page's groups, wired to the app. */
@Composable
fun BackupSettingsContent(
    onOpenFolders: () -> Unit,
    onOpenAttention: () -> Unit,
    onConnect: () -> Unit,
    onSignIn: () -> Unit,
) {
    val graph = LocalContext.current.appGraph
    val viewModel = viewModel { settingsViewModel(graph) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val askLocation =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            graph.deviceSetup.refresh()
            viewModel.refresh()
        }
    BackupPage(
        state = state,
        debugOptions = graph.debuggable,
        actions =
            BackupActions(
                setAutomatic = viewModel::setAutomatic,
                setGrace = viewModel::setGrace,
                setOnlyWhileCharging = viewModel::setOnlyWhileCharging,
                setUseMobileData = viewModel::setUseMobileData,
                setNetworkUse = viewModel::setNetworkUse,
                setPaused = viewModel::setPaused,
                backUpEverythingNow = viewModel::backUpEverythingNow,
                retryAfterFull = viewModel::retryAfterFull,
                allowLocation = { askLocation.launch(Manifest.permission.ACCESS_MEDIA_LOCATION) },
                connect = onConnect,
                signIn = onSignIn,
                openAttention = onOpenAttention,
                openFolders = onOpenFolders,
            ),
    )
}

/** Settings → Backup → Folders to back up, wired to the app. */
@Composable
fun BackupFoldersScreen(onBack: () -> Unit) {
    val graph = LocalContext.current.appGraph
    val viewModel = viewModel { settingsViewModel(graph) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    SettingsPage(stringResource(R.string.backup_folders), onBack) {
        BackupFoldersPage(state.folders, viewModel::setFolderBackUp)
    }
}

/** Settings → Deletion, wired to the app. */
@Composable
fun DeletionScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val deletion = context.appGraph.deletion
    val scope = rememberCoroutineScope()
    val policy by
        deletion.policy.collectAsStateWithLifecycle(ExternalDeletionPolicy.MOVE_DRIVE_COPY_TO_TRASH)
    var silent by remember { mutableStateOf(deletion.canDeleteWithoutAsking()) }
    LifecycleResumeEffect(Unit) {
        silent = deletion.canDeleteWithoutAsking()
        onPauseOrDispose {}
    }
    SettingsPage(stringResource(R.string.settings_deletion), onBack) {
        DeletionPage(
            policy = policy,
            canDeleteWithoutAsking = silent,
            onPolicy = { scope.launch { deletion.setPolicy(it) } },
            onAllowDeletingWithoutAsking = { openMediaManagement(context) },
        )
    }
}

/** Keep backup running, wired to the app; it closes once nothing is left to do. */
@Composable
fun KeepRunningScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val graph = context.appGraph
    val system by graph.deviceSetup.state.collectAsStateWithLifecycle()
    val askNotifications =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            graph.deviceSetup.refresh()
        }
    LaunchedEffect(system.keepRunningShown) { if (!system.keepRunningShown) onBack() }
    SettingsPage(stringResource(R.string.keep_running_title), onBack) {
        KeepRunningPage(
            system,
            KeepRunningActions(
                allowNotifications = {
                    askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                },
                openAppInfo = { openAppInfo(context) },
                openNeverSleeping = { openNeverSleeping(context) },
            ),
        )
    }
}

/** Language, wired to the app. */
@Composable
fun LanguageScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var language by remember { mutableStateOf(AppLanguage.current(context)) }
    SettingsPage(stringResource(R.string.settings_language), onBack) {
        LanguagePage(language) {
            language = it
            AppLanguage.set(context, it)
        }
    }
}

/** About Settle, wired to the app. */
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val version = remember {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    }
    SettingsPage(stringResource(R.string.about_title), onBack) {
        AboutPage(
            version,
            AboutActions(
                openHome = { openUri(context, HOME_URL) },
                openPrivacy = { openUri(context, PRIVACY_URL) },
                sendFeedback = { openUri(context, "mailto:$FEEDBACK_ADDRESS") },
            ),
        )
    }
}

/** Settle's home page and privacy policy, on the `gh-pages` branch (ADR-013). */
private const val HOME_URL = "https://iltan987.github.io/settle/"
private const val PRIVACY_URL = "https://iltan987.github.io/settle/privacy.html"
/** The contact address the home page gives. */
private const val FEEDBACK_ADDRESS = "iltancanerr@gmail.com"

private fun openUri(context: Context, uri: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri.toUri())) }
}

private fun openDriveFolder(context: Context, folderId: String) {
    openUri(context, "https://drive.google.com/drive/folders/$folderId")
}

private val DriveNotice.message: Int
    get() =
        when (this) {
            DriveNotice.CONNECT_FAILED -> R.string.drive_connect_failed
            DriveNotice.SANDBOX_RESET -> R.string.drive_sandbox_reset_done
            DriveNotice.SANDBOX_RESET_FAILED -> R.string.drive_sandbox_reset_failed
        }
