package com.iltan.settle.ui.viewer

import android.content.Context
import android.media.AudioManager
import android.net.Uri
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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
import androidx.media3.ui.compose.material3.buttons.MuteButton
import androidx.media3.ui.compose.material3.buttons.PlayPauseButton
import androidx.media3.ui.compose.material3.indicator.DurationText
import androidx.media3.ui.compose.material3.indicator.PositionText
import androidx.media3.ui.compose.material3.indicator.ProgressSlider
import com.iltan.settle.ui.theme.SettleMotion
import com.iltan.settle.ui.theme.SettleShape
import com.iltan.settle.ui.theme.settleColors

// Media3's Compose UI is still marked unstable and experimental; its use is kept to this file.

/**
 * Plays [uri] with Settle's video controls (VideoControls), shown while [showControls] is true, as
 * long as [play] is true: a 64 dp play/pause in the centre, and a bar with the time, the scrubber
 * and mute [controlsBottom] above the bottom, where the status chip otherwise sits. Sound starts
 * muted when the phone is on silent or vibrate. [onPlayingChanged] follows play and pause.
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
    controlsBottom: Dp = 0.dp,
    onEnded: () -> Unit = {},
    onPlayingChanged: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    val currentOnEnded = rememberUpdatedState(onEnded)
    val currentOnPlaying = rememberUpdatedState(onPlayingChanged)
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
                    if (!motionPhoto && phoneIsSilent(context)) volume = 0f
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

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    currentOnPlaying.value(isPlaying)
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
    val stage = MaterialTheme.settleColors
    Player(
        player = player,
        modifier = modifier.fillMaxSize().alpha(if (visible) 1f else 0f),
        surfaceType = if (motionPhoto) SURFACE_TYPE_TEXTURE_VIEW else SURFACE_TYPE_SURFACE_VIEW,
        showControls = showControls,
        shutter = { if (!motionPhoto) Box(Modifier.fillMaxSize().background(stage.stage)) },
        topControls = null,
        centerControls = { p, shown ->
            AnimatedVisibility(
                visible = shown,
                enter = fadeIn(SettleMotion.fade()),
                exit = fadeOut(SettleMotion.fade()),
                modifier = Modifier.align(Alignment.Center),
            ) {
                PlayPauseButton(
                    p,
                    modifier = Modifier.size(64.dp).background(stage.stageChrome, CircleShape),
                    iconSize = 32.dp,
                    tint = stage.onStage,
                )
            }
        },
        bottomControls = { p, shown ->
            AnimatedVisibility(
                visible = shown,
                enter = fadeIn(SettleMotion.fade()),
                exit = fadeOut(SettleMotion.fade()),
                modifier =
                    Modifier.align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(start = 16.dp, end = 16.dp, bottom = controlsBottom),
            ) {
                VideoBar(p)
            }
        },
    )
}

/** The 48 dp bar: position, scrubber, duration and mute, on #202020 at 92%. */
@OptIn(UnstableApi::class, ExperimentalApi::class)
@Composable
private fun VideoBar(player: Player?) {
    val stage = MaterialTheme.settleColors
    Surface(
        shape = SettleShape.Notice,
        color = stage.stageChrome,
        contentColor = stage.onStage,
        modifier = Modifier.widthIn(max = 600.dp).fillMaxWidth(),
    ) {
        Row(
            Modifier.height(48.dp).padding(start = 14.dp, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PositionText(player, color = stage.onStage)
            ProgressSlider(
                player,
                Modifier.weight(1f).padding(horizontal = 10.dp),
                colors =
                    SliderDefaults.colors(
                        thumbColor = stage.onStage,
                        activeTrackColor = stage.stageOk,
                        inactiveTrackColor = stage.onStage.copy(alpha = 0.3f),
                    ),
            )
            DurationText(player, color = stage.onStage)
            MuteButton(player, tint = stage.onStage)
        }
    }
}

/** Silent or vibrate: a video shouldn't suddenly play aloud (D3). */
private fun phoneIsSilent(context: Context): Boolean =
    context.getSystemService(AudioManager::class.java)?.ringerMode !=
        AudioManager.RINGER_MODE_NORMAL

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
