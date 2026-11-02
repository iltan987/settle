package com.iltan.settle.ui

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.NavEntryDecorator
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import com.iltan.settle.ui.theme.SettleMotion

/** The scopes a photo needs to fly between a grid cell and the viewer (D, shared element). */
class MediaTransition(val shared: SharedTransitionScope, val animated: AnimatedVisibilityScope)

/** Null outside navigation, as in screen tests: photos then simply don't fly. */
val LocalMediaTransition = compositionLocalOf<MediaTransition?> { null }

/** Gives every destination the shared-element scopes for [LocalMediaTransition]. */
fun <T : Any> mediaTransitionDecorator(shared: SharedTransitionScope): NavEntryDecorator<T> =
    NavEntryDecorator { entry ->
        CompositionLocalProvider(
            LocalMediaTransition provides
                MediaTransition(shared, LocalNavAnimatedContentScope.current)
        ) {
            entry.Content()
        }
    }

/**
 * The photo with [itemId] as one element in every screen showing it: opening it from a grid cell
 * grows the cell into the viewer, and closing the viewer shrinks it back into its cell (300 ms,
 * emphasized).
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.sharedMedia(itemId: String): Modifier {
    val transition = LocalMediaTransition.current ?: return this
    return with(transition.shared) {
        this@sharedMedia.sharedElement(
            rememberSharedContentState(mediaKey(itemId)),
            transition.animated,
            boundsTransform = { _, _ -> SettleMotion.sharedElement() },
        )
    }
}

/** Whether the screen is still coming in or going out, when a photo may be in flight. */
@Composable
fun mediaInFlight(): Boolean = LocalMediaTransition.current?.animated?.transition?.isRunning == true

private fun mediaKey(itemId: String) = "media-$itemId"
