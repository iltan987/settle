package com.iltan.settle.ui.onboarding

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.iltan.settle.appGraph
import com.iltan.settle.data.media.MediaAccess
import com.iltan.settle.ui.settings.KeepRunningActions
import com.iltan.settle.ui.settings.SetupStep
import com.iltan.settle.ui.settings.driveViewModel
import com.iltan.settle.ui.settings.keepRunningShown
import com.iltan.settle.ui.settings.message
import com.iltan.settle.ui.settings.openAppInfo
import com.iltan.settle.ui.settings.openNeverSleeping
import com.iltan.settle.ui.settings.openSetupSteps
import kotlinx.coroutines.flow.map

/** A screen of onboarding (A). */
enum class OnboardingStep {
    WELCOME,
    PARTIAL,
    BLOCKED,
    CONNECT,
    SETUP,
}

/**
 * Where onboarding stands; null once the app itself shows. Photo access comes first and is never
 * skipped: without full access Settle shows an access screen in the timeline's place, even after
 * onboarding ended. Everything after it is offered once and can be skipped; what's skipped returns
 * as a notice card or a Settings row, never as a screen.
 */
fun onboardingStep(
    access: MediaAccess,
    /** Onboarding ran to its end once. */
    done: Boolean,
    /** The user turned photo access down in this run. */
    denied: Boolean,
    connected: Boolean,
    /** Connecting was skipped with "Not now". */
    connectSkipped: Boolean,
    /** Some setup step is open, or the setup screen is already showing. */
    setupNeeded: Boolean,
    /** The setup screen was left with Skip or Done. */
    setupLeft: Boolean,
): OnboardingStep? =
    when {
        access == MediaAccess.NONE ->
            if (done || denied) OnboardingStep.BLOCKED else OnboardingStep.WELCOME
        access == MediaAccess.PARTIAL -> OnboardingStep.PARTIAL
        done -> null
        !connected -> if (connectSkipped) null else OnboardingStep.CONNECT
        setupNeeded && !setupLeft -> OnboardingStep.SETUP
        else -> null
    }

/** The stored Drive account, once read. */
private data class Account(val email: String?)

/**
 * Shows onboarding until it has run once, and an access screen whenever full photo access is
 * missing; [content] otherwise. Access is read again on every resume, since the user can change it
 * in system settings at any time.
 */
@Composable
fun OnboardingGate(onAccessGranted: () -> Unit, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val graph = context.appGraph
    var access by remember { mutableStateOf(MediaAccess.of(context)) }
    fun checkAccess() {
        val now = MediaAccess.of(context)
        if (now == MediaAccess.FULL && access != MediaAccess.FULL) onAccessGranted()
        access = now
    }
    var denied by rememberSaveable { mutableStateOf(false) }
    var connectSkipped by rememberSaveable { mutableStateOf(false) }
    var setupShown by rememberSaveable { mutableStateOf(false) }
    var setupLeft by rememberSaveable { mutableStateOf(false) }

    LifecycleResumeEffect(Unit) {
        checkAccess()
        onPauseOrDispose {}
    }
    val askAccess =
        rememberLauncherForActivityResult(RequestMultiplePermissions()) {
            checkAccess()
            if (access == MediaAccess.NONE) denied = true
        }
    val prefs by remember { graph.uiPreferences.prefs }.collectAsStateWithLifecycle(null)
    val account by remember {
        graph.database.backupDao().account().map { Account(it?.email) }
    }
        .collectAsStateWithLifecycle(null)
    val system by graph.deviceSetup.state.collectAsStateWithLifecycle()

    // Settle's own records decide between the screens; they take a moment to read.
    val loadedPrefs = prefs
    val loadedAccount = account
    if (loadedPrefs == null || loadedAccount == null) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {}
        return
    }
    val step =
        onboardingStep(
            access = access,
            done = loadedPrefs.onboardingDone,
            denied = denied,
            connected = loadedAccount.email != null,
            connectSkipped = connectSkipped,
            setupNeeded = setupShown || system.keepRunningShown,
            setupLeft = setupLeft,
        )
    val finished = step == null && !loadedPrefs.onboardingDone
    LaunchedEffect(finished) { if (finished) graph.uiPreferences.onboardingDone() }
    SideEffect { if (step == OnboardingStep.SETUP) setupShown = true }

    val openSettings = { openAppInfo(context) }
    when (step) {
        null -> content()
        OnboardingStep.WELCOME -> WelcomeScreen { askAccess.launch(MediaAccess.permissions) }
        OnboardingStep.PARTIAL ->
            AccessPartialScreen(
                onAllowAll = { askAccess.launch(MediaAccess.permissions) },
                onOpenSettings = openSettings,
            )
        OnboardingStep.BLOCKED -> AccessBlockedScreen(openSettings)
        OnboardingStep.CONNECT -> ConnectDrive(onNotNow = { connectSkipped = true })
        OnboardingStep.SETUP -> Setup(email = loadedAccount.email, onFinish = { setupLeft = true })
    }
}

/** Connect Google Drive (A5), wired to Google's account chooser and consent screen (A6). */
@Composable
private fun ConnectDrive(onNotNow: () -> Unit) {
    val graph = LocalContext.current.appGraph
    val drive = viewModel { driveViewModel(graph) }
    val state by drive.state.collectAsStateWithLifecycle()
    val consent by drive.consent.collectAsStateWithLifecycle()
    var problem by rememberSaveable { mutableStateOf<Int?>(null) }
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
            drive.onConsentResult(it.resultCode, it.data)
        }
    LaunchedEffect(consent) {
        consent?.let {
            drive.consentLaunched()
            launcher.launch(IntentSenderRequest.Builder(it).build())
        }
    }
    LaunchedEffect(state.notice) {
        state.notice?.let {
            drive.noticeShown()
            problem = it.message
        }
    }
    ConnectDriveScreen(
        connecting = state.busy,
        problem = problem?.let { stringResource(it) },
        onConnect = {
            problem = null
            drive.connect()
        },
        onNotNow = onNotNow,
    )
}

/** Keep backup running (A7, A8), wired to Android's settings. */
@Composable
private fun Setup(email: String?, onFinish: () -> Unit) {
    val context = LocalContext.current
    val graph = context.appGraph
    val system by graph.deviceSetup.state.collectAsStateWithLifecycle()
    // The steps open when the screen first showed; done ones stay, checked.
    val shown = rememberSaveable { system.openSetupSteps.map { it.name } }
    var neverSleepingDone by rememberSaveable { mutableStateOf(false) }
    val askNotifications =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            graph.deviceSetup.refresh()
        }
    SetupScreen(
        email = email,
        shown = shown.map(SetupStep::valueOf),
        system = system,
        neverSleepingDone = neverSleepingDone,
        actions =
            KeepRunningActions(
                allowNotifications = {
                    askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                },
                openAppInfo = { openAppInfo(context) },
                openNeverSleeping = { openNeverSleeping(context) },
            ),
        onNeverSleepingDone = { neverSleepingDone = true },
        onFinish = onFinish,
    )
}
