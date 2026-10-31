package com.iltan.settle.data.backup

import com.iltan.settle.data.db.BackupDao
import com.iltan.settle.data.db.BackupSettingsEntity
import com.iltan.settle.domain.backup.BackupPause
import com.iltan.settle.domain.backup.BackupSettings
import com.iltan.settle.domain.transfer.NetworkUse
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The user's backup choices, and whether Drive stopped backup until the user acts. */
data class BackupChoices(
    val settings: BackupSettings = BackupSettings(),
    val networkUse: NetworkUse = NetworkUse.Polite,
    /** The last fixed limit the user chose, kept while another mode is on. */
    val limitMbps: Int = DEFAULT_LIMIT_MBPS,
    /** AUTH_NEEDED or QUOTA_FULL, set by a run that hit it. */
    val blocked: BackupPause? = null,
) {
    companion object {
        const val DEFAULT_LIMIT_MBPS = 20
    }
}

/**
 * Reads and changes [BackupChoices], kept in Room's single settings row. One instance per process:
 * changes are read, edited and written under one lock, so a run blocking backup and the user
 * changing a setting at the same moment can't undo each other.
 */
class BackupPreferences(private val dao: BackupDao) {

    private val lock = Mutex()

    val choices: Flow<BackupChoices> =
        dao.observeSettings().map { it?.toChoices() ?: BackupChoices() }

    suspend fun current(): BackupChoices = dao.settings()?.toChoices() ?: BackupChoices()

    suspend fun update(change: (BackupChoices) -> BackupChoices) {
        lock.withLock { dao.saveSettings(change(current()).toEntity()) }
    }

    suspend fun block(reason: BackupPause) = update { it.copy(blocked = reason) }

    /** Clears [reason] if that is what blocks backup; returns whether it did. */
    suspend fun unblock(reason: BackupPause): Boolean = lock.withLock {
        val choices = current()
        if (choices.blocked != reason) return@withLock false
        dao.saveSettings(choices.copy(blocked = null).toEntity())
        true
    }

    private fun BackupSettingsEntity.toChoices() =
        BackupChoices(
            settings =
                BackupSettings(
                    automatic = automatic,
                    graceMillis = graceMs,
                    onlyWhileCharging = onlyWhileCharging,
                    useMobileData = useMobileData,
                    paused = paused,
                ),
            networkUse =
                when (networkUse) {
                    LIMIT -> NetworkUse.Limit(limitMbps.toLong() * BYTES_PER_MBPS)
                    UNLIMITED -> NetworkUse.Unlimited
                    else -> NetworkUse.Polite
                },
            limitMbps = limitMbps,
            blocked = blocked,
        )

    private fun BackupChoices.toEntity() =
        BackupSettingsEntity(
            automatic = settings.automatic,
            graceMs = settings.graceMillis,
            onlyWhileCharging = settings.onlyWhileCharging,
            useMobileData = settings.useMobileData,
            paused = settings.paused,
            networkUse =
                when (networkUse) {
                    is NetworkUse.Limit -> LIMIT
                    NetworkUse.Unlimited -> UNLIMITED
                    NetworkUse.Polite -> POLITE
                },
            limitMbps =
                (networkUse as? NetworkUse.Limit)?.let {
                    (it.bytesPerSecond / BYTES_PER_MBPS).toInt()
                } ?: limitMbps,
            blocked = blocked,
        )

    private companion object {
        const val POLITE = "POLITE"
        const val LIMIT = "LIMIT"
        const val UNLIMITED = "UNLIMITED"
        /** Megabits, as internet plans are sold, in bytes. */
        const val BYTES_PER_MBPS = 1_000_000L / 8
    }
}
