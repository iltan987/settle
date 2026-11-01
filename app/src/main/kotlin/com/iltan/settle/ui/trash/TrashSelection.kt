package com.iltan.settle.ui.trash

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.iltan.settle.R

// The trash's selection pieces from before the design pass; its own design (F) replaces them.

/** The trash's top bar while items are selected. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun SelectionTopBar(
    count: Int,
    onClear: () -> Unit,
    scrollBehavior: TopAppBarScrollBehavior,
    actions: @Composable () -> Unit,
) {
    BackHandler(onBack = onClear)
    LargeFlexibleTopAppBar(
        title = { Text(pluralStringResource(R.plurals.selected_count, count, count)) },
        navigationIcon = {
            IconButton(onClick = onClear) {
                Icon(
                    painterResource(R.drawable.ic_close),
                    contentDescription = stringResource(R.string.selection_clear),
                )
            }
        },
        actions = { actions() },
        colors =
            TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                scrolledContainerColor = MaterialTheme.colorScheme.secondaryContainer,
            ),
        scrollBehavior = scrollBehavior,
    )
}

/** An action in [SelectionTopBar]. */
@Composable
internal fun SelectionAction(icon: Int, label: Int, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(painterResource(icon), contentDescription = stringResource(label))
    }
}

/** A round check that shows whether an item is selected. */
@Composable
internal fun SelectionMark(selected: Boolean, label: Int, onPhoto: Boolean = false) {
    Icon(
        painterResource(
            if (selected) R.drawable.ic_check_circle_filled
            else R.drawable.ic_radio_button_unchecked
        ),
        contentDescription = stringResource(label),
        tint =
            when {
                selected -> MaterialTheme.colorScheme.primary
                onPhoto -> Color.White
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
        modifier =
            if (onPhoto && selected) {
                Modifier.size(24.dp).background(MaterialTheme.colorScheme.onPrimary, CircleShape)
            } else Modifier.size(24.dp),
    )
}
