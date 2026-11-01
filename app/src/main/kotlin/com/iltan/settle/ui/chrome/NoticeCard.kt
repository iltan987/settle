package com.iltan.settle.ui.chrome

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.iltan.settle.R
import com.iltan.settle.ui.theme.SettleShape
import com.iltan.settle.ui.theme.cardColor
import com.iltan.settle.ui.theme.settleColors

/** A button on a notice: its label and what it does. */
@Immutable data class NoticeAction(val label: String, val onClick: () -> Unit)

/** What a notice says (NoticeCard); the same content shows in the status popover. */
@Immutable
data class NoticeContent(
    val tone: Tone,
    val icon: Int,
    val title: String,
    val body: String,
    val primary: NoticeAction? = null,
    /** Text buttons before [primary], in order. */
    val secondary: List<NoticeAction> = emptyList(),
)

private data class NoticeColors(
    val container: Color,
    val content: Color,
    val button: Color,
    val onButton: Color,
)

@Composable
private fun noticeColors(tone: Tone): NoticeColors {
    val colors = MaterialTheme.colorScheme
    val settle = MaterialTheme.settleColors
    return when (tone) {
        Tone.WARN ->
            NoticeColors(
                settle.warningContainer,
                settle.onWarningContainer,
                settle.onWarningContainer,
                settle.warningContainer,
            )
        Tone.ERR ->
            NoticeColors(
                colors.errorContainer,
                colors.onErrorContainer,
                colors.onErrorContainer,
                colors.errorContainer,
            )
        else ->
            NoticeColors(
                colors.surfaceContainer,
                colors.onSurface,
                colors.primary,
                colors.onPrimary,
            )
    }
}

/**
 * A card at the top of a tab's content that scrolls with it (NoticeCard): radius 24, 10 dp from the
 * sides. With [onDismiss] it has a close button; the problem then lives on in the status chip.
 */
@Composable
fun NoticeCard(
    content: NoticeContent,
    modifier: Modifier = Modifier,
    onDismiss: (() -> Unit)? = null,
) {
    val colors = noticeColors(content.tone)
    Surface(
        shape = SettleShape.Notice,
        color = colors.container,
        contentColor = colors.content,
        modifier =
            modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, top = 4.dp, bottom = 8.dp),
    ) {
        NoticeBody(
            content,
            colors,
            onDismiss,
            PaddingValues(start = 16.dp, top = 16.dp, end = 4.dp, bottom = 8.dp),
        )
    }
}

/**
 * The status popover (B8): the problem behind a collapsed notice, with its fix, opened from the
 * chip. Radius 20 on the card surface with a level-3 shadow.
 */
@Composable
fun StatusPopoverCard(content: NoticeContent, modifier: Modifier = Modifier) {
    val colors = noticeColors(Tone.INFO).copy(container = MaterialTheme.cardColor)
    Surface(
        shape = SettleShape.Card,
        color = colors.container,
        contentColor = colors.content,
        shadowElevation = 6.dp,
        modifier = modifier.width(300.dp),
    ) {
        NoticeBody(
            content,
            colors,
            onDismiss = null,
            PaddingValues(start = 14.dp, top = 14.dp, end = 4.dp, bottom = 6.dp),
            iconTint = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun NoticeBody(
    content: NoticeContent,
    colors: NoticeColors,
    onDismiss: (() -> Unit)?,
    padding: PaddingValues,
    iconTint: Color = LocalContentColor.current,
    shape: Shape = SettleShape.Pill,
    buttonHeight: Dp = 40.dp,
) {
    Column(Modifier.padding(padding), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(
                painterResource(content.icon),
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(24.dp),
            )
            Column(
                Modifier.weight(1f).padding(top = 2.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(content.title, style = MaterialTheme.typography.titleSmall)
                Text(content.body, style = MaterialTheme.typography.bodyMedium)
            }
            if (onDismiss != null) {
                IconButton(onClick = onDismiss, modifier = Modifier.offset(y = (-12).dp)) {
                    Icon(
                        painterResource(R.drawable.ic_close),
                        contentDescription = stringResource(R.string.notice_dismiss),
                        modifier = Modifier.size(22.dp),
                    )
                }
            } else {
                Spacer(Modifier.width(12.dp))
            }
        }
        if (content.primary != null || content.secondary.isNotEmpty()) {
            FlowRow(
                Modifier.fillMaxWidth().padding(end = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                content.secondary.forEach { action ->
                    TextButton(
                        onClick = action.onClick,
                        colors = ButtonDefaults.textButtonColors(contentColor = colors.button),
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text(action.label, style = MaterialTheme.typography.labelLarge)
                    }
                }
                content.primary?.let { action ->
                    Button(
                        onClick = action.onClick,
                        shape = shape,
                        colors =
                            ButtonDefaults.buttonColors(
                                containerColor = colors.button,
                                contentColor = colors.onButton,
                            ),
                        contentPadding = PaddingValues(horizontal = 18.dp),
                        modifier = Modifier.heightIn(min = buttonHeight),
                    ) {
                        Text(action.label, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}
