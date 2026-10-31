package com.iltan.settle.domain.transfer

/** How much of the network backup may take (R23). */
sealed interface NetworkUse {
    /** Leaves room for others: backs off when the network's delay rises, stays below a share. */
    data object Polite : NetworkUse

    /** At most this many bytes per second, whatever else happens on the network. */
    data class Limit(val bytesPerSecond: Long) : NetworkUse

    /** No cap; only the number of uploads adapts. */
    data object Unlimited : NetworkUse
}

/** The pacer's tuning; defaults come from measurements on the phone (ADR-014). */
data class PacerConfig(
    /** How often the pacer is fed and decides. */
    val tickMillis: Long = 5_000,
    val minSlots: Int = 1,
    val maxSlots: Int = 6,
    val startSlots: Int = 2,
    /**
     * Uploads at once while measuring what the connection can do: enough that small files' fixed
     * per-file delay doesn't hide the connection's speed (4 reached 95% of the line on the phone).
     */
    val measureSlots: Int = 4,
    /** Polite never sends faster than this share of the throughput measured uncapped. */
    val ceilingShare: Double = 0.75,
    /** Nor slower than this share of it, so backup keeps moving whatever the delay says. */
    val floorShare: Double = 0.25,
    /** Queueing delay above the network's quiet delay that counts as "others feel it". */
    val delayTargetMillis: Long = 100,
    /** The quiet delay is the lowest one seen in this long. */
    val baseDelayWindowMillis: Long = 10 * 60_000,
    /** The current delay is the lowest of this many recent samples, to ignore one-off spikes. */
    val delayFilterSamples: Int = 3,
    /** Ticks with traffic that a measurement of the connection takes. */
    val measureTicks: Int = 4,
    /** How often that measurement is repeated, since other traffic comes and goes. */
    val remeasureEveryMillis: Long = 30 * 60_000,
    /** Ticks with traffic a slot count runs before its throughput is judged. */
    val slotTrialTicks: Int = 6,
    /** A slot is kept only if total throughput rose by at least this share. */
    val slotGain: Double = 0.10,
    /** Multiplies the cap when the network's delay rises or Drive asks to slow down. */
    val backoff: Double = 0.7,
    /** Each calm tick raises the cap by this share of the measured throughput. */
    val recoveryShare: Double = 0.05,
    /** The cap never goes below this, so backup always makes some progress. */
    val minBytesPerSecond: Long = 256 * 1024,
)

/** What uploads may do until the next tick. */
data class Pace(
    /** How many uploads run at once. */
    val slots: Int,
    /** Bytes per second for all uploads together; null means no cap. */
    val capBytesPerSecond: Long?,
)

/**
 * Decides how many uploads run at once and how fast they may send, from what the network shows
 * (R23, ADR-014). The method is LEDBAT's (RFC 6817), the one behind BitTorrent's µTP and Windows
 * BITS, applied above HTTP:
 * - a connection's quiet delay is the lowest round trip seen lately; delay above it is queueing in
 *   the router, which everyone else on the network feels as lag;
 * - while that queueing delay stays under a target the cap grows slowly, and once it rises the cap
 *   shrinks at once;
 * - Polite also stays between a quarter and three quarters of the throughput measured uncapped,
 *   which is measured again every so often.
 *
 * More uploads at once mainly hide Drive's fixed delay at the end of every file, so a slot is added
 * only while it raises total throughput. Ticks without traffic change nothing: there is nothing to
 * learn from them.
 *
 * Pure and fed with time, so whole network histories can be replayed in tests.
 */
