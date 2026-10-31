package com.iltan.settle.ui.settings

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.iltan.settle.data.db.BackupDao
import com.iltan.settle.data.drive.ConnectStep
import com.iltan.settle.data.drive.DriveAbout
import com.iltan.settle.data.drive.DriveAuth
import com.iltan.settle.data.drive.DriveRest
import com.iltan.settle.data.drive.DriveSandbox
import com.iltan.settle.domain.backup.DriveException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Something the Drive section reports once. */
enum class DriveNotice {
    CONNECT_FAILED,
    SANDBOX_RESET,
    SANDBOX_RESET_FAILED,
}

data class DriveSettingsState(
    /** False until the stored account has been read. */
    val loaded: Boolean = false,
    val email: String? = null,
    val about: DriveAbout? = null,
    val folder: String = "",
    val busy: Boolean = false,
    val sandbox: Boolean = false,
    val notice: DriveNotice? = null,
)

class DriveSettingsViewModel(
    private val dao: BackupDao,
    private val auth: DriveAuth,
    private val drive: DriveRest,
    private val sandbox: DriveSandbox?,
    folder: String,
) : ViewModel() {

    private val about = MutableStateFlow<DriveAbout?>(null)
    private val busy = MutableStateFlow(false)
    private val notice = MutableStateFlow<DriveNotice?>(null)

    private val _consent = MutableStateFlow<PendingIntent?>(null)
    /** Google's account chooser and consent screen, for the UI to launch once. */
    val consent: StateFlow<PendingIntent?> = _consent.asStateFlow()

    val state: StateFlow<DriveSettingsState> =
        combine(dao.account(), about, busy, notice) { account, about, busy, notice ->
                DriveSettingsState(
                    loaded = true,
                    email = account?.email,
                    about = about.takeIf { account != null },
                    folder = folder,
                    busy = busy,
                    sandbox = sandbox != null,
                    notice = notice,
                )
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DriveSettingsState())

    init {
        viewModelScope.launch {
            dao.account()
                .map { it?.email }
                .distinctUntilChanged()
                .collectLatest { email ->
                    about.value = null
                    if (email != null) {
                        about.value =
                            try {
                                drive.about()
                            } catch (e: DriveException) {
                                Log.w(TAG, "Reading Drive storage failed", e)
                                null
                            }
                    }
                }
        }
    }

    fun connect() = working { handle(auth.beginConnect()) }

    /** What the account chooser and consent screen returned. Backing out changes nothing. */
    fun onConsentResult(resultCode: Int, data: Intent?) {
        _consent.value = null
        if (resultCode == Activity.RESULT_OK) working { handle(auth.finishConnect(data)) }
    }

    fun consentLaunched() {
        _consent.value = null
    }

    fun disconnect() = working { auth.disconnect() }

    fun resetSandbox() = working {
        notice.value =
            try {
                sandbox?.reset()
                DriveNotice.SANDBOX_RESET
            } catch (e: DriveException) {
                Log.w(TAG, "Resetting the debug Drive folder failed", e)
                DriveNotice.SANDBOX_RESET_FAILED
            }
    }

    fun noticeShown() {
        notice.value = null
    }

    private fun handle(step: ConnectStep) {
        when (step) {
            is ConnectStep.NeedsUser -> _consent.value = step.intent
            is ConnectStep.Connected -> {}
            is ConnectStep.Failed -> {
                Log.w(TAG, "Connecting Drive failed: ${step.reason}")
                notice.value = DriveNotice.CONNECT_FAILED
            }
        }
    }

    private fun working(block: suspend () -> Unit) {
        viewModelScope.launch {
            busy.value = true
            try {
                block()
            } finally {
                busy.value = false
            }
        }
    }

    private companion object {
        const val TAG = "DriveSettings"
    }
}
