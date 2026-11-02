package com.iltan.settle.ui.chrome

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.iltan.settle.ui.theme.SettleShape
import com.iltan.settle.ui.theme.settleColors

/** The header's one button. */
@Immutable data class HeaderAction(val label: String, val icon: Int?, val onClick: () -> Unit)

/** A run's bar: how far, with speed on the left and time left on the right. */
@Immutable data class HeaderProgress(val fraction: Float, val label: String?, val meta: String?)

/** What the Backup tab's header says (BackupStatusHeader, J8). */
@Immutable
data class HeaderContent(
    val tone: Tone,
    val icon: Int,
    val title: String,
    val body: String?,
    val progress: HeaderProgress? = null,
    val action: HeaderAction? = null,
)

private data class HeaderColors(
    val container: Color,
    val content: Color,
    val tile: Color,
    val onTile: Color,
    val track: Color,
)

@Composable
private fun headerColors(tone: Tone): HeaderColors {
    val colors = MaterialTheme.colorScheme
    val settle = MaterialTheme.settleColors
    return when (tone) {
        Tone.WARN ->
            HeaderColors(
                settle.warningContainer,
                settle.onWarningContainer,
                settle.onWarningContainer,
                settle.warningContainer,
                settle.onWarningContainer.copy(alpha = 0.2f),
            )
        Tone.ERR ->
            HeaderColors(
                colors.errorContainer,
                colors.onErrorContainer,
                colors.onErrorContainer,
                colors.errorContainer,
                colors.onErrorContainer.copy(alpha = 0.2f),
            )
        // Drive not connected: nothing is wrong, nothing is on.
        Tone.INFO ->
            HeaderColors(
                colors.surfaceContainer,
                colors.onSurface,
                colors.onSurfaceVariant,
                colors.surfaceContainer,
                colors.surfaceContainerHighest,
            )
        else ->
            HeaderColors(
                colors.surfaceContainer,
                colors.onSurface,
                colors.primary,
                colors.onPrimary,
                colors.surfaceContainerHighest,
            )
    }
}

/**
 * The Backup tab's top card (BackupStatusHeader): radius 28, a 44 dp icon tile, a one-sentence
 * title, an optional body and 8 dp progress bar, and at most one action. Its tone matches the chip
 * and the notice cards.
 */
@Composable
fun BackupStatusHeader(content: HeaderContent, modifier: Modifier = Modifier) {
    val colors = headerColors(content.tone)
    Surface(
        shape = SettleShape.Large,
        color = colors.container,
        contentColor = colors.content,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.padding(start = 20.dp, top = 20.dp, end = 12.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                Modifier.padding(end = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Box(
                    Modifier.size(44.dp).background(colors.tile, SettleShape.IconTile),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painterResource(content.icon),
                        contentDescription = null,
                        tint = colors.onTile,
                        modifier = Modifier.size(26.dp),
                    )
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        content.title,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.semantics { heading() },
                    )
                    if (content.body != null) {
                        Text(content.body, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            content.progress?.let { progress ->
                Column(
                    Modifier.padding(end = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    LinearProgressIndicator(
                        progress = { progress.fraction },
                        modifier = Modifier.fillMaxWidth().height(8.dp),
                        color = colors.tile,
                        trackColor = colors.track,
                        strokeCap = StrokeCap.Round,
                        gapSize = 0.dp,
                        drawStopIndicator = {},
                    )
                    // Speed and time left wrap at large font sizes rather than overlap.
                    FlowRow(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        progress.label?.let {
                            Text(
                                it,
                                style =
                                    MaterialTheme.typography.labelMedium.copy(fontWeight = null),
                            )
                        }
                        progress.meta?.let {
                            Text(
                                it,
                                style =
                                    MaterialTheme.typography.labelMedium.copy(fontWeight = null),
                            )
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                val action = content.action
                if (action == null) {
                    Spacer(Modifier.height(4.dp))
                } else {
                    Button(
                        onClick = action.onClick,
                        shape = SettleShape.Pill,
                        colors =
                            ButtonDefaults.buttonColors(
                                containerColor = colors.tile,
                                contentColor = colors.onTile,
                            ),
                        contentPadding = PaddingValues(horizontal = 18.dp),
                        modifier = Modifier.padding(vertical = 4.dp).heightIn(min = 40.dp),
                    ) {
                        if (action.icon != null) {
                            Icon(
                                painterResource(action.icon),
                                contentDescription = null,
                                modifier = Modifier.padding(end = 6.dp).size(18.dp),
                            )
                        }
                        Text(action.label, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}
