package com.iltan.settle.ui.viewer

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.iltan.settle.R
import com.iltan.settle.ui.theme.SettleMotion
import com.iltan.settle.ui.theme.SettleShape
import com.iltan.settle.ui.theme.SettleType
import com.iltan.settle.ui.theme.settleColors
import java.text.NumberFormat
import kotlin.math.roundToInt

/** One entry in the viewer's overflow menu. */
class ViewerMenuItem(val label: Int, val onClick: () -> Unit)

/**
 * ViewerTopBar: back, the day and "time · place", and the overflow menu, over a scrim that fades
 * out 120 dp down so white text reads on bright photos.
 */
@Composable
fun ViewerTopBar(
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    menu: List<ViewerMenuItem>,
    modifier: Modifier = Modifier,
) {
    val stage = MaterialTheme.settleColors
    Box(
        modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(stage.stageScrim, Color.Transparent)))
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(bottom = SCRIM_BELOW_BAR)
    ) {
        Row(
            Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StageIconButton(R.drawable.ic_arrow_back, R.string.navigate_back, onBack)
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    title,
                    style = SettleType.viewerTitle,
                    color = stage.onStage,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    subtitle,
                    style = SettleType.viewerSubtitle,
                    color = stage.onStage.copy(alpha = 0.8f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            }
            if (menu.isEmpty()) {
                Box(Modifier.size(48.dp))
            } else {
                Box {
                    var open by remember { mutableStateOf(false) }
                    StageIconButton(R.drawable.ic_more_vert, R.string.viewer_more, { open = true })
                    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                        menu.forEach { item ->
                            DropdownMenuItem(
                                text = { Text(stringResource(item.label)) },
                                onClick = {
                                    open = false
                                    item.onClick()
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * The FloatingToolbar pill: #202020 at 92%, no blur, 56 dp tall with four 48 dp buttons 8 dp apart.
 * Drawn here rather than with Material's floating toolbar, which is at least 64 dp tall.
 */
@Composable
fun ViewerToolbar(
    favorite: Boolean,
    onShare: () -> Unit,
    onFavorite: () -> Unit,
    onDelete: () -> Unit,
    onDetails: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val stage = MaterialTheme.settleColors
    Surface(
        shape = SettleShape.Pill,
        color = stage.stageChrome,
        contentColor = stage.onStage,
        modifier = modifier.semantics { isTraversalGroup = true },
    ) {
        Row(
            Modifier.height(TOOLBAR_HEIGHT).padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StageIconButton(R.drawable.ic_share, R.string.viewer_share, onShare)
            // The heart pops when it fills (1.2×, spring).
            val pop = remember { Animatable(1f) }
            var wasFavorite by rememberSaveable { mutableStateOf(favorite) }
            LaunchedEffect(favorite) {
                if (favorite && !wasFavorite) {
                    pop.animateTo(1.2f, SettleMotion.pop())
                    pop.animateTo(1f, SettleMotion.pop())
                }
                wasFavorite = favorite
            }
            StageIconButton(
                if (favorite) R.drawable.ic_favorite_filled else R.drawable.ic_favorite,
                if (favorite) R.string.viewer_unfavorite else R.string.viewer_favorite,
                onFavorite,
                Modifier.graphicsLayer {
                    scaleX = pop.value
                    scaleY = pop.value
                },
            )
            StageIconButton(R.drawable.ic_delete, R.string.action_delete, onDelete)
            StageIconButton(R.drawable.ic_info, R.string.viewer_info, onDetails)
        }
    }
}

/** A white 48 dp icon button on the stage whose label shows as a tooltip on long-press. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StageIconButton(
    icon: Int,
    label: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val text = stringResource(label)
    TooltipBox(
        positionProvider =
            TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text(text) } },
        state = rememberTooltipState(),
    ) {
        IconButton(onClick = onClick, modifier = modifier) {
            Icon(painterResource(icon), text, tint = MaterialTheme.settleColors.onStage)
        }
    }
}

/** ZoomPill: "2.5×" while zooming; TalkBack hears "Zoomed to 250%". */
@Composable
fun ZoomPill(zoom: Float, modifier: Modifier = Modifier) {
    val stage = MaterialTheme.settleColors
    val percent = (zoom * 100).roundToInt()
    val spoken = stringResource(R.string.viewer_zoomed_to, percent)
    Surface(
        shape = SettleShape.Pill,
        color = stage.stageChrome,
        contentColor = stage.onStage,
        modifier =
            modifier.semantics {
                contentDescription = spoken
                liveRegion = LiveRegionMode.Polite
            },
    ) {
        val locale = LocalConfiguration.current.locales[0]
        val factor =
            remember(locale) {
                NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 1 }
            }
        Text(
            factor.format(zoom) + "×",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

/** "Play motion": a light 40 dp pill that plays a motion photo's clip. */
@Composable
fun PlayMotionPill(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val stage = MaterialTheme.settleColors
    Surface(
        onClick = onClick,
        shape = SettleShape.Pill,
        color = stage.onStage,
        contentColor = Color.Black,
        modifier = modifier,
    ) {
        Row(
            Modifier.height(40.dp).padding(start = 12.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                painterResource(R.drawable.ic_motion_photo),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Text(
                stringResource(R.string.viewer_play_motion),
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

val TOOLBAR_HEIGHT = 56.dp

/** The scrim runs on 120 dp below the status bar, past the bar's 64 dp. */
private val SCRIM_BELOW_BAR = 56.dp
