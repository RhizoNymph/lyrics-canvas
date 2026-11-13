package dev.nymph.lyrics.sync.engine

import dev.nymph.lyrics.model.error.ApiError
import dev.nymph.lyrics.model.error.ApiResult
import dev.nymph.lyrics.sync.SyncLog
import dev.nymph.lyrics.sync.conflict.ConflictAlert
import dev.nymph.lyrics.sync.debug
import dev.nymph.lyrics.sync.info
import dev.nymph.lyrics.sync.outbox.Batch
import dev.nymph.lyrics.sync.snapshot.SnapshotMerge
import dev.nymph.lyrics.sync.store.LocalStore
import dev.nymph.lyrics.sync.warn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Why the engine runs a pass. */
enum class Wake {
    /** A local change was queued. */
    EDIT,

    /** The app came to the foreground: send, then refresh from the server. */
    FOREGROUND,

    /** The health monitor saw the server come back. */
    HEALTHY,

    /** A conflict was resolved (or the first load needs a snapshot): refresh from the server. */
    REFRESH,

    /** The periodic retry while something is pending. */
    TICK,
    ;

    /** These end a backoff early and fetch a snapshot after sending. */
    val refreshes: Boolean get() = this == FOREGROUND || this == HEALTHY || this == REFRESH
}

/** Timings. */
data class SyncPolicy(
    /** Retry pending changes this often while the app is in the foreground. */
    val tickMillis: Long = 30_000,
    val backoffBaseMillis: Long = 2_000,
    val backoffMaxMillis: Long = 60_000,
) {
    /** After [attempt] consecutive transient failures (1-based): base, doubled per attempt, capped. */
    fun backoffMillis(attempt: Int): Long {
        val shift = (attempt - 1).coerceIn(0, 20)
        return minOf(backoffMaxMillis, backoffBaseMillis shl shift)
    }
}

/** What the engine is doing (the status indicator's "Syncing"). */
enum class EngineActivity { IDLE, SENDING, REFRESHING }

/** The latest snapshot fetch: [generation] counts attempts; [error] is null when it was merged. */
data class SnapshotAttempt(val generation: Long, val error: ApiError?)

/** What repositories need from the engine. */
interface SyncTrigger {
    fun wake(reason: Wake)

    /**
     * Wait until the copy holds a snapshot (asking for one); the failure when the fetch failed
     * first (offline on a fresh install). Null: there is one.
     */
    suspend fun awaitSnapshot(): ApiError?

    object None : SyncTrigger {
        override fun wake(reason: Wake) = Unit

        override suspend fun awaitSnapshot(): ApiError? = null
    }
}

/**
 * The one coroutine that talks to the server for the outbox ([run]). It waits on a channel of
 * [Wake]s (edits, foreground, the server back up, resolved conflicts) or the periodic tick, sends
 * operations in order through [sender], applies each answer to the [store] ([Outcomes]), and
 * after the outbox drains fetches a snapshot and merges it ([SnapshotMerge]). Transient failures
 * back off (doubling to a minute) and keep their place. No state is shared outside the store.
 */
