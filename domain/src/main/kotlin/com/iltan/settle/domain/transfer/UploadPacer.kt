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
    /** Polite never sends faster than this share of the throughput measured uncapped. */
    val ceilingShare: Double = 0.75,
    /** Queueing delay above the network's quiet delay that counts as "others feel it". */
    val delayTargetMillis: Long = 100,
    /** The quiet delay is the lowest one seen in this long. */
    val baseDelayWindowMillis: Long = 10 * 60_000,
    /** The current delay is the lowest of this many recent samples, to ignore one-off spikes. */
    val delayFilterSamples: Int = 3,
    /** How long uploads run uncapped to measure what the connection can do. */
    val measureMillis: Long = 20_000,
    /** How often that measurement is repeated, since other traffic comes and goes. */
    val remeasureEveryMillis: Long = 30 * 60_000,
    /** How long a slot count runs before its throughput is judged. */
    val slotTrialMillis: Long = 30_000,
    /** A slot is kept only if total throughput rose by at least this share. */
    val slotGain: Double = 0.10,
    /** Multiplies the cap when the network's delay rises or Drive asks to slow down. */
    val backoff: Double = 0.7,
    /** Each calm tick raises the cap by this share of the measured throughput. */
    val recoveryShare: Double = 0.05,
    /** The cap never goes below this, so backup always makes some progress. */
    val minBytesPerSecond: Long = 64 * 1024,
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
 * - Polite also never exceeds a share of the throughput measured uncapped, which is measured again
 *   every so often.
 *
 * More uploads at once mainly hide Drive's fixed delay at the end of every file, so a slot is added
 * only while it raises total throughput.
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
    private var measuringSince: Long? = startMillis
    private var lastMeasureAt = startMillis
    private val delays = ArrayDeque<Pair<Long, Long>>()

    // The running trial of the current slot count, and the throughput of the one before.
    private var trialStart = startMillis
    private var trialBytes = 0L
    private var previousRate: Double? = null
    private var ramping = true
    /** The running trial is of a slot just added. */
    private var trialAdded = false

    val pace: Pace
        get() = Pace(slots, cap?.toLong())

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
        val rate = bytesSent * 1000.0 / config.tickMillis
        if (delayMillis != null) recordDelay(nowMillis, delayMillis)
        val crowded = queueingMillis()?.let { it > config.delayTargetMillis } ?: false

        trialBytes += bytesSent
        when {
            rateLimited -> {
                slots = (slots / 2).coerceAtLeast(config.minSlots)
                ramping = false
                restartTrial(nowMillis)
            }
            crowded && use == NetworkUse.Polite -> {
                if (slots > config.minSlots) slots--
                ramping = false
                restartTrial(nowMillis)
            }
            nowMillis - trialStart >= config.slotTrialMillis -> judgeTrial(nowMillis)
        }

        updateCap(nowMillis, rate, crowded, rateLimited)
        return pace
    }

    /** A round-trip sample taken before uploads start: the network's quiet delay. */
    fun onIdleDelay(nowMillis: Long, delayMillis: Long) = recordDelay(nowMillis, delayMillis)

    /** A different network: everything learned about the old one is dropped. */
    fun onNetworkChanged(nowMillis: Long) {
        delays.clear()
        peak = 0.0
        previousRate = null
        ramping = true
        slots = config.startSlots.coerceIn(config.minSlots, config.maxSlots)
        startMeasuring(nowMillis)
        restartTrial(nowMillis)
    }

    private fun updateCap(nowMillis: Long, rate: Double, crowded: Boolean, rateLimited: Boolean) {
        when (use) {
            NetworkUse.Unlimited -> cap = null
            is NetworkUse.Limit -> cap = use.bytesPerSecond.toDouble()
            NetworkUse.Polite -> {
                val since = measuringSince
                if (since != null) {
                    peak = maxOf(peak, rate)
                    if (crowded || rateLimited) {
                        // The connection is full already: what got through is the measurement.
                        endMeasuring(nowMillis, startAt = peak * config.backoff)
                    } else if (nowMillis - since >= config.measureMillis) {
                        endMeasuring(nowMillis, startAt = peak * config.ceilingShare)
                    }
                    return
                }
                if (nowMillis - lastMeasureAt >= config.remeasureEveryMillis) {
                    // Files and other traffic change: try more slots again as well.
                    ramping = true
                    previousRate = null
                    restartTrial(nowMillis)
                    startMeasuring(nowMillis)
                    return
                }
                val ceiling = peak * config.ceilingShare
                val current = cap ?: ceiling
                cap =
                    if (crowded || rateLimited) current * config.backoff
                    else minOf(ceiling, current + peak * config.recoveryShare)
                cap = cap?.coerceAtLeast(config.minBytesPerSecond.toDouble())
            }
        }
    }

    private fun startMeasuring(nowMillis: Long) {
        if (use != NetworkUse.Polite) return
        measuringSince = nowMillis
        lastMeasureAt = nowMillis
        peak = 0.0
        cap = null
    }

    private fun endMeasuring(nowMillis: Long, startAt: Double) {
        measuringSince = null
        lastMeasureAt = nowMillis
        cap = startAt.coerceAtLeast(config.minBytesPerSecond.toDouble())
    }

    /** Keeps a new slot only if it paid off; otherwise goes back and stops adding. */
    private fun judgeTrial(nowMillis: Long) {
        val rate = trialBytes * 1000.0 / (nowMillis - trialStart)
        val before = previousRate
        if (trialAdded && before != null && rate < before * (1 + config.slotGain)) {
            slots--
            ramping = false
            restartTrial(nowMillis)
            return
        }
        previousRate = rate
        restartTrial(nowMillis)
        if (ramping && slots < config.maxSlots) {
            slots++
            trialAdded = true
        }
    }

    private fun restartTrial(nowMillis: Long) {
        trialStart = nowMillis
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