class UploadPacer(
    private val use: NetworkUse,
    private val config: PacerConfig = PacerConfig(),
    startMillis: Long,
) {
    private var slots = config.startSlots.coerceIn(config.minSlots, config.maxSlots)
    private var cap: Double? = null
    private var peak = 0.0
    /** Ticks with traffic left in the current measurement; 0 when not measuring. */
    private var measuring = if (use == NetworkUse.Polite) config.measureTicks else 0
    private var lastMeasureAt = startMillis
    private val delays = ArrayDeque<Pair<Long, Long>>()

    // The running trial of the current slot count, and the throughput of the one before.
    private var trialTicks = 0
    private var trialBytes = 0L
    private var previousRate: Double? = null
    private var ramping = true
    /** The running trial is of a slot just added. */
    private var trialAdded = false

    val pace: Pace
        get() =
            when {
                measuring > 0 ->
                    Pace(maxOf(slots, config.measureSlots).coerceAtMost(config.maxSlots), null)
                use is NetworkUse.Limit -> Pace(slots, use.bytesPerSecond)
                else -> Pace(slots, cap?.toLong())
            }

    /**
     * One tick: [bytesSent] since the last tick, an optional round-trip [delayMillis] sample, and
     * whether Drive answered "slow down" during it.
     */
    fun onTick(
        nowMillis: Long,
        bytesSent: Long,
        delayMillis: Long?,
        rateLimited: Boolean = false,
    ): Pace {
        if (delayMillis != null) recordDelay(nowMillis, delayMillis)
        if (bytesSent <= 0 && !rateLimited) return pace

        val rate = bytesSent * 1000.0 / config.tickMillis
        val crowded = use == NetworkUse.Polite && (queueingMillis() ?: 0) > config.delayTargetMillis

        when {
            rateLimited -> {
                slots = (slots / 2).coerceAtLeast(config.minSlots)
                stopRamping()
            }
            crowded -> {
                if (slots > config.minSlots) slots--
                stopRamping()
            }
            measuring == 0 -> {
                trialTicks++
                trialBytes += bytesSent
                if (trialTicks >= config.slotTrialTicks) judgeTrial()
            }
        }
        if (use == NetworkUse.Polite) updateCap(nowMillis, rate, crowded || rateLimited)
        return pace
    }

    /** A round-trip sample taken before uploads start: the network's quiet delay. */
    fun onIdleDelay(nowMillis: Long, delayMillis: Long) = recordDelay(nowMillis, delayMillis)

    /** A different network: everything learned about the old one is dropped. */
    fun onNetworkChanged(nowMillis: Long) {
        delays.clear()
        slots = config.startSlots.coerceIn(config.minSlots, config.maxSlots)
        startOver(nowMillis)
    }

    private fun updateCap(nowMillis: Long, rate: Double, slowDown: Boolean) {
        if (measuring > 0) {
            peak = maxOf(peak, rate)
            measuring--
            when {
                // The connection is full already: what got through is the measurement.
                slowDown -> endMeasuring(peak * config.backoff)
                measuring == 0 -> endMeasuring(peak * config.ceilingShare)
            }
            return
        }
        if (nowMillis - lastMeasureAt >= config.remeasureEveryMillis) {
            // Files and other traffic change: try more slots again as well.
            startOver(nowMillis)
            return
        }
        val current = cap ?: (peak * config.ceilingShare)
        val next =
            if (slowDown) current * config.backoff
            else minOf(peak * config.ceilingShare, current + peak * config.recoveryShare)
        cap = next.coerceAtLeast(floor())
    }

    private fun startOver(nowMillis: Long) {
        lastMeasureAt = nowMillis
        peak = 0.0
        cap = null
        measuring = if (use == NetworkUse.Polite) config.measureTicks else 0
        previousRate = null
        ramping = true
        restartTrial()
    }

    private fun endMeasuring(startAt: Double) {
        measuring = 0
        cap = startAt.coerceAtLeast(floor())
        restartTrial()
    }

    private fun floor() = maxOf(config.minBytesPerSecond.toDouble(), peak * config.floorShare)

    /** Keeps a new slot only if it paid off; otherwise goes back and stops adding. */
    private fun judgeTrial() {
        val rate = trialBytes * 1000.0 / (trialTicks * config.tickMillis)
        val before = previousRate
        val added = trialAdded
        restartTrial()
        if (added && before != null && rate < before * (1 + config.slotGain)) {
            slots--
            ramping = false
            return
        }
        previousRate = rate
        if (ramping && slots < config.maxSlots) {
            slots++
            trialAdded = true
        }
    }

    private fun stopRamping() {
        ramping = false
        restartTrial()
    }

    private fun restartTrial() {
        trialTicks = 0
        trialBytes = 0
        trialAdded = false
    }

    private fun recordDelay(nowMillis: Long, delayMillis: Long) {
        delays.addLast(nowMillis to delayMillis)
        while (delays.first().first < nowMillis - config.baseDelayWindowMillis) delays.removeFirst()
    }

    /** Current delay minus the quiet one; null until there are enough samples to tell. */
    private fun queueingMillis(): Long? {
        if (delays.size < config.delayFilterSamples) return null
        val base = delays.minOf { it.second }
        val current = delays.takeLast(config.delayFilterSamples).minOf { it.second }
        return current - base
    }
}
