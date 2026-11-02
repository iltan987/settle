package com.iltan.settle.ui.settings

import android.app.LocaleManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.iltan.settle.R
import com.iltan.settle.data.system.SystemState
import com.iltan.settle.ui.chrome.BackTopBar
import com.iltan.settle.ui.chrome.RowTone
import com.iltan.settle.ui.chrome.SettingsColumn
import com.iltan.settle.ui.chrome.SettingsGroup
import com.iltan.settle.ui.chrome.SettingsRadioRow
import com.iltan.settle.ui.chrome.SettingsRow
import com.iltan.settle.ui.chrome.Trailing
import java.util.Locale

/** A pushed settings page: a back bar over its scrolling groups. */
@Composable
fun SettingsPage(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column {
            BackTopBar(title, onBack)
            SettingsColumn(Modifier.weight(1f)) { content() }
        }
    }
}

/** Whether any step to keep backup running is still worth showing (I2, I10). */
val SystemState.keepRunningShown: Boolean
    get() = openSetupSteps.isNotEmpty()

/**
 * Keep backup running (I10): the setup steps Android still lacks, each gone once done. Samsung's
 * Never sleeping apps can't be read back, so on Samsung that step always shows, and opens the guide
 * (A8) first.
 */
@Composable
fun KeepRunningPage(system: SystemState, actions: KeepRunningActions) {
    var guide by rememberSaveable { mutableStateOf(false) }
    SettingsGroup(lead = stringResource(R.string.keep_running_lead)) {
        system.openSetupSteps.forEach { step ->
            row {
                SetupStepRow(
                    step = step,
                    system = system,
                    done = false,
                    actions = actions,
                    onGuide = { guide = true },
                    tone = RowTone.WARN,
                )
            }
        }
    }
    if (guide) {
        NeverSleepingGuide(
            onOpen = actions.openNeverSleeping,
            onDone = { guide = false },
            onDismiss = { guide = false },
        )
    }
}

/** The main page's one row for the steps: how many are left and the first one's reason. */
@Composable
internal fun KeepRunningRow(system: SystemState, onClick: () -> Unit) {
    val open = system.openSteps
    SettingsRow(
        title =
            if (open > 0) {
                pluralStringResource(R.plurals.keep_running_left, open, open)
            } else stringResource(R.string.keep_running_never_sleeping),
        sub =
            stringResource(
                when {
                    !system.notificationsAllowed -> R.string.keep_running_notifications_sub
                    system.backgroundRestricted || !system.batteryUnrestricted ->
                        R.string.keep_running_battery_sub
                    else -> R.string.keep_running_never_sleeping_sub
                }
            ),
        icon = if (open > 0) R.drawable.ic_battery_alert else R.drawable.ic_bedtime_off,
        tone = if (open > 0) RowTone.WARN else RowTone.PLAIN,
        trailing = Trailing.Chevron,
        onClick = onClick,
    )
}

/**
 * Language (I11): each language in its own name, with its name in the current language below when
 * that differs. The choice applies at once through the per-app locale.
 */
@Composable
fun LanguagePage(language: AppLanguage, onChoose: (AppLanguage) -> Unit) {
    val ui = LocalConfiguration.current.locales[0]
    val context = LocalContext.current
    SettingsGroup(modifier = Modifier.selectableGroup()) {
        AppLanguage.entries.forEach { option ->
            row {
                SettingsRadioRow(
                    title = stringResource(option.label),
                    sub = option.subtitle(context, ui),
                    selected = option == language,
                    onClick = { onChoose(option) },
                )
            }
        }
    }
}

internal val AppLanguage.label: Int
    get() =
        when (this) {
            AppLanguage.SYSTEM -> R.string.language_system
            AppLanguage.ENGLISH -> R.string.language_english
            AppLanguage.TURKISH -> R.string.language_turkish
        }

/** "English" under System default; "Turkish" under Türkçe when the app speaks English. */
private fun AppLanguage.subtitle(context: Context, ui: Locale): String? {
    val locale =
        tag?.let(Locale::forLanguageTag)
            ?: context.getSystemService(LocaleManager::class.java).systemLocales[0]
    val inUi = locale.getDisplayLanguage(ui).replaceFirstChar { it.titlecase(ui) }
    val own = locale.getDisplayLanguage(locale).replaceFirstChar { it.titlecase(locale) }
    return inUi.takeIf { this == AppLanguage.SYSTEM || it != own }
}

/** What the About page's rows open. */
class AboutActions(
    val openHome: () -> Unit = {},
    val openPrivacy: () -> Unit = {},
    val sendFeedback: () -> Unit = {},
)

/**
 * About Settle (I12): the version, which can be selected and copied, and links out. The licences
 * screen comes in a later phase, so it has no row yet.
 */
@Composable
fun AboutPage(version: String, actions: AboutActions) {
    SettingsGroup {
        row {
            SelectionContainer {
                Text(
                    stringResource(R.string.settings_version, version),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
        row {
            SettingsRow(
                title = stringResource(R.string.about_safety),
                icon = R.drawable.ic_verified_user,
                trailing = Trailing.External,
                onClick = actions.openHome,
            )
        }
        row {
            SettingsRow(
                title = stringResource(R.string.about_privacy),
                icon = R.drawable.ic_policy,
                trailing = Trailing.External,
                onClick = actions.openPrivacy,
            )
        }
        row {
            SettingsRow(
                title = stringResource(R.string.about_feedback),
                icon = R.drawable.ic_mail,
                trailing = Trailing.External,
                onClick = actions.sendFeedback,
            )
        }
    }
}

internal fun openAppInfo(context: Context) {
    context.startActivity(
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            "package:${context.packageName}".toUri(),
        )
    )
}

/**
 * Samsung's "Never sleeping apps" list, through the intent Samsung documents
 * (developer.samsung.com/mobile/app-management.html); App info if this One UI lacks it.
 */
internal fun openNeverSleeping(context: Context) {
    try {
        context.startActivity(
            Intent("com.samsung.android.sm.ACTION_OPEN_CHECKABLE_LISTACTIVITY")
                .setPackage("com.samsung.android.lool")
                .putExtra("activity_type", 2)
        )
    } catch (e: ActivityNotFoundException) {
        openAppInfo(context)
    }
}
