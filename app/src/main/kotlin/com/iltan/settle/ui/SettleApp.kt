package com.iltan.settle.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
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
import com.iltan.settle.domain.deletion.VanishedDecision
import com.iltan.settle.ui.folders.CollectionScreen
import com.iltan.settle.ui.folders.CollectionViewModel
import com.iltan.settle.ui.folders.FoldersScreen
import com.iltan.settle.ui.folders.FoldersViewModel
import com.iltan.settle.ui.onboarding.MediaAccessGate
import com.iltan.settle.ui.settings.SettingsScreen
import com.iltan.settle.ui.timeline.TimelineScreen
import com.iltan.settle.ui.timeline.TimelineViewModel
import com.iltan.settle.ui.trash.LocalItemActions
import com.iltan.settle.ui.trash.TrashScreen
import com.iltan.settle.ui.trash.TrashViewModel
import com.iltan.settle.ui.trash.rememberDeletionRunner
import com.iltan.settle.ui.trash.rememberItemActions
import com.iltan.settle.ui.viewer.ViewerScreen
import com.iltan.settle.ui.viewer.ViewerViewModel
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable data object Timeline : NavKey

@Serializable data object Folders : NavKey

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

/** The bottom bar's destinations. Photos is always the root, so back from Folders leads there. */
private enum class Tab(val key: NavKey, val label: Int, val icon: Int, val selectedIcon: Int) {
    PHOTOS(
        Timeline,
        R.string.timeline_title,
        R.drawable.ic_photo_library,
        R.drawable.ic_photo_library_filled,
    ),
    FOLDERS(Folders, R.string.folders_title, R.drawable.ic_folder, R.drawable.ic_folder_filled),
}

private fun NavBackStack<NavKey>.select(tab: Tab) {
    while (size > 1 && last() != tab.key) removeAt(lastIndex)
    if (last() != tab.key) add(tab.key)
}

@Composable
private fun Navigation(openSettings: Boolean, reconnect: Boolean) {
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
    CompositionLocalProvider(LocalAppMessages provides messages) {
        val actions = rememberItemActions(messages)
        CompositionLocalProvider(LocalItemActions provides actions) {
            Box(Modifier.fillMaxSize()) {
                Destinations(backStack, currentTab, reconnectPending) { reconnectPending = false }
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
private val TAB_BAR_OFFSET = 64.dp + 12.dp

@Composable
private fun Destinations(
    backStack: NavBackStack<NavKey>,
    currentTab: Tab?,
    reconnectPending: Boolean,
    onReconnectStarted: () -> Unit,
) {
    val graph = LocalContext.current.appGraph
    Scaffold(
        // Each screen handles its own insets; this Scaffold only adds the bar.
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            if (currentTab != null) {
                ShortNavigationBar {
                    Tab.entries.forEach { tab ->
                        val selected = tab == currentTab
                        ShortNavigationBarItem(
                            selected = selected,
                            onClick = { backStack.select(tab) },
                            icon = {
                                Icon(
                                    painterResource(if (selected) tab.selectedIcon else tab.icon),
                                    contentDescription = null,
                                )
                            },
                            label = { Text(stringResource(tab.label)) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavDisplay(
            backStack = backStack,
            onBack = { backStack.removeLastOrNull() },
            modifier = Modifier.padding(padding).consumeWindowInsets(padding),
            entryDecorators =
                listOf(
                    rememberSaveableStateHolderNavEntryDecorator(),
                    rememberViewModelStoreNavEntryDecorator(),
                ),
            entryProvider =
                entryProvider {
                    entry<Timeline> {
                        val viewModel = viewModel {
                            TimelineViewModel(graph.library, graph.librarySync)
                        }
                        val state by viewModel.state.collectAsStateWithLifecycle()
                        val breaker by graph.deletion.breakerCount.collectAsStateWithLifecycle(0)
                        fun decide(decision: VanishedDecision) {
                            graph.appScope.launch { graph.deletion.decide(null, decision) }
                        }
                        TimelineScreen(
                            state = state,
                            onOpen = { backStack.add(Viewer(GridSource.Timeline, it.id)) },
                            onOpenSettings = { backStack.add(Settings) },
                            actions = LocalItemActions.current,
                            breakerCount = breaker,
                            onBreakerKeep = { decide(VanishedDecision.KEEP_DRIVE_COPIES) },
                            onBreakerMove = { decide(VanishedDecision.MOVE_DRIVE_COPIES_TO_TRASH) },
                            onCheckAgain = { graph.librarySync.requestScan(full = true) },
                        )
                    }
                    entry<Folders> {
                        val viewModel = viewModel {
                            FoldersViewModel(graph.library) { graph.backupScheduler.reschedule() }
                        }
                        val state by viewModel.state.collectAsStateWithLifecycle()
                        val trash by graph.deletion.trash.collectAsStateWithLifecycle(emptyList())
                        FoldersScreen(
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
                    entry<Viewer> { key ->
                        val viewModel = viewModel {
                            ViewerViewModel(
                                graph.library,
                                key.source,
                                graph.database.backupDao(),
                                graph.cloudBackup,
                            )
                        }
                        ViewerScreen(
                            viewModel = viewModel,
                            initialId = key.itemId,
                            onBack = { backStack.removeLastOrNull() },
                            onOpenSettings = { backStack.add(Settings) },
                        )
                    }
                    entry<Trash> {
                        val viewModel = viewModel { TrashViewModel(graph.deletion) }
                        val state by viewModel.state.collectAsStateWithLifecycle()
                        val scope = rememberCoroutineScope()
                        val run = rememberDeletionRunner { _, _ -> }
                        val deletion = graph.deletion
                        TrashScreen(
                            state = state,
                            nowMillis = System.currentTimeMillis(),
                            onBack = { backStack.removeLastOrNull() },
                            onRestore = { scope.launch { run(deletion.restore(it)) } },
                            onDeleteForever = { scope.launch { run(deletion.deleteForever(it)) } },
                            onEmpty = { scope.launch { run(deletion.emptyTrash()) } },
                            onMoveDriveCopies = {
                                val ids = state.undecided.map { it.id }
                                graph.appScope.launch {
                                    deletion.decide(
                                        ids,
                                        VanishedDecision.MOVE_DRIVE_COPIES_TO_TRASH,
                                    )
                                }
                            },
                            onKeepDriveCopies = {
                                val ids = state.undecided.map { it.id }
                                graph.appScope.launch {
                                    deletion.decide(ids, VanishedDecision.KEEP_DRIVE_COPIES)
                                }
                            },
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
