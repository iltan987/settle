package com.iltan.settle.data.drive

import android.accounts.Account
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.RevokeAccessRequest
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Scope
import com.iltan.settle.data.db.BackupDao
import com.iltan.settle.data.db.DriveAccountEntity
import com.iltan.settle.domain.backup.DriveException
import com.iltan.settle.domain.backup.DriveFailure
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await

/** The next step of connecting Drive. */
sealed interface ConnectStep {
    /** Google needs the user to pick an account and allow access: launch this. */
    data class NeedsUser(val intent: PendingIntent) : ConnectStep

    data class Connected(val email: String) : ConnectStep

    /** Google came back without Drive access: the user didn't allow it (A6). */
    data object Refused : ConnectStep

    data class Failed(val reason: String?) : ConnectStep
}

/**
 * Drive authorization with Google Play services' `AuthorizationClient` and only the `drive.file`
 * scope (developer.android.com/identity/authorization, ADR-010). No sign-in step: the first
 * `authorize()` shows Google's account chooser and consent; later calls return tokens silently.
 * Play services keeps and refreshes the grant, so Settle stores no tokens, only the chosen
 * account's email, which it passes on every request so the same account is always used.
 */
class DriveAuth(
    private val context: Context,
    private val dao: BackupDao,
    /** Learns the account behind a token, e.g. through Drive's `about`. */
    private val emailFor: suspend (token: String) -> String?,
) : DriveTokens {

    private val client
        get() = Identity.getAuthorizationClient(context)

    private val lock = Mutex()
    private var cachedToken: String? = null

    /** Starts connecting; the result may need the user (see [finishConnect]). */
    suspend fun beginConnect(): ConnectStep = complete { client.authorize(request(null)).await() }

    /** Completes connecting with what the account chooser and consent screen returned. */
    suspend fun finishConnect(data: Intent?): ConnectStep = complete {
        client.getAuthorizationResultFromIntent(data)
    }

    private suspend fun complete(authorize: suspend () -> AuthorizationResult): ConnectStep =
        try {
            val result = authorize()
            val pending = result.pendingIntent
            val token = result.accessToken
            when {
                result.hasResolution() && pending != null -> ConnectStep.NeedsUser(pending)
                token == null ||
                    (result.grantedScopes.isNotEmpty() &&
                        DRIVE_FILE.scopeUri !in result.grantedScopes) -> ConnectStep.Refused
                else -> {
                    val email = emailFor(token)
                    if (email == null) ConnectStep.Failed("Drive didn't name the account")
                    else {
                        dao.setAccount(DriveAccountEntity(email = email))
                        lock.withLock { cachedToken = token }
                        ConnectStep.Connected(email)
                    }
                }
            }
        } catch (e: ApiException) {
            Log.w(TAG, "Connecting Drive failed", e)
            ConnectStep.Failed(CommonStatusCodes.getStatusCodeString(e.statusCode))
        } catch (e: DriveException) {
            Log.w(TAG, "Reading the Drive account failed", e)
            ConnectStep.Failed(e.failure.name)
        }

    override suspend fun token(): String = lock.withLock {
        cachedToken?.let {
            return it
        }
        val email =
            dao.accountEmail()
                ?: throw DriveException(DriveFailure.AUTH_NEEDED, "Drive is not connected")
        val result =
            try {
                client.authorize(request(email)).await()
            } catch (e: ApiException) {
                val failure =
                    if (e.statusCode == CommonStatusCodes.NETWORK_ERROR) DriveFailure.TRANSIENT
                    else DriveFailure.AUTH_NEEDED
                throw DriveException(failure, "Authorization failed", e)
            }
        // Needing the user means the grant was withdrawn; only the app's UI can ask again.
        val token =
            result.accessToken?.takeUnless { result.hasResolution() }
                ?: throw DriveException(DriveFailure.AUTH_NEEDED, "Drive needs reconnecting")
        cachedToken = token
        token
    }

    override suspend fun invalidate(token: String) {
        lock.withLock { if (cachedToken == token) cachedToken = null }
        try {
            client.clearToken(ClearTokenRequest.builder().setToken(token).build()).await()
        } catch (e: ApiException) {
            Log.w(TAG, "Clearing a rejected token failed", e)
        }
    }

    /**
     * Withdraws Settle's Drive access and forgets the account. Files already in Drive and the local
     * upload records stay; reconnecting continues where things stopped.
     */
    suspend fun disconnect() {
        val email = dao.accountEmail()
        lock.withLock { cachedToken = null }
        if (email != null) {
            try {
                client
                    .revokeAccess(
                        RevokeAccessRequest.builder()
                            .setAccount(Account(email, ACCOUNT_TYPE))
                            .setScopes(listOf(DRIVE_FILE))
                            .build()
                    )
                    .await()
            } catch (e: ApiException) {
                Log.w(TAG, "Revoking Drive access failed; forgetting the account anyway", e)
            }
        }
        dao.deleteAccount()
    }

    private fun request(email: String?): AuthorizationRequest =
        AuthorizationRequest.builder()
            .setRequestedScopes(listOf(DRIVE_FILE))
            .apply { email?.let { setAccount(Account(it, ACCOUNT_TYPE)) } }
            .build()

    private companion object {
        const val TAG = "DriveAuth"
        const val ACCOUNT_TYPE = "com.google"
        val DRIVE_FILE = Scope("https://www.googleapis.com/auth/drive.file")
    }
}
