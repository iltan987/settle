package com.iltan.settle.data.backup

import com.iltan.settle.data.db.BackupDao
import com.iltan.settle.data.db.UploadRetryEntity
import com.iltan.settle.data.drive.UploadMeter
import com.iltan.settle.domain.backup.BackupCandidate
import com.iltan.settle.domain.backup.BackupEngine
import com.iltan.settle.domain.backup.BackupPause
import com.iltan.settle.domain.backup.BackupResult
import com.iltan.settle.domain.backup.UploadProblem
import com.iltan.settle.domain.backup.checksumRecheckAtMillis
import com.iltan.settle.domain.backup.retryAtMillis
import com.iltan.settle.domain.transfer.PacerConfig
import com.iltan.settle.domain.transfer.UploadPacer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Bytes sent so far of one upload. */
data class UploadProgress(val sentBytes: Long, val totalBytes: Long)

/** How a backup the user asked for ended, for the UI to report. */
sealed interface BackupOutcome {
    val itemId: String

    data class Done(override val itemId: String, val result: BackupResult) : BackupOutcome

    /** Media location access is missing, so only a redacted copy could be read. */
    data class NeedsOriginalAccess(override val itemId: String) : BackupOutcome
}

/** Which items a run covers. */
enum class RunKind {
    /** Everything the backup rules call ready: a job on Wi-Fi and the charger. */
    AUTOMATIC,
    /** Only what the user asked for: a user-initiated job that starts at once. */
    REQUESTED,
}

enum class RunEnd {
    /** Nothing left that this run covers. */
    DONE,
    /** Backup can't go on until something changes: see the plan's pause. */
    PAUSED,
}

/**
 * Runs uploads, several at once, as the [UploadPacer] allows (R23, ADR-014). Called from a job;
 * cancelling the job cancels the run, and every upload continues later from the server's offset
 * because the engine saves each step.
 *
 * One run at a time: a second job waits and then usually finds its items done.
 */
