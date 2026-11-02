package com.iltan.settle.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.iltan.settle.R
import com.iltan.settle.data.system.SystemState
import com.iltan.settle.ui.chrome.SettingsGroup
import com.iltan.settle.ui.chrome.StepList
import com.iltan.settle.ui.settings.KeepRunningActions
import com.iltan.settle.ui.settings.NeverSleepingGuide
import com.iltan.settle.ui.settings.SetupStep
import com.iltan.settle.ui.settings.SetupStepRow
import com.iltan.settle.ui.settings.openSetupSteps
import com.iltan.settle.ui.theme.SettleType

/** Welcome (A1): what Settle is, and the one thing it can't work without. There's no skip. */
@Composable
fun WelcomeScreen(onAllow: () -> Unit) {
    OnboardingFrame(
        bottom = { PrimaryButton(stringResource(R.string.welcome_allow), onAllow) },
        sides = 0.dp,
    ) {
        BrandArt(Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp))
        Column(
            Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.app_name),
                style =
                    MaterialTheme.typography.labelLarge.copy(
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                stringResource(R.string.welcome_title),
                style = MaterialTheme.typography.headlineLarge,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                stringResource(R.string.welcome_body),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Only some photos allowed (A3). Settle's library follows MediaStore only with full access
 * (ADR-012), so instead of a partial timeline this asks for all of them.
 */
@Composable
fun AccessPartialScreen(onAllowAll: () -> Unit, onOpenSettings: () -> Unit) {
    OnboardingFrame(
        bottom = {
            PrimaryButton(stringResource(R.string.access_allow_all), onAllowAll)
            QuietButton(stringResource(R.string.access_open_settings), onOpenSettings)
        }
    ) {
        Header(
            R.drawable.ic_photo_library,
            stringResource(R.string.access_partial_title),
            stringResource(R.string.access_partial_body),
        )
    }
}

/**
 * Access blocked (A4): after a denial, or whenever access is taken away later, in the timeline's
 * place. The steps repeat Android's wording; coming back from settings checks again on its own.
 */
@Composable
fun AccessBlockedScreen(onOpenSettings: () -> Unit) {
    OnboardingFrame(
        bottom = {
            PrimaryButton(
                stringResource(R.string.access_open_settings),
                onOpenSettings,
                external = true,
            )
        }
    ) {
        Header(
            R.drawable.ic_lock,
            stringResource(R.string.access_blocked_title),
            stringResource(R.string.access_blocked_body),
            neutral = true,
            top = 86.dp,
        )
        StepList(
            listOf(
                stringResource(R.string.access_step_permissions),
                stringResource(R.string.access_step_photos),
                stringResource(R.string.access_allow_all),
            ),
            Modifier.padding(top = 6.dp),
        )
    }
}

/**
 * Connect Google Drive (A5): the promise in three facts before Google's own screens (A6). Backing
 * out of Google's screens returns here without a word; a refusal says what Settle needs.
 */
@Composable
fun ConnectDriveScreen(
    connecting: Boolean,
    problem: String?,
    onConnect: () -> Unit,
    onNotNow: () -> Unit,
) {
    OnboardingFrame(
        bottom = {
            if (problem != null) {
                Text(
                    problem,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                )
            }
            if (connecting) {
                Button(
                    onClick = {},
                    enabled = false,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                ) {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                    Text(
                        stringResource(R.string.connecting),
                        modifier = Modifier.padding(start = 10.dp),
                    )
                }
            } else {
                PrimaryButton(stringResource(R.string.drive_connect), onConnect)
            }
            QuietButton(stringResource(R.string.action_not_now), onNotNow, enabled = !connecting)
        }
    ) {
        Header(
            R.drawable.ic_add_to_drive,
            stringResource(R.string.connect_title),
            stringResource(R.string.connect_body),
        )
        Column(
            Modifier.padding(top = 10.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Promise(
                R.drawable.ic_schedule,
                stringResource(R.string.promise_wait),
                stringResource(R.string.promise_wait_sub),
            )
            Promise(
                R.drawable.ic_nightlight,
                stringResource(R.string.promise_night),
                stringResource(R.string.promise_night_sub),
            )
            Promise(
                R.drawable.ic_delete,
                stringResource(R.string.promise_delete),
                stringResource(R.string.promise_delete_sub),
            )
        }
    }
}

/**
 * Keep backup running (A7): the steps that were still needed when it opened, each checked in place
 * once done, without reordering. Samsung's step opens the guide (A8), and "I've done this" checks
 * it. Every step is optional; whatever is left stays in Settings.
 */
@Composable
fun SetupScreen(
    email: String?,
    shown: List<SetupStep>,
    system: SystemState,
    neverSleepingDone: Boolean,
    actions: KeepRunningActions,
    onNeverSleepingDone: () -> Unit,
    onFinish: () -> Unit,
) {
    var guide by rememberSaveable { mutableStateOf(false) }
    val open = system.openSetupSteps
    OnboardingFrame(
        top = {
            Row(
                Modifier.fillMaxWidth().height(56.dp).padding(start = 24.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    email?.let { stringResource(R.string.setup_connected, it) }.orEmpty(),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onFinish, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.setup_skip))
                }
            }
        },
        bottom = { PrimaryButton(stringResource(R.string.setup_finish), onFinish) },
        sides = 12.dp,
    ) {
        Column(
            Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                stringResource(R.string.setup_title),
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                stringResource(R.string.setup_body),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        SettingsGroup {
            shown.forEach { step ->
                row {
                    SetupStepRow(
                        step = step,
                        system = system,
                        done =
                            step !in open ||
                                (step == SetupStep.NEVER_SLEEPING && neverSleepingDone),
                        actions = actions,
                        onGuide = { guide = true },
                    )
                }
            }
        }
    }
    if (guide) {
        NeverSleepingGuide(
            onOpen = actions.openNeverSleeping,
            onDone = {
                guide = false
                onNeverSleepingDone()
            },
            onDismiss = { guide = false },
        )
    }
}

/**
 * An onboarding page: an optional bar, content that scrolls when the font is large, and its buttons
 * pinned to the bottom.
 */
@Composable
private fun OnboardingFrame(
    bottom: @Composable ColumnScope.() -> Unit,
    top: (@Composable () -> Unit)? = null,
    sides: Dp = 24.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            top?.invoke()
            Column(
                Modifier.weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = sides),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                content = content,
            )
            Column(
                Modifier.fillMaxWidth()
                    .padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                content = bottom,
            )
        }
    }
}

