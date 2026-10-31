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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.compose.SURFACE_TYPE_SURFACE_VIEW
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import androidx.media3.ui.compose.material3.Player
import androidx.media3.ui.compose.material3.PlayerDefaults

// Media3's Compose UI is still marked unstable and experimental; its use is kept to this file.

/**
 * Plays [uri] with Media3's Material 3 controls, shown while [showControls] is true, as long as
 * [play] is true.
 *
 * With [motionPhoto], plays the clip embedded in a motion photo. Given [videoOffset] (see
 * `MotionPhotos`), only the bytes from there on are read, as a plain MP4; without it Media3 walks
 * the whole JPEG first, which took ~1.8 s on the S24 FE. The clip is muted, as in other galleries,
 * and has no audio output at all. It is drawn on a TextureView that stays invisible until its first
 * frame, so the still photo underneath shows instead of a black SurfaceView.
 */
@OptIn(UnstableApi::class, ExperimentalApi::class)
@Composable
fun MediaPlayer(
    uri: Uri,
    mimeType: String,
    showControls: Boolean,
    modifier: Modifier = Modifier,
    play: Boolean = true,
    motionPhoto: Boolean = false,
    videoOffset: Long? = null,
    onEnded: () -> Unit = {},
) {
    val context = LocalContext.current
    val currentOnEnded = rememberUpdatedState(onEnded)
    var firstFrameShown by remember(uri) { mutableStateOf(false) }
    val player =
        remember(uri) {
            ExoPlayer.Builder(
                    context,
                    if (motionPhoto) SilentRenderers(context) else DefaultRenderersFactory(context),
                )
                .build()
                .apply {
                    if (videoOffset != null) {
                        setMediaSource(embeddedVideo(context, uri, videoOffset))
                    } else {
                        // Content URIs have no file extension; the MIME type spares sniffing.
                        setMediaItem(MediaItem.Builder().setUri(uri).setMimeType(mimeType).build())
                    }
                    prepare()
                }
        }
    LaunchedEffect(player, play) { player.playWhenReady = play }
    DisposableEffect(player) {
        val listener =
            object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) {
                        if (motionPhoto) {
                            player.playWhenReady = false
                            player.seekTo(0)
                        }
                        currentOnEnded.value()
                    }
                }

                override fun onRenderedFirstFrame() {
                    firstFrameShown = true
                }
            }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }
    val visible = !motionPhoto || (play && firstFrameShown)
    Player(
        player = player,
        modifier = modifier.fillMaxSize().alpha(if (visible) 1f else 0f),
        surfaceType = if (motionPhoto) SURFACE_TYPE_TEXTURE_VIEW else SURFACE_TYPE_SURFACE_VIEW,
        showControls = showControls,
        shutter = { if (!motionPhoto) Box(Modifier.fillMaxSize().background(Color.Black)) },
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

/** Renderers without audio output: no audio sink means no audio renderers at all. */
@OptIn(UnstableApi::class)
private class SilentRenderers(context: android.content.Context) : DefaultRenderersFactory(context) {
    override fun buildAudioSink(
        context: android.content.Context,
        enableFloatOutput: Boolean,
        enableAudioOutputPlaybackParams: Boolean,
    ): AudioSink? = null
}

/** The MP4 that starts [offset] bytes into [uri], played as if it were its own file. */
@OptIn(UnstableApi::class)
private fun embeddedVideo(context: android.content.Context, uri: Uri, offset: Long): MediaSource {
    val shifted =
        ResolvingDataSource.Factory(DefaultDataSource.Factory(context)) { spec ->
            spec.subrange(offset)
        }
    return ProgressiveMediaSource.Factory(shifted)
        .createMediaSource(MediaItem.Builder().setUri(uri).setMimeType(MimeTypes.VIDEO_MP4).build())
}
