package com.iltan.settle.data.trash

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BreakerCheckTest {

    private val missing = MutableStateFlow(312)
    private val seenChecking = mutableListOf<Boolean>()

    private fun check(onScan: () -> Unit) =
        BreakerCheck(
            scan = {
                seenChecking += check.checking.value
                onScan()
            },
            missing = missing,
            minimumMillis = 0,
        )

    private lateinit var check: BreakerCheck

    @Test
    fun itSaysHowManyCameBackAndShowsProgressMeanwhile() = runTest {
        check = check { missing.value = 12 }

        val result = check.run()

        assertEquals(BreakerCheck.Result(312, 12), result)
        assertEquals(300, result.back)
        assertTrue(seenChecking.single())
        assertFalse(check.checking.value)
    }

    @Test
    fun aFailedScanStillEndsTheProgress() = runTest {
        check = check { error("scan failed") }

        runCatching { check.run() }

        assertFalse(check.checking.value)
    }
}
