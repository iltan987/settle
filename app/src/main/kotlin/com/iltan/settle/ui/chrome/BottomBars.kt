package com.iltan.settle.ui.chrome

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.iltan.settle.ui.theme.SettleShape

/** The navigation bar's height above the gesture inset; the selection bar has the same. */
val BottomBarHeight = 80.dp

/**
 * Lets a tab hide the app's navigation bar while it shows its own bar in the same slot, as the
 * timeline does while selecting (C1).
 */
@Stable
class TabBarState {
    var hidden by mutableStateOf(false)
}

val LocalTabBar = staticCompositionLocalOf { TabBarState() }

/** Hides the app's navigation bar while [hidden] and this is shown. */
@Composable
fun HideTabBar(hidden: Boolean) {
    val bar = LocalTabBar.current
    DisposableEffect(hidden) {
        bar.hidden = hidden
        onDispose { bar.hidden = false }
    }
}

/** The surface both bottom bars sit on: 80 dp on c1, above the gesture inset. */
@Composable
fun BottomBarFrame(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    // A surface, so text and icons on it take the matching content colour in both themes.
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Box(Modifier.windowInsetsPadding(WindowInsets.navigationBars)) {
            Row(
                Modifier.fillMaxWidth().height(BottomBarHeight).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceAround,
                content = content,
            )
        }
    }
}

/** A tab in the navigation bar (NavBar): a 64×32 pill when active, and an optional 8 dp dot. */
@Composable
fun RowScope.NavItem(
    selected: Boolean,
    icon: Int,
    selectedIcon: Int,
    label: String,
    onClick: () -> Unit,
    dot: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier.weight(1f)
            .widthIn(min = 88.dp)
            .heightIn(min = 64.dp)
            .selectable(
                selected = selected,
                onClick = onClick,
                role = Role.Tab,
                interactionSource = null,
                indication = ripple(bounded = false, radius = 40.dp),
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
    ) {
        Box(
            Modifier.size(64.dp, 32.dp)
                .background(
                    if (selected) colors.primaryContainer else colors.surfaceContainer,
                    SettleShape.Pill,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painterResource(if (selected) selectedIcon else icon),
                contentDescription = null,
                tint = if (selected) colors.onPrimaryContainer else colors.onSurfaceVariant,
            )
            if (dot) {
                Box(
                    Modifier.align(Alignment.TopEnd)
                        .offset(x = (-16).dp, y = 3.dp)
                        .size(8.dp)
                        .border(2.dp, colors.surfaceContainer, CircleShape)
                        .padding(1.dp)
                        .background(colors.error, CircleShape)
                )
            }
        }
        Text(
            label,
            style =
                MaterialTheme.typography.labelSmall.copy(
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                ),
            color = if (selected) colors.onSurface else colors.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/** An action in the selection bar (SelectionBar): icon over label, with an optional count. */
@Composable
fun RowScope.BarAction(
    icon: Int,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    destructive: Boolean = false,
    badge: Int? = null,
    onLongClick: (() -> Unit)? = null,
) {
    val color = if (destructive) MaterialTheme.colorScheme.error else LocalContentColor.current
    Column(
        Modifier.weight(1f)
            .fillMaxHeight()
            .padding(vertical = 8.dp)
            // Still long-pressable while disabled, to say why ("Already in Drive").
            .combinedClickable(
                onClick = { if (enabled) onClick() },
                onLongClick = onLongClick,
                role = Role.Button,
            )
            .semantics { if (!enabled) disabled() }
            .alpha(if (enabled) 1f else 0.38f),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
    ) {
        Box(Modifier.size(56.dp, 32.dp), contentAlignment = Alignment.Center) {
            Icon(painterResource(icon), contentDescription = null, tint = color)
            if (badge != null && badge > 0) {
                CompositionLocalProvider(
                    LocalContentColor provides MaterialTheme.colorScheme.onPrimary
                ) {
                    Box(
                        Modifier.align(Alignment.TopEnd)
                            .offset(x = (-6).dp, y = (-2).dp)
                            .heightIn(min = 18.dp)
                            .widthIn(min = 18.dp)
                            .background(MaterialTheme.colorScheme.primary, CircleShape)
                            .padding(horizontal = 5.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            badge.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}
