package com.iltan.settle.data.db

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface UiDao {
    @Query("SELECT * FROM ui_prefs WHERE id = 0") fun prefs(): Flow<UiPrefsEntity?>

    @Query("SELECT * FROM ui_prefs WHERE id = 0") suspend fun currentPrefs(): UiPrefsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun savePrefs(prefs: UiPrefsEntity)

    @Query("SELECT * FROM notice_dismissal") fun dismissals(): Flow<List<NoticeDismissalEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun dismiss(dismissal: NoticeDismissalEntity)

    /** Forgets dismissals of notices whose problem is gone. */
    @Query("DELETE FROM notice_dismissal WHERE `key` NOT IN (:current)")
    suspend fun keepDismissals(current: List<String>)
}
