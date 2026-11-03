package com.iltan.settle.ui.grid

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.iltan.settle.R
import com.iltan.settle.data.db.MediaEntry
import com.iltan.settle.data.thumbnail
import com.iltan.settle.domain.ItemStatus
import com.iltan.settle.domain.scan.MediaKind
import com.iltan.settle.ui.sharedMedia
import com.iltan.settle.ui.theme.SettleMotion
import com.iltan.settle.ui.theme.SettleShape
import com.iltan.settle.ui.theme.SettleType
import com.iltan.settle.ui.theme.settleColors

/** MediaStore's `_special_format` value for motion photos (API 37). */
const val SPECIAL_FORMAT_MOTION_PHOTO = 2

/** What a cell shows about backup: the status, and how far an upload is. */
@Immutable
data class CellBackup(
    val status: ItemStatus,
    val progress: Float? = null,
    /** Sent, and Drive is finishing the file: the ring turns instead of sitting full. */
    val checking: Boolean = false,
)

/**
 * One photo or video in a grid (GridCell). Each marker has its own corner so they never collide:
 * the selection check top-left, the favourite top-right, a video's duration or the motion mark
 * bottom-left, and the backup status bottom-right. Soft gradients sit only behind corners that have
 * a marker.
 */
@Composable
fun GridCell(
    entry: MediaEntry,
    /** Null when nothing is being selected. */
    selected: Boolean?,
    /** The status to badge, already filtered by where it may show (B5); null for none. */
    backup: CellBackup?,
    showFavorite: Boolean,
    description: String,
    /** Only for accessibility services; the grid handles touches itself (see gridGestures). */
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
) {
    val isSelected = selected == true
    // The spring overshoots a little on the way back; padding can't go below zero.
    val inset by animateDpAsState(if (isSelected) 8.dp else 0.dp, SettleMotion.select())
    val shownInset = inset.coerceAtLeast(0.dp)
    val favorite = showFavorite && entry.isFavorite
    val video = entry.kind == MediaKind.VIDEO
    val motion = !video && entry.specialFormat == SPECIAL_FORMAT_MOTION_PHOTO
    val context = LocalContext.current
    val request =
        remember(entry.thumbnail) {
            ImageRequest.Builder(context)
                .data(entry.thumbnail)
                .crossfade(DECODE_FADE_MILLIS)
                .build()
        }
    Box(
        Modifier.aspectRatio(1f)
            .clip(SettleShape.Cell)
            .background(
                if (isSelected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceContainerHigh
            )
            .semantics {
                contentDescription = description
                if (selected != null) this.selected = isSelected
                onClick { onClick().let { true } }
                if (onLongClick != null) onLongClick { onLongClick().let { true } }
            }
            .testTag("media-${entry.id}")
    ) {
        AsyncImage(
            model = request,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier =
                Modifier.fillMaxSize()
                    .padding(shownInset)
                    .sharedMedia(entry.id)
                    .clip(RoundedCornerShape(shownInset * 0.75f)),
        )
        if (favorite || selected != null) Scrim(top = true)
        if (video || motion) Scrim(top = false)
        when {
            video ->
                Text(
                    formatDuration(entry.durationMs),
                    style = SettleType.micro.copy(shadow = MarkerShadow),
                    color = Color.White,
                    modifier = Modifier.align(Alignment.BottomStart).padding(6.dp),
                )
            motion ->
                Icon(
                    painterResource(R.drawable.ic_motion_photo),
                    contentDescription = null,
                    tint = Color.White,
                    modifier =
                        Modifier.align(Alignment.BottomStart)
                            .padding(start = 5.dp, bottom = 4.dp)
                            .size(15.dp),
                )
        }
        if (favorite) {
            Icon(
                painterResource(R.drawable.ic_favorite_filled),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.align(Alignment.TopEnd).padding(5.dp).size(15.dp),
            )
        }
        StatusMarker(backup)
        SelectionMarker(selected)
    }
}

@Composable
private fun BoxScope.Scrim(top: Boolean) {
    val shade = Color.Black.copy(alpha = if (top) 0.32f else 0.36f)
    Box(
        Modifier.align(if (top) Alignment.TopCenter else Alignment.BottomCenter)
            .fillMaxWidth()
            .fillMaxHeight(0.45f)
            .background(
                Brush.verticalGradient(
                    if (top) listOf(shade, Color.Transparent) else listOf(Color.Transparent, shade)
                )
            )
    )
}

@Composable
private fun BoxScope.StatusMarker(backup: CellBackup?) {
    val status = backup?.status ?: return
    val badge = MaterialTheme.settleColors.photoBadge
    val colors = MaterialTheme.colorScheme
    when (status) {
        ItemStatus.NEEDS_ATTENTION -> {
            Box(Modifier.matchParentSize().border(2.dp, colors.error, SettleShape.Cell))
            Disc(colors.error, colors.onError, R.drawable.ic_priority_high, glyph = 13)
        }
        ItemStatus.UPLOADING -> UploadRing(backup.progress ?: 0f, backup.checking)
        ItemStatus.WAITING -> Disc(badge, Color.White, R.drawable.ic_schedule)
        ItemStatus.QUEUED -> Disc(badge, Color.White, R.drawable.ic_cloud_upload)
        ItemStatus.NOT_BACKED_UP -> Disc(badge, Color.White, R.drawable.ic_cloud_off)
        ItemStatus.CLOUD_ONLY -> Disc(badge, Color.White, R.drawable.ic_cloud)
        ItemStatus.BACKED_UP,
        ItemStatus.IN_TRASH -> {}
    }
}

/** An 18 dp disc in the bottom-right corner with a 12 dp glyph. */
@Composable
private fun BoxScope.Disc(color: Color, tint: Color, icon: Int, glyph: Int = 12) {
    Box(
        Modifier.align(Alignment.BottomEnd)
            .padding(4.dp)
            .size(18.dp)
            .background(color, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painterResource(icon),
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(glyph.dp),
        )
    }
}

/**
 * A 20 dp ring that fills as the upload goes, around an arrow; while Drive finishes the file
 * ([checking]) a quarter of it turns instead.
 */
@Composable
private fun BoxScope.UploadRing(progress: Float, checking: Boolean) {
    val turn =
        if (checking) {
            rememberInfiniteTransition(label = "checking")
                .animateFloat(
                    0f,
                    360f,
                    infiniteRepeatable(tween(CHECKING_TURN_MILLIS, easing = LinearEasing)),
                    label = "turn",
                )
        } else null
    Box(
        Modifier.align(Alignment.BottomEnd).padding(3.dp).size(20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.matchParentSize()) {
            drawCircle(Color.White.copy(alpha = 0.3f))
            if (turn != null) {
                drawArc(Color.White, turn.value - 90f, 90f, useCenter = true, topLeft = Offset.Zero)
            } else {
                drawArc(
                    Color.White,
                    -90f,
                    360f * progress.coerceIn(0f, 1f),
                    useCenter = true,
                    topLeft = Offset.Zero,
                )
            }
        }
        Box(
            Modifier.size(15.dp).background(Color.Black.copy(alpha = 0.6f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painterResource(R.drawable.ic_arrow_upward),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(11.dp),
            )
        }
    }
}

/** The empty ring every cell gets while selecting, and the check once chosen (C1). */
@Composable
internal fun BoxScope.SelectionMarker(selected: Boolean?) {
    AnimatedVisibility(
        visible = selected == false,
        enter = fadeIn(SettleMotion.fade()),
        exit = fadeOut(SettleMotion.fade()),
        modifier = Modifier.align(Alignment.TopStart).padding(6.dp),
    ) {
        Box(
            Modifier.size(20.dp)
                .background(Color.Black.copy(alpha = 0.18f), CircleShape)
                .border(2.dp, Color.White, CircleShape)
        )
    }
    AnimatedVisibility(
        visible = selected == true,
        enter = fadeIn(SettleMotion.fade()),
        exit = fadeOut(SettleMotion.fade()),
        // A 24 dp check 3 dp from the corner, with a 2 dp ring around it.
        modifier = Modifier.align(Alignment.TopStart).padding(1.dp),
    ) {
        val colors = MaterialTheme.colorScheme
        Box(
            Modifier.size(28.dp)
                .background(colors.primaryContainer, CircleShape)
                .padding(2.dp)
                .background(colors.primary, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painterResource(R.drawable.ic_check),
                contentDescription = null,
                tint = colors.onPrimary,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

private val MarkerShadow = Shadow(Color.Black.copy(alpha = 0.6f), Offset(0f, 2f), blurRadius = 4f)

private const val DECODE_FADE_MILLIS = 120

/** `0:07`, `12:34` or `1:02:03`. */
fun formatDuration(millis: Long): String {
    val total = millis / 1000
    val hours = total / 3600
    val minutes = total % 3600 / 60
    val seconds = total % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
    else "%d:%02d".format(minutes, seconds)
}

/** One turn of the checking ring. */
private const val CHECKING_TURN_MILLIS = 1_000
