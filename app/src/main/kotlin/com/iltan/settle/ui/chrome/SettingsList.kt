package com.iltan.settle.ui.chrome

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.iltan.settle.R
import com.iltan.settle.ui.theme.SettleShape
import com.iltan.settle.ui.theme.SettleType
import com.iltan.settle.ui.theme.cardColor
import com.iltan.settle.ui.theme.settleColors

/** How a row's one-shot button looks: filled, tonal, outlined, or outlined in error. */
enum class RowButtonStyle {
    FILLED,
    TONAL,
    OUTLINE,
    DANGER,
}

/** The one control at a row's end (SettingsList). */
@Immutable
sealed interface Trailing {
    /** Opens a sub-page or a sheet. */
    data object Chevron : Trailing

    /** Leaves Settle, for a web page or Drive. */
    data object External : Trailing

    /** A setup step that was just completed: it stays in place, checked (A7). */
    data object Done : Trailing

    /** The current value, then a chevron. */
    data class Value(val text: String) : Trailing

    /** The whole row toggles it. */
    data class Toggle(val checked: Boolean) : Trailing

    /** A one-shot action; the row itself isn't clickable. */
    data class Action(
        val label: String,
        val style: RowButtonStyle,
        val onClick: () -> Unit,
        val enabled: Boolean = true,
    ) : Trailing
}

/** Where a row's icon takes its colour from: problems use warn or error, never colour alone. */
enum class RowTone {
    PLAIN,
    PRIMARY,
    WARN,
    ERR,
}

@Composable
private fun RowTone.color(): Color =
    when (this) {
        RowTone.PLAIN -> MaterialTheme.colorScheme.onSurfaceVariant
        RowTone.PRIMARY -> MaterialTheme.colorScheme.primary
        RowTone.WARN -> MaterialTheme.settleColors.warning
        RowTone.ERR -> MaterialTheme.colorScheme.error
    }

/** The rows of one group; each is drawn with a 1 dp line above all but the first. */
class SettingsGroupScope internal constructor() {
    internal val rows = mutableListOf<@Composable () -> Unit>()

    fun row(content: @Composable () -> Unit) {
        rows += content
    }
}

/**
 * One group of a settings page (SettingsList): a primary title, an optional lead, an optional
 * notice card, then rows on a card (radius 20) and an optional note. A group with nothing to show
 * isn't drawn at all: rows that aren't needed disappear rather than turning grey.
 */
@Composable
fun SettingsGroup(
    modifier: Modifier = Modifier,
    title: String? = null,
    lead: String? = null,
    note: String? = null,
    container: Color = MaterialTheme.cardColor,
    notice: (@Composable () -> Unit)? = null,
    content: SettingsGroupScope.() -> Unit,
) {
    val rows = SettingsGroupScope().apply(content).rows
    if (rows.isEmpty() && notice == null) return
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (title != null) {
            Text(
                title,
                style = SettleType.groupTitle,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 8.dp).semantics { heading() },
            )
        }
        if (lead != null) {
            Text(
                lead,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
        notice?.invoke()
        if (rows.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().clip(SettleShape.Card).background(container)) {
                rows.forEachIndexed { index, row ->
                    if (index > 0) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                    row()
                }
            }
        }
        if (note != null) {
            Text(
                note,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = null),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
    }
}

/** A settings page's groups: 12 dp from the sides, 20 dp apart, scrolling under its bar. */
@Composable
fun SettingsColumn(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 24.dp)
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        content()
    }
}

/**
 * One row (SettingsRow): at least 56 dp, a 24 dp icon or a [leading] control, a title, a sub that
 * carries the current value and wraps rather than truncating, an optional [meter], and one
 * [trailing] control. A [Trailing.Toggle] row toggles as a whole; an [Trailing.Action] row is not
 * clickable itself.
 */
