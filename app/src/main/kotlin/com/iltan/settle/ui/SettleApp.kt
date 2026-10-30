package com.iltan.settle.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.iltan.settle.appGraph
import com.iltan.settle.ui.onboarding.MediaAccessGate
import com.iltan.settle.ui.settings.SettingsScreen
import com.iltan.settle.ui.timeline.TimelineScreen
import com.iltan.settle.ui.timeline.TimelineViewModel
import kotlinx.serialization.Serializable

@Serializable data object Timeline : NavKey

@Serializable data object Settings : NavKey

/** Root of the UI: onboarding until media access is granted, then one Navigation 3 back stack. */
@Composable
fun SettleApp() {
    val context = LocalContext.current
    MediaAccessGate(onAccessGranted = { context.appGraph.librarySync.requestScan() }) {
        Navigation()
    }
}

@Composable
private fun Navigation() {
    val context = LocalContext.current
    val backStack = rememberNavBackStack(Timeline)
    NavDisplay(
        backStack = backStack,
        onBack = { backStack.removeLastOrNull() },
        entryDecorators =
            listOf(
                rememberSaveableStateHolderNavEntryDecorator(),
                rememberViewModelStoreNavEntryDecorator(),
            ),
        entryProvider =
            entryProvider {
                entry<Timeline> {
                    val viewModel = viewModel {
                        TimelineViewModel(context.appGraph.library, context.appGraph.librarySync)
                    }
                    val state by viewModel.state.collectAsStateWithLifecycle()
                    TimelineScreen(
                        state = state,
                        onOpen = {},
                        onOpenSettings = { backStack.add(Settings) },
                    )
                }
                entry<Settings> { SettingsScreen(onBack = { backStack.removeLastOrNull() }) }
            },
    )
}
