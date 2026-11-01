package com.iltan.settle.data.system

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What Android allows Settle right now, as far as backup and its setup steps care. */
data class SystemState(
    val notificationsAllowed: Boolean = true,
    /** "Unrestricted" battery use; otherwise Android may hold jobs back. */
    val batteryUnrestricted: Boolean = true,
    /** "Restricted" battery use: jobs don't run at all. */
    val backgroundRestricted: Boolean = false,
    val samsung: Boolean = false,
    /** Media location access, without which originals can't be read byte for byte (R1). */
    val canReadOriginals: Boolean = true,
) {
    /** Setup steps still open, apart from Samsung's, which Android can't report. */
    val openSteps: Int
        get() =
            listOf(!notificationsAllowed, !batteryUnrestricted || backgroundRestricted).count { it }
}

/**
 * Android's permission and battery settings as a flow. Android sends no callback when the user
 * changes them in system settings, so the activity calls [refresh] on every resume.
 */
class DeviceSetup(private val context: Context) {
    private val _state = MutableStateFlow(read())
    val state: StateFlow<SystemState> = _state.asStateFlow()

    fun refresh() {
        _state.value = read()
    }

    private fun read() =
        SystemState(
            notificationsAllowed = granted(Manifest.permission.POST_NOTIFICATIONS),
            batteryUnrestricted =
                context
                    .getSystemService(PowerManager::class.java)
                    .isIgnoringBatteryOptimizations(context.packageName),
            backgroundRestricted =
                context.getSystemService(ActivityManager::class.java).isBackgroundRestricted,
            samsung = Build.MANUFACTURER.equals("samsung", ignoreCase = true),
            canReadOriginals = granted(Manifest.permission.ACCESS_MEDIA_LOCATION),
        )

    private fun granted(permission: String) =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
}
