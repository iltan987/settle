package com.iltan.settle.ui.settings

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.iltan.settle.R
import com.iltan.settle.appGraph
import com.iltan.settle.domain.deletion.ExternalDeletionPolicy
import kotlinx.coroutines.launch

/** Settings → Deletion, wired to the app (R13, ADR-015). */
@Composable
fun DeletionSettings() {
    val context = LocalContext.current
    val deletion = context.appGraph.deletion
    val scope = rememberCoroutineScope()
    val policy by
        deletion.policy.collectAsStateWithLifecycle(ExternalDeletionPolicy.MOVE_DRIVE_COPY_TO_TRASH)
    var silent by remember { mutableStateOf(deletion.canDeleteWithoutAsking()) }
    // The user comes back from Special app access with it changed.
    LifecycleResumeEffect(Unit) {
        silent = deletion.canDeleteWithoutAsking()
        onPauseOrDispose {}
    }
    DeletionSection(
        policy = policy,
        canDeleteWithoutAsking = silent,
        onPolicy = { scope.launch { deletion.setPolicy(it) } },
        onAllowDeletingWithoutAsking = {
            context.startActivity(
                Intent(
                    Settings.ACTION_REQUEST_MANAGE_MEDIA,
                    "package:${context.packageName}".toUri(),
                )
            )
        },
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun DeletionSection(
    policy: ExternalDeletionPolicy,
    canDeleteWithoutAsking: Boolean,
    onPolicy: (ExternalDeletionPolicy) -> Unit,
    onAllowDeletingWithoutAsking: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(stringResource(R.string.deletion_policy_title)) },
        supportingContent = { Text(stringResource(R.string.deletion_policy_text)) },
    )
    Column(Modifier.selectableGroup()) {
        listOf(
                ExternalDeletionPolicy.MOVE_DRIVE_COPY_TO_TRASH to R.string.deletion_policy_move,
                ExternalDeletionPolicy.ASK to R.string.deletion_policy_ask,
            )
            .forEach { (option, label) ->
                ListItem(
                    selected = option == policy,
                    onClick = { onPolicy(option) },
                    leadingContent = { RadioButton(selected = option == policy, onClick = null) },
                    content = { Text(stringResource(label)) },
                )
            }
    }
    if (!canDeleteWithoutAsking) {
        ListItem(
            onClick = onAllowDeletingWithoutAsking,
            leadingContent = { Icon(painterResource(R.drawable.ic_delete), null) },
            content = { Text(stringResource(R.string.manage_media_title)) },
            supportingContent = { Text(stringResource(R.string.manage_media_text)) },
        )
    }
}