/** A 56 dp icon tile, the title and what it means. */
@Composable
private fun ColumnScope.Header(
    icon: Int,
    title: String,
    body: String,
    neutral: Boolean = false,
    top: Dp = 64.dp,
) {
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier.padding(top = top)
            .size(56.dp)
            .background(
                if (neutral) colors.surfaceContainer else colors.primaryContainer,
                RoundedCornerShape(18.dp),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painterResource(icon),
            contentDescription = null,
            tint = if (neutral) colors.onSurfaceVariant else colors.onPrimaryContainer,
            modifier = Modifier.size(30.dp),
        )
    }
    Text(
        title,
        style = MaterialTheme.typography.headlineMedium,
        modifier = Modifier.semantics { heading() },
    )
    Text(body, style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
}

/** One promise (PromiseRow): a 40 dp tile, a title and one line of detail. */
@Composable
private fun Promise(icon: Int, title: String, detail: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(
            Modifier.size(40.dp)
                .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painterResource(icon),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
        }
        Column(
            Modifier.padding(top = 1.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(title, style = SettleType.itemTitle)
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The page's one main action, 52 dp. */
@Composable
private fun PrimaryButton(label: String, onClick: () -> Unit, external: Boolean = false) {
    Button(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge.copy(fontSize = 15.sp))
        if (external) {
            Icon(
                painterResource(R.drawable.ic_open_in_new),
                contentDescription = null,
                modifier = Modifier.padding(start = 8.dp).size(18.dp),
            )
        }
    }
}

@Composable
private fun QuietButton(label: String, onClick: () -> Unit, enabled: Boolean = true) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge.copy(fontSize = 15.sp))
    }
}
