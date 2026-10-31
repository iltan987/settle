package com.iltan.settle.domain.transfer

import io.kotest.matchers.ints.shouldBeLessThan as intShouldBeLessThan
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.longs.shouldBeLessThanOrEqual
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Replays network histories through the pacer. A simulated link carries at most [linkRate] bytes
 * per second; each extra upload adds [perSlotRate] until the link is full, the way more uploads at
 * once hide Drive's per-file delay. Delay rises once the pacer lets uploads fill the link.
 */
class UploadPacerTest {

    private val config = PacerConfig()
    private val tick = config.tickMillis
    private val mib = 1024L * 1024

    private class Link(
        val linkRate: Long,
        val perSlotRate: Long,
        val quietDelay: Long = 40,
        /** Queueing delay when sending at the full link rate. */
        val fullDelay: Long = 300,
    ) {
        fun sent(pace: Pace, tickMillis: Long): Long {
            val wanted = minOf(linkRate, perSlotRate * pace.slots)
            val allowed = pace.capBytesPerSecond?.let { minOf(it, wanted) } ?: wanted
            return allowed * tickMillis / 1000
        }

        /** Delay grows only in the last fifth of the link, as a router's queue does. */
        fun delay(pace: Pace): Long {
            val wanted = minOf(linkRate, perSlotRate * pace.slots)
            val rate = pace.capBytesPerSecond?.let { minOf(it, wanted) } ?: wanted
            val load = rate.toDouble() / linkRate
            return quietDelay + if (load > 0.8) ((load - 0.8) / 0.2 * fullDelay).toLong() else 0
        }
    }

    private fun run(
        pacer: UploadPacer,
        link: Link,
        fromMillis: Long,
        ticks: Int,
        onTick: (Long, Pace) -> Unit = { _, _ -> },
    ): Long {
        var now = fromMillis
        var pace = pacer.pace
        repeat(ticks) {
            now += tick
            pace = pacer.onTick(now, link.sent(pace, tick), link.delay(pace))
            onTick(now, pace)
        }
        return now
    }

    @Test
    fun `small files add uploads while total throughput keeps rising`() {
        // Each upload manages 1 MiB/s on small files; the line takes 6 MiB/s.
        val link = Link(linkRate = 6 * mib, perSlotRate = mib)
        val pacer = UploadPacer(NetworkUse.Unlimited, config, startMillis = 0)
        run(pacer, link, 0, ticks = 60)
        pacer.pace.slots shouldBe config.maxSlots
    }

    @Test
    fun `large files stop adding uploads once one more no longer helps`() {
        // One upload already fills the line, so a second one gains nothing.
        val link = Link(linkRate = 6 * mib, perSlotRate = 6 * mib)
        val pacer = UploadPacer(NetworkUse.Unlimited, config.copy(startSlots = 1), startMillis = 0)
        run(pacer, link, 0, ticks = 60)
        pacer.pace.slots shouldBe 1
    }

    @Test
    fun `polite measures the line, then stays below three quarters of it`() {
        val link = Link(linkRate = 6 * mib, perSlotRate = 6 * mib)
        val pacer = UploadPacer(NetworkUse.Polite, config, startMillis = 0)
        pacer.pace.capBytesPerSecond.shouldBeNull()
        val end = run(pacer, link, 0, ticks = 4)
        // The full line shows as delay, so measuring ends early below the line rate.
        pacer.pace.capBytesPerSecond.shouldNotBeNull() shouldBeLessThan 6 * mib
        run(pacer, link, end, ticks = 100) { _, pace ->
            pace.capBytesPerSecond.shouldNotBeNull() shouldBeLessThanOrEqual (6 * mib * 3 / 4)
        }
        // And it doesn't starve backup: it settles near the ceiling.
        pacer.pace.capBytesPerSecond!! shouldBeGreaterThanOrEqual 4 * mib
    }

    @Test
    fun `when someone else fills the network, polite backs off quickly and recovers slowly`() {
        val pacer = UploadPacer(NetworkUse.Polite, config, startMillis = 0)
        var now = 0L
        fun tick(bytesPerSecond: Long, delay: Long) {
            now += tick
            pacer.onTick(now, bytesPerSecond * tick / 1000, delay)
        }
        // Quiet network: measures 6 MiB/s at a 40 ms delay, then settles at the ceiling.
        repeat(config.measureTicks) { tick(6 * mib, 40) }
        repeat(10) { tick(4 * mib, 40) }
        val before = pacer.pace.capBytesPerSecond!!
        before shouldBe 6 * mib * 3 / 4

        // A video call starts: delay jumps by 250 ms. The last three samples must all show it (the
        // filter ignores one-off spikes), then each tick backs off further.
        repeat(5) { tick(4 * mib, 290) }
        pacer.pace.capBytesPerSecond!! shouldBeLessThan before / 2

        // The call ends; the cap creeps back up and doesn't jump straight to the ceiling.
        repeat(3) { tick(mib, 40) }
        pacer.pace.capBytesPerSecond!! shouldBeLessThan before
        repeat(40) { tick(mib, 40) }
        pacer.pace.capBytesPerSecond shouldBe before
    }

