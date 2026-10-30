package com.iltan.settle.ui

import androidx.compose.runtime.Composable
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.iltan.settle.ui.settings.SettingsScreen
import com.iltan.settle.ui.timeline.TimelineScreen
import kotlinx.serialization.Serializable

@Serializable data object Timeline : NavKey

@Serializable data object Settings : NavKey

/** Root of the UI: one Navigation 3 back stack that starts at the timeline. */
@Composable
fun SettleApp() {
    val backStack = rememberNavBackStack(Timeline)
    NavDisplay(
        backStack = backStack,
        onBack = { backStack.removeLastOrNull() },
        entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator()),
        entryProvider =
            entryProvider {
                entry<Timeline> { TimelineScreen(onOpenSettings = { backStack.add(Settings) }) }
                entry<Settings> { SettingsScreen(onBack = { backStack.removeLastOrNull() }) }
            },
    )
}