@Composable
fun SettingsRow(
    title: String,
    modifier: Modifier = Modifier,
    sub: String? = null,
    icon: Int? = null,
    tone: RowTone = RowTone.PLAIN,
    subTone: RowTone = RowTone.PLAIN,
    /** Disconnect: the title in error. */
    danger: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
    /** A share from 0 to 1, drawn as an 8 dp bar that always shows at least 2%. */
    meter: Float? = null,
    trailing: Trailing? = null,
    onClick: (() -> Unit)? = null,
) {
    val clickable =
        when {
            trailing is Trailing.Toggle && onClick != null ->
                Modifier.toggleable(
                    value = trailing.checked,
                    role = Role.Switch,
                    onValueChange = { onClick() },
                )
            trailing is Trailing.Action || onClick == null -> Modifier
            else -> Modifier.clickable(onClick = onClick)
        }
    Row(
        modifier
            .fillMaxWidth()
            .then(clickable)
            .heightIn(min = 56.dp)
            .padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (leading != null) leading()
        if (icon != null) {
            Icon(
                painterResource(icon),
                contentDescription = null,
                tint = tone.color(),
                modifier = Modifier.size(24.dp),
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                title,
                style = SettleType.rowTitle,
                color =
                    if (danger) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurface,
            )
            if (sub != null) {
                Text(
                    sub,
                    style = MaterialTheme.typography.bodySmall,
                    color =
                        if (subTone == RowTone.PLAIN) MaterialTheme.colorScheme.onSurfaceVariant
                        else subTone.color(),
                )
            }
            if (meter != null) {
                LinearProgressIndicator(
                    progress = { meter.coerceIn(0.02f, 1f) },
                    modifier = Modifier.padding(top = 6.dp).fillMaxWidth().height(8.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    strokeCap = StrokeCap.Round,
                    gapSize = 0.dp,
                    drawStopIndicator = {},
                )
            }
        }
        when (trailing) {
            null -> {}
            Trailing.Chevron -> Chevron()
            Trailing.Done ->
                Icon(
                    painterResource(R.drawable.ic_check_circle_filled),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
            Trailing.External ->
                Icon(
                    painterResource(R.drawable.ic_open_in_new),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            is Trailing.Value -> {
                Text(
                    trailing.text,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = null),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.End,
                    modifier = Modifier.widthIn(max = 160.dp),
                )
                Chevron()
            }
            is Trailing.Toggle ->
                Switch(
                    checked = trailing.checked,
                    onCheckedChange = null,
                    thumbContent =
                        if (trailing.checked) {
                            {
                                Icon(
                                    painterResource(R.drawable.ic_check),
                                    contentDescription = null,
                                    modifier = Modifier.size(SwitchDefaults.IconSize),
                                )
                            }
                        } else null,
                )
            is Trailing.Action -> RowButton(trailing)
        }
    }
}

@Composable
private fun Chevron() {
    Icon(
        painterResource(R.drawable.ic_chevron_right),
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(24.dp),
    )
}

/** A 40 dp button with a 48 dp touch target. */
@Composable
private fun RowButton(action: Trailing.Action) {
    val colors = MaterialTheme.colorScheme
    when (action.style) {
        RowButtonStyle.FILLED,
        RowButtonStyle.TONAL ->
            Button(
                onClick = action.onClick,
                enabled = action.enabled,
                shape = SettleShape.Pill,
                colors =
                    if (action.style == RowButtonStyle.TONAL) {
                        ButtonDefaults.buttonColors(
                            containerColor = colors.primaryContainer,
                            contentColor = colors.onPrimaryContainer,
                        )
                    } else ButtonDefaults.buttonColors(),
                contentPadding = ButtonDefaults.ContentPadding,
            ) {
                Text(action.label, style = MaterialTheme.typography.labelLarge)
            }
        RowButtonStyle.OUTLINE,
        RowButtonStyle.DANGER -> {
            val color =
                if (action.style == RowButtonStyle.DANGER) colors.error else colors.onSurface
            OutlinedButton(
                onClick = action.onClick,
                enabled = action.enabled,
                shape = SettleShape.Pill,
                border =
                    BorderStroke(
                        1.dp,
                        if (action.style == RowButtonStyle.DANGER) colors.error
                        else colors.onSurfaceVariant,
                    ),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = color),
            ) {
                Text(action.label, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/** A choice among a few (radio on the leading side); choosing applies it. */
@Composable
fun SettingsRadioRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    sub: String? = null,
) {
    SettingsRow(
        title = title,
        sub = sub,
        modifier =
            modifier.selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        leading = {
            Icon(
                painterResource(
                    if (selected) R.drawable.ic_radio_button_checked
                    else R.drawable.ic_radio_button_unchecked
                ),
                contentDescription = null,
                tint =
                    if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
        },
    )
}

/** The connected account's initial on a 40 dp primary-container disc. */
@Composable
fun AccountInitial(email: String, modifier: Modifier = Modifier) {
    Box(
        modifier.size(40.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            email.firstOrNull()?.uppercase().orEmpty(),
            style = MaterialTheme.typography.titleSmall.copy(fontSize = 17.sp),
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

/**
 * A modal choice among a few options (ChoiceSheet, I7, I8): a title, a one-line lead, and radio
 * rows on a surface-container group. Picking a row applies it and closes the sheet; there is no OK.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> ChoiceSheet(
    title: String,
    lead: String?,
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onChoose: (T) -> Unit,
    onDismiss: () -> Unit,
    sub: @Composable (T) -> String? = { null },
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = SettleShape.Sheet,
        containerColor = MaterialTheme.cardColor,
        scrimColor = MaterialTheme.settleColors.scrim,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            modifier =
                Modifier.padding(horizontal = 24.dp).padding(bottom = 4.dp).semantics { heading() },
        )
        if (lead != null) {
            Text(
                lead,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 8.dp),
            )
        }
        Column(Modifier.verticalScroll(rememberScrollState())) {
            SettingsGroup(
                modifier =
                    Modifier.padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 20.dp)
                        .selectableGroup(),
                container = MaterialTheme.colorScheme.surfaceContainer,
            ) {
                options.forEach { option ->
                    row {
                        SettingsRadioRow(
                            title = label(option),
                            sub = sub(option),
                            selected = option == selected,
                            onClick = {
                                onChoose(option)
                                onDismiss()
                            },
                        )
                    }
                }
            }
            Spacer(Modifier.navigationBarsPadding())
        }
    }
}
