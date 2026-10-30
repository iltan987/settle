package com.iltan.settle.ui.viewer

import android.net.Uri
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.compose.material3.Player
import androidx.media3.ui.compose.material3.PlayerDefaults

// Media3's Compose UI is still marked unstable and experimental; its use is kept to this file.

/**
 * Plays [uri] with Media3's Material 3 controls, shown while [showControls] is true. A motion
 * photo's JPEG plays its embedded clip (Media3 reads the Motion Photo XMP), so motion photos use
 * this too, without controls.
 */
@OptIn(UnstableApi::class, ExperimentalApi::class)
@Composable
fun MediaPlayer(
    uri: Uri,
    mimeType: String,
    showControls: Boolean,
    modifier: Modifier = Modifier,
    /** Keeps what is underneath (the still of a motion photo) visible until the first frame. */
    transparentUntilFirstFrame: Boolean = false,
    onEnded: () -> Unit = {},
) {
    val context = LocalContext.current
    val currentOnEnded = rememberUpdatedState(onEnded)
    val player =
        remember(uri) {
            ExoPlayer.Builder(context).build().apply {
                // The MIME type lets Media3 pick the right extractor instead of sniffing a
                // content URI against every format, which took seconds for a 7 MB motion photo.
                setMediaItem(MediaItem.Builder().setUri(uri).setMimeType(mimeType).build())
                prepare()
                playWhenReady = true
            }
        }
    DisposableEffect(player) {
        val listener =
            object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) currentOnEnded.value()
                }
            }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }
    Player(
        player = player,
        modifier = modifier.fillMaxSize(),
        showControls = showControls,
        shutter = {
            if (!transparentUntilFirstFrame) Box(Modifier.fillMaxSize().background(Color.Black))
        },
        topControls = null,
        centerControls = { p, visible ->
            // One item at a time: no previous or next.
            PlayerDefaults.CenterControls(
                p,
                visible,
                Modifier.fillMaxWidth(),
                backSecondary = {},
                forwardSecondary = {},
            )
        },
        bottomControls = { p, visible ->
            PlayerDefaults.BottomControls(p, visible, Modifier.navigationBarsPadding())
        },
    )
}