    @Test
    fun `measuring uses enough uploads to see the line, not small files' gaps`() {
        val pacer = UploadPacer(NetworkUse.Polite, config.copy(startSlots = 1), startMillis = 0)
        pacer.pace shouldBe Pace(config.measureSlots, null)
        var now = 0L
        repeat(config.measureTicks) {
            now += tick
            pacer.onTick(now, 4 * mib * tick / 1000, 40)
        }
        pacer.pace shouldBe Pace(1, 4 * mib * 3 / 4)
        UploadPacer(NetworkUse.Polite, config.copy(maxSlots = 2), startMillis = 0)
            .pace
            .slots shouldBe 2
    }

    @Test
    fun `ticks without uploads change nothing, however noisy the delay`() {
        val pacer = UploadPacer(NetworkUse.Polite, config, startMillis = 0)
        var now = 0L
        repeat(config.measureTicks) {
            now += tick
            pacer.onTick(now, 4 * mib * tick / 1000, 40)
        }
        val settled = pacer.pace
        repeat(30) {
            now += tick
            pacer.onTick(now, 0, delayMillis = 500)
        }
        pacer.pace shouldBe settled
    }

    @Test
    fun `an empty first measurement doesn't end it`() {
        val pacer = UploadPacer(NetworkUse.Polite, config, startMillis = 0)
        repeat(10) { pacer.onTick((it + 1) * tick, 0, 40) }
        pacer.pace.capBytesPerSecond.shouldBeNull()
    }

    @Test
    fun `delay also removes uploads in polite mode`() {
        val pacer = UploadPacer(NetworkUse.Polite, config.copy(startSlots = 4), startMillis = 0)
        var now = 0L
        repeat(3) {
            now += tick
            pacer.onTick(now, mib, delayMillis = 40)
        }
        repeat(3) {
            now += tick
            pacer.onTick(now, mib, delayMillis = 400)
        }
        pacer.pace.slots intShouldBeLessThan 4
    }

    @Test
    fun `a rate limit from Drive halves the uploads in every mode`() {
        for (use in listOf(NetworkUse.Polite, NetworkUse.Unlimited, NetworkUse.Limit(mib))) {
            val pacer = UploadPacer(use, config.copy(startSlots = 6), startMillis = 0)
            pacer.onTick(tick, mib, null, rateLimited = true)
            pacer.pace.slots shouldBe 3
            pacer.onTick(2 * tick, mib, null, rateLimited = true)
            pacer.pace.slots shouldBe 1
            pacer.onTick(3 * tick, mib, null, rateLimited = true)
            pacer.pace.slots shouldBe 1
        }
    }

    @Test
    fun `a fixed limit is kept whatever the delay`() {
        val pacer = UploadPacer(NetworkUse.Limit(2 * mib), config, startMillis = 0)
        var now = 0L
        repeat(10) {
            now += tick
            pacer.onTick(now, 2 * mib * tick / 1000, delayMillis = if (it < 3) 40 else 500)
            pacer.pace.capBytesPerSecond shouldBe 2 * mib
        }
    }

    @Test
    fun `unlimited never caps`() {
        val link = Link(linkRate = 6 * mib, perSlotRate = 2 * mib)
        val pacer = UploadPacer(NetworkUse.Unlimited, config, startMillis = 0)
        run(pacer, link, 0, ticks = 50) { _, pace -> pace.capBytesPerSecond.shouldBeNull() }
    }

    @Test
    fun `polite measures again every half hour, so a faster line is noticed`() {
        val pacer = UploadPacer(NetworkUse.Polite, config, startMillis = 0)
        val slow = Link(linkRate = 2 * mib, perSlotRate = 2 * mib)
        val end = run(pacer, slow, 0, ticks = 30)
        pacer.pace.capBytesPerSecond!! shouldBeLessThanOrEqual (2 * mib * 3 / 4)
        val fast = Link(linkRate = 8 * mib, perSlotRate = 8 * mib)
        run(pacer, fast, end, ticks = (config.remeasureEveryMillis / tick).toInt() + 10)
        pacer.pace.capBytesPerSecond!! shouldBeGreaterThanOrEqual 4 * mib
    }

    @Test
    fun `a new network starts over`() {
        val pacer = UploadPacer(NetworkUse.Polite, config, startMillis = 0)
        val end = run(pacer, Link(2 * mib, 2 * mib), 0, ticks = 30)
        pacer.onNetworkChanged(end)
        // Measuring again, with enough uploads to see the new connection.
        pacer.pace shouldBe Pace(config.measureSlots, null)
    }

    @Test
    fun `the cap never falls below a quarter of the measured throughput`() {
        val pacer = UploadPacer(NetworkUse.Polite, config, startMillis = 0)
        var now = 0L
        repeat(config.measureTicks) {
            now += tick
            pacer.onTick(now, 4 * mib * tick / 1000, 40)
        }
        // Shorter than the 10-minute window: after that a lasting delay becomes the new normal.
        repeat(100) {
            now += tick
            pacer.onTick(now, mib, 2_000)
        }
        pacer.pace.capBytesPerSecond shouldBe mib
        pacer.pace.slots shouldBe config.minSlots
    }
}
