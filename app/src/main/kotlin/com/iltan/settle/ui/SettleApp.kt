package com.iltan.settle.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
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
import com.iltan.settle.ui.folders.CollectionScreen
import com.iltan.settle.ui.folders.CollectionViewModel
import com.iltan.settle.ui.folders.FoldersScreen
import com.iltan.settle.ui.folders.FoldersViewModel
import com.iltan.settle.ui.onboarding.MediaAccessGate
import com.iltan.settle.ui.settings.SettingsScreen
import com.iltan.settle.ui.timeline.TimelineScreen
import com.iltan.settle.ui.timeline.TimelineViewModel
import com.iltan.settle.ui.viewer.ViewerScreen
import com.iltan.settle.ui.viewer.ViewerViewModel
import kotlinx.serialization.Serializable

@Serializable data object Timeline : NavKey

@Serializable data object Folders : NavKey

@Serializable data class Folder(val relativePath: String, val name: String) : NavKey

@Serializable data object Favorites : NavKey

@Serializable data class Viewer(val source: GridSource, val itemId: String) : NavKey

@Serializable data object Settings : NavKey

/** Root of the UI: onboarding until media access is granted, then one Navigation 3 back stack. */
@Composable
fun SettleApp(openSettings: Boolean = false) {
    val context = LocalContext.current
    MediaAccessGate(onAccessGranted = { context.appGraph.librarySync.requestScan() }) {
        Navigation(openSettings)
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
private fun Navigation(openSettings: Boolean) {
    val context = LocalContext.current
    val graph = context.appGraph
    // A backup notification that needs the user opens Settings over the timeline.
    val backStack =
        if (openSettings) rememberNavBackStack(Timeline, Settings)
        else rememberNavBackStack(Timeline)
    val currentTab = Tab.entries.firstOrNull { it.key == backStack.lastOrNull() }

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
                        TimelineScreen(
                            state = state,
                            onOpen = { backStack.add(Viewer(GridSource.Timeline, it.id)) },
                            onOpenSettings = { backStack.add(Settings) },
                        )
                    }
                    entry<Folders> {
                        val viewModel = viewModel {
                            FoldersViewModel(graph.library) { graph.backupScheduler.reschedule() }
                        }
                        val state by viewModel.state.collectAsStateWithLifecycle()
                        FoldersScreen(
                            state = state,
                            onOpenFolder = { path, name -> backStack.add(Folder(path, name)) },
                            onOpenFavorites = { backStack.add(Favorites) },
                            onSetHidden = viewModel::setHiddenFromTimeline,
                            onSetBackUp = viewModel::setBackUp,
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
                    entry<Settings> { SettingsScreen(onBack = { backStack.removeLastOrNull() }) }
                },
        )
    }
}
