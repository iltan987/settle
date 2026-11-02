package com.iltan.settle.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.provider.Settings.ACTION_INTERNAL_STORAGE_SETTINGS
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.iltan.settle.R
import com.iltan.settle.appGraph
import com.iltan.settle.data.GridSource
import com.iltan.settle.domain.backup.BackupHeadline
import com.iltan.settle.domain.deletion.VanishedDecision
import com.iltan.settle.ui.backup.BackupTab
import com.iltan.settle.ui.chrome.BottomBarFrame
import com.iltan.settle.ui.chrome.BottomBarHeight
import com.iltan.settle.ui.chrome.LocalTabBar
import com.iltan.settle.ui.chrome.NavItem
import com.iltan.settle.ui.chrome.TabBarState
import com.iltan.settle.ui.folders.CollectionScreen
import com.iltan.settle.ui.folders.CollectionViewModel
import com.iltan.settle.ui.folders.FoldersScreen
import com.iltan.settle.ui.folders.FoldersViewModel
import com.iltan.settle.ui.folders.folderNames
import com.iltan.settle.ui.onboarding.MediaAccessGate
import com.iltan.settle.ui.settings.SettingsScreen
import com.iltan.settle.ui.theme.SettleMotion
import com.iltan.settle.ui.timeline.NoticeHandlers
import com.iltan.settle.ui.timeline.TimelineScreen
import com.iltan.settle.ui.timeline.TimelineViewModel
import com.iltan.settle.ui.timeline.cellBackup
import com.iltan.settle.ui.trash.LocalItemActions
import com.iltan.settle.ui.trash.LocalTrashActions
import com.iltan.settle.ui.trash.RestoreIssueHandlers
import com.iltan.settle.ui.trash.TrashScreen
import com.iltan.settle.ui.trash.TrashViewModel
import com.iltan.settle.ui.trash.rememberItemActions
import com.iltan.settle.ui.trash.rememberTrashActions
import com.iltan.settle.ui.viewer.ViewerHandlers
import com.iltan.settle.ui.viewer.ViewerScreen
import com.iltan.settle.ui.viewer.ViewerViewModel
import com.iltan.settle.ui.viewer.openInDrive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable data object Timeline : NavKey

@Serializable data object Folders : NavKey

@Serializable data object Backup : NavKey

@Serializable data class Folder(val relativePath: String, val name: String) : NavKey

@Serializable data object Favorites : NavKey

@Serializable data class Viewer(val source: GridSource, val itemId: String) : NavKey

@Serializable data object Settings : NavKey

@Serializable data object Trash : NavKey

/** Root of the UI: onboarding until media access is granted, then one Navigation 3 back stack. */
@Composable
fun SettleApp(openSettings: Boolean = false, reconnect: Boolean = false) {
    val context = LocalContext.current
    MediaAccessGate(onAccessGranted = { context.appGraph.librarySync.requestScan() }) {
        Navigation(openSettings, reconnect)
    }
}

/** The bottom bar's destinations. Photos is always the root, so back from a tab leads there. */
private enum class Tab(val key: NavKey, val label: Int, val icon: Int, val selectedIcon: Int) {
    PHOTOS(
        Timeline,
        R.string.timeline_title,
        R.drawable.ic_photo_library,
        R.drawable.ic_photo_library_filled,
    ),
    FOLDERS(Folders, R.string.folders_title, R.drawable.ic_folder, R.drawable.ic_folder_filled),
    BACKUP(Backup, R.string.nav_backup, R.drawable.ic_cloud_sync, R.drawable.ic_cloud_sync_filled),
}

private fun NavBackStack<NavKey>.select(tab: Tab) {
    while (size > 1 && last() != tab.key) removeAt(lastIndex)
    if (last() != tab.key) add(tab.key)
}

