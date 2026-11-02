package com.iltan.settle.data.trash

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

/**
 * "Check again" for a mass disappearance (G4): scans the whole phone once more and says how many of
 * the missing files came back. It only reads the phone; nothing in Drive changes, and files that
 * are back simply leave the question.
 */
class BreakerCheck(
    private val scan: suspend () -> Unit,
    /** How many files of the mass disappearance are still missing. */
    private val missing: Flow<Int>,
    /** The check shows at least this long, so its progress can be read. */
    private val minimumMillis: Long = 1_000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _checking = MutableStateFlow(false)

    /** True while a check runs, for the notice's progress state. */
    val checking: StateFlow<Boolean> = _checking.asStateFlow()

    data class Result(val missingBefore: Int, val missingAfter: Int) {
        val back: Int
            get() = (missingBefore - missingAfter).coerceAtLeast(0)
    }

    suspend fun run(): Result {
        val before = missing.first()
        val started = clock()
        _checking.value = true
        try {
            scan()
            delay((minimumMillis - (clock() - started)).coerceAtLeast(0))
        } finally {
            _checking.value = false
        }
        return Result(before, missing.first())
    }
}
