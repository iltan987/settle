package com.iltan.settle.data

import com.iltan.settle.data.db.NoticeDismissalEntity
import com.iltan.settle.data.db.UiDao
import com.iltan.settle.data.db.UiPrefsEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** How the user left Settle's screens, and which notices they dismissed (ADR-016). */
class UiPreferences(
    private val dao: UiDao,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Mutex()

    val prefs: Flow<UiPrefsEntity> =
        dao.prefs().map { it ?: UiPrefsEntity() }.distinctUntilChanged()

    suspend fun setGridColumns(columns: Int) = update {
        it.copy(gridColumns = columns.coerceIn(MIN_COLUMNS, MAX_COLUMNS))
    }

    suspend fun mediaExplainerShown() = update { it.copy(mediaExplainerShown = true) }

    suspend fun onboardingDone() = update { it.copy(onboardingDone = true) }

    /** Keys of notices to keep collapsed now: dismissed less than a day ago. */
    val dismissed: Flow<Set<String>> =
        dao.dismissals()
            .map { all ->
                val now = clock()
                all.filter { now - it.dismissedAtMs < NOTICE_BACK_AFTER_MILLIS }
                    .map { it.key }
                    .toSet()
            }
            .distinctUntilChanged()

    suspend fun dismiss(key: String) = dao.dismiss(NoticeDismissalEntity(key, clock()))

    /** Forgets dismissals of notices that no longer apply, so their next occurrence shows. */
    suspend fun noticesNow(keys: Collection<String>) = dao.keepDismissals(keys.toList())

    private suspend fun update(change: (UiPrefsEntity) -> UiPrefsEntity) = lock.withLock {
        dao.savePrefs(change(dao.currentPrefs() ?: UiPrefsEntity()))
    }

    companion object {
        const val MIN_COLUMNS = 3
        const val MAX_COLUMNS = 6
        /** A dismissed notice returns a day later while its problem lasts (B7, B12). */
        const val NOTICE_BACK_AFTER_MILLIS = 24 * 60 * 60 * 1000L
    }
}