@Composable
private fun Navigation(openSettings: Boolean, reconnect: Boolean) {
    val graph = LocalContext.current.appGraph
    // Started once, from the notification that opened Settle, not on every visit to Settings.
    var reconnectPending by rememberSaveable { mutableStateOf(reconnect) }
    // A backup notification that needs the user opens Settings over the timeline.
    val backStack =
        if (openSettings) rememberNavBackStack(Timeline, Settings)
        else rememberNavBackStack(Timeline)
    val currentTab = Tab.entries.firstOrNull { it.key == backStack.lastOrNull() }
    // Messages and item actions belong to the app, not to a screen (ADR-016): a deletion's
    // dialogs and its Undo outlive the viewer that started it.
    val scope = rememberCoroutineScope()
    val messages = remember { AppMessages(SnackbarHostState(), scope) }
    val tabBar = remember { TabBarState() }
    val headline by graph.backupStatus.headline.collectAsStateWithLifecycle()
    val breaker by graph.deletion.breakerCount.collectAsStateWithLifecycle(0)
    CompositionLocalProvider(LocalAppMessages provides messages, LocalTabBar provides tabBar) {
        val actions = rememberItemActions(messages)
        val trashActions = rememberTrashActions(messages)
        CompositionLocalProvider(
            LocalItemActions provides actions,
            LocalTrashActions provides trashActions,
        ) {
            Box(Modifier.fillMaxSize()) {
                Destinations(
                    backStack,
                    reconnectPending,
                    onReconnectStarted = { reconnectPending = false },
                    onSignIn = {
                        reconnectPending = true
                        backStack.add(Settings)
                    },
                    selectTab = backStack::select,
                )
                AnimatedVisibility(
                    visible = currentTab != null && !tabBar.hidden,
                    enter = fadeIn(SettleMotion.fade()),
                    exit = fadeOut(SettleMotion.fade()),
                    modifier = Modifier.align(Alignment.BottomCenter),
                ) {
                    BottomBarFrame {
                        Tab.entries.forEach { tab ->
                            NavItem(
                                selected = tab == currentTab,
                                icon = tab.icon,
                                selectedIcon = tab.selectedIcon,
                                label = stringResource(tab.label),
                                onClick = { backStack.select(tab) },
                                // Something waits for the user until it's resolved (B6, B9).
                                dot =
                                    tab == Tab.BACKUP &&
                                        (headline is BackupHeadline.NeedsAttention || breaker > 0),
                            )
                        }
                    }
                }
                AppSnackbarHost(
                    messages,
                    // Above the navigation bar on the tabs; screens with other bars say so.
                    tabBarOffset = if (currentTab != null) TAB_BAR_OFFSET else null,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
    }
}

/** Room for the navigation bar under the snackbar, plus the design's 12 dp gap. */
private val TAB_BAR_OFFSET = BottomBarHeight + 12.dp

@Composable
private fun Destinations(
    backStack: NavBackStack<NavKey>,
    reconnectPending: Boolean,
    onReconnectStarted: () -> Unit,
    onSignIn: () -> Unit,
    selectTab: (Tab) -> Unit,
) {
    val context = LocalContext.current
    val graph = context.appGraph
    // Photos fly between their grid cell and the viewer (D).
    SharedTransitionLayout {
        NavDisplay(
            backStack = backStack,
            onBack = { backStack.removeLastOrNull() },
            entryDecorators =
                listOf(
                    rememberSaveableStateHolderNavEntryDecorator(),
                    rememberViewModelStoreNavEntryDecorator(),
                    remember(this) { mediaTransitionDecorator(this) },
                ),
            sharedTransitionScope = this,
            entryProvider =
                entryProvider {
                    entry<Timeline> {
                        val viewModel = viewModel {
                            TimelineViewModel(
                                graph.library,
                                graph.librarySync,
                                graph.backupStatus,
                                graph.cloudBackup.progress,
                                graph.deletion.breakerCount,
                                graph.uiPreferences,
                            )
                        }
                        val state by viewModel.state.collectAsStateWithLifecycle()
                        val status by viewModel.status.collectAsStateWithLifecycle()
                        // Read inside each cell, so an upload's progress redraws only its cells.
                        val decisions = viewModel.decisions.collectAsStateWithLifecycle()
                        val uploads = viewModel.uploads.collectAsStateWithLifecycle()
                        val askLocation =
                            rememberLauncherForActivityResult(
                                ActivityResultContracts.RequestPermission()
                            ) {
                                graph.deviceSetup.refresh()
                            }
                        fun decide(decision: VanishedDecision) {
                            graph.appScope.launch { graph.deletion.decide(null, decision) }
                        }
                        TimelineScreen(
                            state = state,
                            onOpen = { backStack.add(Viewer(GridSource.Timeline, it.id)) },
                            onOpenSettings = { backStack.add(Settings) },
                            actions = LocalItemActions.current,
                            status = status,
                            notices =
                                NoticeHandlers(
                                    review = {
                                        val ids = viewModel.attentionIds()
                                        val inTimeline =
                                            state.sections.any { day ->
                                                day.items.any { it.id == ids.singleOrNull() }
                                            }
                                        when {
                                            // Restore problems are the only attention left.
                                            ids.isEmpty() -> backStack.add(Trash)
                                            inTimeline ->
                                                backStack.add(
                                                    Viewer(GridSource.Timeline, ids.single())
                                                )
                                            else -> selectTab(Tab.BACKUP)
                                        }
                                    },
                                    connect = { backStack.add(Settings) },
                                    signIn = onSignIn,
                                    manageStorage = { openUrl(context, MANAGE_STORAGE_URL) },
                                    allowLocation = {
                                        askLocation.launch(
                                            Manifest.permission.ACCESS_MEDIA_LOCATION
                                        )
                                    },
                                    resume = {
                                        graph.appScope.launch { graph.cloudBackup.setPaused(false) }
                                    },
                                    details = { selectTab(Tab.BACKUP) },
                                    dismiss = viewModel::dismiss,
                                    keepDriveCopies = {
                                        decide(VanishedDecision.KEEP_DRIVE_COPIES)
                                    },
                                    moveDriveCopies = {
                                        decide(VanishedDecision.MOVE_DRIVE_COPIES_TO_TRASH)
                                    },
                                    checkAgain = { graph.librarySync.requestScan(full = true) },
                                ),
                            onOpenBackup = { selectTab(Tab.BACKUP) },
                            backup = { entry -> cellBackup(entry, decisions.value, uploads.value) },
                            onColumnsChange = viewModel::setColumns,
                            onChooseFolders = { selectTab(Tab.FOLDERS) },
                        )
                    }
                    entry<Backup> {
                        val email by
                            graph.backupStatus.accountEmail.collectAsStateWithLifecycle(null)
                        BackupTab(email, onOpenSettings = { backStack.add(Settings) })
                    }
                    entry<Folders> {
                        val viewModel = viewModel {
                            FoldersViewModel(graph.library) { graph.backupScheduler.reschedule() }
                        }
                        val state by viewModel.state.collectAsStateWithLifecycle()
                        val trash by graph.deletion.trash.collectAsStateWithLifecycle(emptyList())
                        FoldersScreen(
                            modifier = Modifier.padding(bottom = BottomBarHeight),
                            state = state,
                            onOpenFolder = { path, name -> backStack.add(Folder(path, name)) },
                            onOpenFavorites = { backStack.add(Favorites) },
                            onSetHidden = viewModel::setHiddenFromTimeline,
                            onSetBackUp = viewModel::setBackUp,
                            trashCount = trash.size,
                            onOpenTrash = { backStack.add(Trash) },
                        )
                    }
                    entry<Folder> { key ->
                        val source = GridSource.Folder(key.relativePath)
                        val viewModel = viewModel { CollectionViewModel(graph.library, source) }
                        val state by viewModel.state.collectAsStateWithLifecycle()
                        CollectionScreen(
                            title = key.name,
                            state = state,
                            emptyText = stringResource(R.string.folder_empty),
                            onOpen = { backStack.add(Viewer(source, it.id)) },
                            onBack = { backStack.removeLastOrNull() },
                        )
                    }
                    entry<Favorites> {
                        val viewModel = viewModel {
                            CollectionViewModel(graph.library, GridSource.Favorites)
                        }
                        val state by viewModel.state.collectAsStateWithLifecycle()
                        CollectionScreen(
                            title = stringResource(R.string.favorites_title),
                            state = state,
                            emptyText = stringResource(R.string.favorites_empty),
                            onOpen = { backStack.add(Viewer(GridSource.Favorites, it.id)) },
                            onBack = { backStack.removeLastOrNull() },
                        )
                    }
                    entry<Viewer>(metadata = ViewerTransitions) { key ->
                        val viewModel = viewModel {
                            ViewerViewModel(
                                graph.library,
                                key.source,
                                graph.database.backupDao(),
                                graph.cloudBackup,
                                graph.backupPreferences,
                                graph.places,
                            ) {
                                graph.backupScheduler.reschedule()
                            }
                        }
                        ViewerScreen(
                            viewModel = viewModel,
                            initialId = key.itemId,
                            onBack = { backStack.removeLastOrNull() },
                            handlers =
                                ViewerHandlers(
                                    openSettings = { backStack.add(Settings) },
                                    signIn = onSignIn,
                                    manageStorage = { openUrl(context, MANAGE_STORAGE_URL) },
                                    reopen = { backStack.add(Viewer(key.source, it)) },
                                ),
                        )
                    }
                    entry<Trash> {
                        val viewModel = viewModel {
                            TrashViewModel(graph.deletion, graph.trashWork.restoring)
                        }
                        val state by viewModel.state.collectAsStateWithLifecycle()
                        val messages = LocalAppMessages.current
                        val resources = LocalResources.current
                        // A restore from Drive that ended well says so, and where (F6).
                        LaunchedEffect(viewModel) {
                            viewModel.finished.collect { outcome ->
                                val root = resources.getString(R.string.folder_root)
                                val path = outcome.folder
                                val name = path?.let { folderNames(listOf(it), root).getValue(it) }
                                val count = outcome.restored
                                if (path != null && name != null) {
                                    messages.show(
                                        resources.getQuantityString(
                                            R.plurals.trash_restored_checked_to,
                                            count,
                                            count,
                                            name,
                                        ),
                                        resources.getString(R.string.trash_show),
                                    ) {
                                        backStack.add(Folder(path, name))
                                    }
                                } else {
                                    messages.show(
                                        resources.getQuantityString(
                                            R.plurals.trash_restored_checked,
                                            count,
                                            count,
                                        )
                                    )
                                }
                            }
                        }
                        TrashScreen(
                            state = state,
                            nowMillis = System.currentTimeMillis(),
                            onBack = { backStack.removeLastOrNull() },
                            actions = LocalTrashActions.current,
                            issues =
                                RestoreIssueHandlers(
                                    signIn = onSignIn,
                                    openInDrive = { openInDrive(context, it) },
                                    freeUpSpace = {
                                        context.startActivity(
                                            Intent(ACTION_INTERNAL_STORAGE_SETTINGS)
                                        )
                                    },
                                ),
                        )
                    }
                    entry<Settings> {
                        SettingsScreen(
                            onBack = { backStack.removeLastOrNull() },
                            reconnect = reconnectPending,
                            onReconnectStarted = onReconnectStarted,
                        )
                    }
                },
        )
    }
}

/**
 * The viewer fades over the grid while the photo grows out of its cell, and fades away while it
 * shrinks back (300 ms); the grid stays put underneath.
 */
private val ViewerTransitions =
    NavDisplay.transitionSpec {
        fadeIn(SettleMotion.sharedElement()) togetherWith
            ExitTransition.KeepUntilTransitionsFinished
    } +
        NavDisplay.popTransitionSpec {
            EnterTransition.None togetherWith fadeOut(SettleMotion.sharedElement())
        } +
        NavDisplay.predictivePopTransitionSpec {
            EnterTransition.None togetherWith fadeOut(SettleMotion.sharedElement())
        }

/** Google One's storage page, where Drive space is freed or bought (B7). */
private const val MANAGE_STORAGE_URL = "https://one.google.com/storage"

private fun openUrl(context: Context, url: String) {
    context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
}