@OptIn(ExperimentalCoroutinesApi::class) // select's onTimeout
class BackupRunner(
    private val dao: BackupDao,
    private val planner: BackupPlanner,
    private val preferences: BackupPreferences,
    /** A new engine for one upload slot; engines running at once share the folder lock. */
    private val newEngine: (folderLock: Mutex) -> BackupEngine,
    private val meter: UploadMeter,
    /** The network's round trip to Drive in milliseconds, for the pacer; null if it failed. */
    private val measureDelay: suspend () -> Long?,
    private val clock: () -> Long = System::currentTimeMillis,
    private val pacerConfig: PacerConfig = PacerConfig(),
    private val workerDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val log: (String) -> Unit = {},
) {
    private val runLock = Mutex()
    private val pacerLock = Any()
    @Volatile private var pacer: UploadPacer? = null
    private var network: Any? = null

    private val _progress = MutableStateFlow<Map<String, UploadProgress>>(emptyMap())
    /** Uploads under way, by item ID. */
    val progress: StateFlow<Map<String, UploadProgress>> = _progress.asStateFlow()

    private val _outcomes = MutableSharedFlow<BackupOutcome>(extraBufferCapacity = 16)
    val outcomes: SharedFlow<BackupOutcome> = _outcomes.asSharedFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    /**
     * The job's network, as Android reports it. Android calls this for capability changes too, so
     * the pacer starts over only when the network itself is a different one.
     */
    fun onNetworkChanged(network: Any?) {
        synchronized(pacerLock) {
            if (network == this.network) return
            val first = this.network == null
            this.network = network
            if (!first) pacer?.onNetworkChanged(clock())
        }
    }

    suspend fun run(kind: RunKind): RunEnd = runLock.withLock {
        _running.value = true
        try {
            runLocked(kind)
        } finally {
            pacer = null
            meter.setCap(null)
            _running.value = false
        }
    }

    private class Finished(
        val item: BackupCandidate,
        val slot: BackupEngine,
        val result: BackupResult,
    )

    private suspend fun runLocked(kind: RunKind): RunEnd = coroutineScope {
        var plan = planner.plan()
        if (plan.pause != null) return@coroutineScope RunEnd.PAUSED
        val start = clock()
        val pacer = UploadPacer(preferences.current().networkUse, pacerConfig, start)
        this@BackupRunner.pacer = pacer
        // The network's quiet delay, before the first byte goes out.
        repeat(IDLE_SAMPLES) {
            pingMillis()?.let { ms -> synchronized(pacerLock) { pacer.onIdleDelay(clock(), ms) } }
        }
        meter.takeSent()
        meter.takeRateLimited()
        meter.setCap(pacer.pace.capBytesPerSecond)

        val ticker = launch {
            while (true) {
                delay(pacerConfig.tickMillis)
                val delayMs = pingMillis()
                val pace =
                    synchronized(pacerLock) {
                        pacer.onTick(clock(), meter.takeSent(), delayMs, meter.takeRateLimited())
                    }
                meter.setCap(pace.capBytesPerSecond)
                log(
                    "Pace: ${pace.slots} slots, cap ${pace.capBytesPerSecond ?: "none"} B/s, delay $delayMs ms"
                )
            }
        }

        val folderLock = Mutex()
        val idleEngines = ArrayDeque<BackupEngine>()
        val finished = Channel<Finished>(Channel.UNLIMITED)
        val tried = mutableSetOf<String>()
        var queue = ArrayDeque(plan.queue.covered(kind))
        var running = 0
        var lastStart = 0L
        var end: RunEnd? = null

        while (true) {
            if (end == null && queue.isEmpty() && running == 0) {
                plan = planner.plan()
                queue = ArrayDeque(plan.queue.covered(kind).filter { it.itemId.value !in tried })
                if (plan.pause != null) end = RunEnd.PAUSED
                else if (queue.isEmpty()) end = RunEnd.DONE
            }
            val slots = synchronized(pacerLock) { pacer.pace.slots }
            if (end == null && queue.isNotEmpty() && running < slots) {
                // File starts are writes Drive limits per account; stay well below that.
                val wait = lastStart + MIN_START_GAP_MILLIS - clock()
                if (wait > 0) delay(wait)
                val item = queue.removeFirst()
                tried += item.itemId.value
                val engine = idleEngines.removeFirstOrNull() ?: newEngine(folderLock)
                running++
                lastStart = clock()
                launch(workerDispatcher) { finished.send(upload(item, engine)) }
                continue
            }
            if (running == 0) break
            // Wait for an upload to end, or wake up to see whether the pacer allows another.
            // select takes either the result or the timeout, never loses a result to the timeout.
            val done =
                select<Finished?> {
                    finished.onReceive { it }
                    onTimeout(WAKE_MILLIS) { null }
                } ?: continue
            running--
            idleEngines.addLast(done.slot)
            if (handle(kind, done)) end = RunEnd.PAUSED
        }
        ticker.cancel()
        log("Run ${kind.name} ended: $end after ${(clock() - start) / 1000} s")
        end ?: RunEnd.DONE
    }

    private fun List<BackupCandidate>.covered(kind: RunKind) =
        if (kind == RunKind.REQUESTED) filter { it.requestedAtMillis != null } else this

    private suspend fun upload(item: BackupCandidate, engine: BackupEngine): Finished {
        val id = item.itemId.value
        _progress.update { it + (id to UploadProgress(0, item.sizeBytes)) }
        return try {
            val result =
                engine.backUp(item.itemId) { sent, total ->
                    _progress.update { it + (id to UploadProgress(sent, total)) }
                }
            Finished(item, engine, result)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("$id failed unexpectedly: $e")
            Finished(item, engine, BackupResult.Stopped(UploadProblem.FAILED))
        } finally {
            _progress.update { it - id }
        }
    }

    /** Records how an upload ended; returns true when backup as a whole must pause. */
    private suspend fun handle(kind: RunKind, done: Finished): Boolean {
        val id = done.item.itemId.value
        val result = done.result
        log("$id: $result")
        if (done.item.requestedAtMillis != null) _outcomes.emit(BackupOutcome.Done(id, result))
        val requested = kind == RunKind.REQUESTED || done.item.requestedAtMillis != null
        val problem =
            when (result) {
                is BackupResult.Verified -> return false
                is BackupResult.AwaitingChecksum -> {
                    // Drive hasn't reported the SHA-256 yet: ask again later, not in the next run.
                    val checks = (dao.retry(id)?.attempts ?: 0) + 1
                    dao.saveRetry(
                        UploadRetryEntity(id, checks, checksumRecheckAtMillis(checks, clock()))
                    )
                    if (requested) dao.deleteRequest(id)
                    return false
                }
                is BackupResult.Stopped -> result.problem
            }
        val blocking =
            when (problem) {
                UploadProblem.AUTH_NEEDED -> BackupPause.AUTH_NEEDED
                UploadProblem.QUOTA_FULL -> BackupPause.QUOTA_FULL
                else -> null
            }
        if (blocking != null) {
            preferences.block(blocking)
            return true
        }
        val attempts = (dao.retry(id)?.attempts ?: 0) + 1
        dao.saveRetry(UploadRetryEntity(id, attempts, retryAtMillis(problem, attempts, clock())))
        // The user hears how it ended; asking again starts a new try.
        if (requested) dao.deleteRequest(id)
        return false
    }

    private suspend fun pingMillis(): Long? =
        try {
            measureDelay()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }

    private companion object {
        const val IDLE_SAMPLES = 3
        const val MIN_START_GAP_MILLIS = 500L
        const val WAKE_MILLIS = 1_000L
    }
}
