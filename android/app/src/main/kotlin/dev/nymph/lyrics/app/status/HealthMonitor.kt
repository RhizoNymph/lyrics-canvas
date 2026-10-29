package dev.nymph.lyrics.app.status

import dev.nymph.lyrics.data.ApiCallObserver
import dev.nymph.lyrics.logic.status.HealthEvent
import dev.nymph.lyrics.logic.status.HealthPolicy
import dev.nymph.lyrics.logic.status.HealthState
import dev.nymph.lyrics.logic.status.ObservationSource
import dev.nymph.lyrics.logic.status.becameSuspectFrom
import dev.nymph.lyrics.logic.status.nextProbeDelayMillis
import dev.nymph.lyrics.logic.status.reduce
import dev.nymph.lyrics.model.status.HealthSignal
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Owns the app's [HealthState]. Inputs feed the pure state machine
 * ([dev.nymph.lyrics.logic.status.reduce]):
 *
 * - [run], while the app is in the foreground: probes `/api/health` at once, then after
 *   [nextProbeDelayMillis] (regular, quick confirm, or backoff), or sooner on [wakeUp].
 * - [onCallOutcome], from every regular [dev.nymph.lyrics.data.ApiClient] call at any time
 *   (passive). A failure that makes the backend suspect wakes the probe loop to confirm it.
 * - [connectivityLost], when the system reports no network.
 *
 * The state is a [MutableStateFlow] updated by compare-and-set, so reports from any thread are safe.
 */
class HealthMonitor(
    private val probe: suspend () -> HealthSignal,
    private val clock: () -> Long = System::currentTimeMillis,
) : ApiCallObserver {
    private val mutableState = MutableStateFlow<HealthState>(HealthState.Unknown)
    val state: StateFlow<HealthState> = mutableState.asStateFlow()

    /** Probe now instead of waiting out the current interval. Conflated: many wakes, one probe. */
    private val wake = Channel<Unit>(Channel.CONFLATED)

    override fun onCallOutcome(signal: HealthSignal) {
        val (before, after) = apply(HealthEvent.Observed(signal, ObservationSource.PASSIVE, clock()))
        if (after.becameSuspectFrom(before)) wake.trySend(Unit)
    }

    /** Resume or connectivity regained: probe at once. */
    fun wakeUp() {
        wake.trySend(Unit)
    }

    /** The system reports no network: unreachable at once, then keep probing to notice recovery. */
    fun connectivityLost() {
        apply(HealthEvent.ConnectivityLost(clock()))
    }

    /** Probe until cancelled (the caller cancels when the app leaves the foreground). */
    suspend fun run() {
        coroutineScope {
            launch { settleBackOnline() }
            while (true) {
                apply(HealthEvent.Observed(probe(), ObservationSource.PROBE, clock()))
                // A wake that arrived during the probe is answered by the probe that just finished.
                wake.tryReceive()
                withTimeoutOrNull(state.value.nextProbeDelayMillis()) { wake.receive() }
            }
        }
    }

    private suspend fun settleBackOnline() {
        state.collectLatest { current ->
            if (current !is HealthState.BackOnline) return@collectLatest
            delay((current.at + HealthPolicy.RECOVERY_DISPLAY_MILLIS - clock()).coerceAtLeast(0))
            apply(HealthEvent.Settle(current.at))
        }
    }

    /** Apply [event] atomically; returns the state before and after. */
    private fun apply(event: HealthEvent): Pair<HealthState, HealthState> {
        while (true) {
            val before = mutableState.value
            val after = before.reduce(event)
            if (mutableState.compareAndSet(before, after)) return before to after
        }
    }
}
