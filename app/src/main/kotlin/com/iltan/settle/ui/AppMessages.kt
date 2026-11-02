package com.iltan.settle.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.iltan.settle.ui.theme.SettleMotion
import com.iltan.settle.ui.theme.SettleShape
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A message with Tidepool's options: Undo offers get a 10 s countdown bar (E3), and a [brief] note
 * such as "Restored" leaves after 2 s.
 */
private class SettleVisuals(
    override val message: String,
    override val actionLabel: String?,
    val countdown: Boolean,
    val brief: Boolean = false,
) : SnackbarVisuals {
    override val withDismissAction = false
    // Countdowns and notes dismiss themselves; plain messages stay 4 s, as the design says.
    override val duration =
        if (countdown || brief) SnackbarDuration.Indefinite else SnackbarDuration.Short
}

/**
 * The activity's messages (ADR-016). They belong to the app, not to a screen, so a snackbar
 * outlives the screen that showed it: deleting the only item open in the viewer closes the viewer,
 * and its Undo stays (E4).
 */
@Stable
class AppMessages(val host: SnackbarHostState, private val scope: CoroutineScope) {
    /** How far above the bottom the snackbar sits; each screen sets it for its own bars. */
    var bottomOffset by mutableStateOf(0.dp)

    /** Runs [block] for as long as the app's messages live, e.g. work whose end is reported. */
    fun launch(block: suspend CoroutineScope.() -> Unit) {
        scope.launch(block = block)
    }

    /** Shows [text] for 4 s, with an optional [action]; [onAction] if tapped. */
    fun show(text: String, action: String? = null, onAction: () -> Unit = {}) {
        scope.launch {
            val result = host.showSnackbar(SettleVisuals(text, action, countdown = false))
            if (result == SnackbarResult.ActionPerformed) onAction()
        }
    }

    /** Shows [text] for 2 s: a confirmation such as "Restored". */
    fun note(text: String) {
        scope.launch {
            host.showSnackbar(SettleVisuals(text, null, countdown = false, brief = true))
        }
    }

    /** Shows [text] with [action] for 10 s, with a bar counting down; [onAction] if tapped. */
    fun offer(text: String, action: String, onAction: () -> Unit) {
        scope.launch {
            val result = host.showSnackbar(SettleVisuals(text, action, countdown = true))
            if (result == SnackbarResult.ActionPerformed) onAction()
        }
    }
}

val LocalAppMessages =
    staticCompositionLocalOf<AppMessages> { error("AppMessages is provided by SettleApp") }

/** Tells the app's snackbar how much bottom chrome this screen has while it is shown. */
@Composable
fun SnackbarAbove(offset: Dp) {
    val messages = LocalAppMessages.current
    DisposableEffect(offset) {
        messages.bottomOffset = offset
        onDispose { messages.bottomOffset = 0.dp }
    }
}

/**
 * The app's one snackbar host, drawn above every screen: [tabBarOffset] above the bottom on the
 * tabs, else as high as the current screen asked with [SnackbarAbove].
 */
@Composable
fun AppSnackbarHost(messages: AppMessages, tabBarOffset: Dp?, modifier: Modifier = Modifier) {
    SnackbarHost(
        hostState = messages.host,
        modifier =
            modifier
                .navigationBarsPadding()
                .padding(bottom = tabBarOffset ?: messages.bottomOffset),
    ) { data ->
        SettleSnackbar(data)
    }
}

/** Tidepool's snackbar: inverse surface, radius 12, an action that never truncates (Snackbar). */
@Composable
fun SettleSnackbar(data: SnackbarData) {
    val visuals = data.visuals
    val countdown = (visuals as? SettleVisuals)?.countdown == true
    if ((visuals as? SettleVisuals)?.brief == true) {
        LaunchedEffect(data) {
            delay(NOTE_MILLIS)
            data.dismiss()
        }
    }
    val left = remember(data) { Animatable(1f) }
    var touched by remember(data) { mutableStateOf(false) }
    if (countdown) {
        // Touching the snackbar pauses the timer; letting go continues it.
        LaunchedEffect(data, touched) {
            if (touched) return@LaunchedEffect
            val remaining = (left.value * SettleMotion.UNDO_MILLIS).toInt()
            left.animateTo(0f, tween(remaining, easing = LinearEasing))
            data.dismiss()
        }
    }
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = SettleShape.Snackbar,
        color = colors.inverseSurface,
        contentColor = colors.inverseOnSurface,
        shadowElevation = 6.dp,
        modifier =
            Modifier.padding(horizontal = 12.dp)
                .widthIn(max = 600.dp)
                .fillMaxWidth()
                .semantics { liveRegion = LiveRegionMode.Polite }
                .pointerInput(data) {
                    // Seen before the action button gets the event, so touching it pauses too.
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            touched = event.changes.any { it.pressed }
                        }
                    }
                },
    ) {
        Box {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.heightIn(min = 48.dp).padding(start = 16.dp, end = 4.dp),
            ) {
                Text(
                    visuals.message,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f).padding(vertical = 10.dp),
                )
                visuals.actionLabel?.let { label ->
                    TextButton(onClick = data::performAction) {
                        Text(
                            label,
                            style = MaterialTheme.typography.labelLarge,
                            color = colors.inversePrimary,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
            }
            if (countdown) {
                Box(
                    Modifier.align(Alignment.BottomStart)
                        .fillMaxWidth(left.value)
                        .height(3.dp)
                        .background(colors.inversePrimary)
                )
            }
        }
    }
}

private const val NOTE_MILLIS = 2_000L