class SyncEngine(
    private val store: LocalStore,
    private val sender: OpSender,
    private val log: SyncLog = SyncLog.None,
    private val policy: SyncPolicy = SyncPolicy(),
    private val foreground: StateFlow<Boolean> = MutableStateFlow(true),
) : SyncTrigger {
    private val wakes = Channel<Wake>(Channel.UNLIMITED)
    private val alertChannel = Channel<ConflictAlert>(Channel.BUFFERED)
    private val _activity = MutableStateFlow(EngineActivity.IDLE)
    private val _snapshot = MutableStateFlow(SnapshotAttempt(0, null))

    /** Newly parked changes, each once (for the snackbar). */
    val alerts: Flow<ConflictAlert> = alertChannel.receiveAsFlow()

    val activity: StateFlow<EngineActivity> = _activity.asStateFlow()

    val snapshotAttempts: StateFlow<SnapshotAttempt> = _snapshot.asStateFlow()

    override fun wake(reason: Wake) {
        wakes.trySend(reason)
    }

    override suspend fun awaitSnapshot(): ApiError? {
        if (store.ensureLoaded().hasSnapshot) return null
        val before = _snapshot.value.generation
        wake(Wake.REFRESH)
        return _snapshot.first { it.generation > before }.error
    }

    /** Runs until cancelled. */
    suspend fun run() {
        store.ensureLoaded()
        var failures = 0
        var reason = Wake.FOREGROUND
        while (true) {
            failures = if (pass(reason)) 0 else failures + 1
            reason = if (failures > 0) waitBackoff(policy.backoffMillis(failures)) else waitIdle()
        }
    }

    /** During a backoff only a refreshing wake (server back, foreground) ends it early. */
    private suspend fun waitBackoff(millis: Long): Wake =
        withTimeoutOrNull(millis) {
            var wake = wakes.receive()
            while (!wake.refreshes) wake = wakes.receive()
            wake
        } ?: Wake.TICK

    private suspend fun waitIdle(): Wake {
        val tick = foreground.value && store.data.value.outbox.unsentCount > 0
        return if (tick) withTimeoutOrNull(policy.tickMillis) { wakes.receive() } ?: Wake.TICK else wakes.receive()
    }

    /** One pass: send what can be sent; refresh when it drained. False after a transient failure. */
    internal suspend fun pass(reason: Wake): Boolean {
        _activity.value = EngineActivity.SENDING
        try {
            val drain = drain()
            if (drain == null) return false
            val wantsSnapshot = drain.sent > 0 || drain.refresh || reason.refreshes || !store.data.value.hasSnapshot
            if (wantsSnapshot) {
                _activity.value = EngineActivity.REFRESHING
                return refresh()
            }
            return true
        } finally {
            _activity.value = EngineActivity.IDLE
        }
    }

    private data class Drained(val sent: Int, val refresh: Boolean)

    /** Null when a transient failure stopped it. */
    private suspend fun drain(): Drained? {
        var sent = 0
        var refresh = false
        while (true) {
            val batch = store.update { d ->
                val b = d.outbox.nextBatch()
                if (b == null) d to null else d.withOutbox(d.outbox.markInFlight(b.ids)) to b
            } ?: return Drained(sent, refresh)
            val outcome = sendOrRequeue(batch)
            val applied = store.update { d -> Outcomes.apply(d, batch, outcome).let { it.data to it } }
            log.debug("sync.op_sent", "op" to batch.first.op::class.simpleName, "count" to batch.entries.size, "outcome" to outcome::class.simpleName)
            applied.alerts.forEach {
                log.warn("sync.conflict_parked", "op_id" to it.opId, "conflict" to it.conflict::class.simpleName)
                alertChannel.trySend(it)
            }
            if (applied.transient) {
                log.info("sync.backoff", "reason" to (outcome as? SendOutcome.Transient)?.error?.message)
                return null
            }
            refresh = refresh || applied.refresh
            sent += batch.entries.size
        }
    }

    /** A cancelled send (the app going away) puts the batch back as pending. */
    private suspend fun sendOrRequeue(batch: Batch): SendOutcome = try {
        sender.send(batch)
    } catch (e: CancellationException) {
        withContext(NonCancellable) { store.update { d -> d.withOutbox(d.outbox.requeue(batch.ids)) to Unit } }
        throw e
    }

    /** Fetch and merge a snapshot (claiming the copy for the account first, if it has none). */
    private suspend fun refresh(): Boolean {
        if (store.data.value.account == null) {
            when (val me = sender.me()) {
                is ApiResult.Success -> store.claim(me.value.id)
                is ApiResult.Failure -> return snapshotFailed(me.error)
            }
        }
        return when (val result = sender.snapshot()) {
            is ApiResult.Success -> {
                store.update { d -> SnapshotMerge.merge(d, result.value) to Unit }
                _snapshot.value = SnapshotAttempt(_snapshot.value.generation + 1, null)
                log.info("sync.snapshot_merged", "server_time" to result.value.serverTime, "outbox" to store.data.value.outbox.entries.size)
                true
            }
            is ApiResult.Failure -> snapshotFailed(result.error)
        }
    }

    private fun snapshotFailed(error: ApiError): Boolean {
        _snapshot.value = SnapshotAttempt(_snapshot.value.generation + 1, error)
        log.warn("sync.snapshot_failed", "reason" to error.message)
        // A refused snapshot (e.g. 404 from an older server) is not worth a backoff loop.
        return error !is ApiError.Network && !(error is ApiError.Server && error.status >= 500)
    }
}
