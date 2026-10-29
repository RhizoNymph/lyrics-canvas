package dev.nymph.lyrics.logic.status

import dev.nymph.lyrics.model.status.HealthSignal
import dev.nymph.lyrics.model.status.Outage
import dev.nymph.lyrics.model.status.UnreachableReason

/** Timing of the backend health monitor (same values as the web client). Milliseconds. */
object HealthPolicy {
    /** Consecutive failures, the last one from a probe, before the backend counts as down. */
    const val FAILURE_THRESHOLD: Int = 2

    /** Probe interval while healthy (or not yet known). */
    const val HEALTHY_PROBE_MILLIS: Long = 15_000

    /** Quick confirming probe after a first failure. */
    const val SUSPECT_PROBE_MILLIS: Long = 3_000

    /** First probe interval once down; doubles per further failure up to [DOWN_BACKOFF_MAX_MILLIS]. */
    const val DOWN_BACKOFF_BASE_MILLIS: Long = 5_000
    const val DOWN_BACKOFF_MAX_MILLIS: Long = 60_000

    /** How long "back online" stays up after recovery. */
    const val RECOVERY_DISPLAY_MILLIS: Long = 5_000
}

/** Where an observation came from. Passive failures alone never confirm an outage. */
enum class ObservationSource {
    /** `GET /api/health` from the monitor. */
    PROBE,

    /** A regular API call made by a screen. */
    PASSIVE,
}

/**
 * What the app believes about the backend. Times are epoch milliseconds from the monitor's
 * clock. Transitions only happen through [reduce].
 */
sealed interface HealthState {
    /** Nothing observed yet (app just started). No banner. */
    data object Unknown : HealthState

    data object Healthy : HealthState

    /** Failures seen but not confirmed. Not shown; probed again after [HealthPolicy.SUSPECT_PROBE_MILLIS]. */
    data class Suspect(val outage: Outage, val failures: Int) : HealthState {
        init {
            require(failures in 1 until HealthPolicy.FAILURE_THRESHOLD) { "Suspect needs 1..${HealthPolicy.FAILURE_THRESHOLD - 1} failures, got $failures" }
        }
    }

    /**
     * A confirmed outage, shown. [since] is when it was confirmed and identifies the incident: it
     * is kept while the outage lasts, even when its kind changes.
     */
    data class Down(val outage: Outage, val failures: Int, val since: Long) : HealthState {
        init {
            require(failures >= HealthPolicy.FAILURE_THRESHOLD) { "Down needs >= ${HealthPolicy.FAILURE_THRESHOLD} failures, got $failures" }
        }
    }

    /** Recovered from [Down] at [at]; shown until [HealthEvent.Settle] for that same [at]. */
    data class BackOnline(val at: Long, val downSince: Long) : HealthState
}

sealed interface HealthEvent {
    data class Observed(val signal: HealthSignal, val source: ObservationSource, val now: Long) : HealthEvent

    /** The system reports no network at all: definitive, confirmed at once. */
    data class ConnectivityLost(val now: Long) : HealthEvent

    /** The "back online" notice that started at [at] has been shown long enough. */
    data class Settle(val at: Long) : HealthEvent
}

fun HealthState.reduce(event: HealthEvent): HealthState = when (event) {
    is HealthEvent.Settle -> if (this is HealthState.BackOnline && at == event.at) HealthState.Healthy else this
    is HealthEvent.ConnectivityLost -> {
        val offline = Outage.Unreachable(UnreachableReason.OFFLINE)
        when (this) {
            is HealthState.Down -> copy(outage = offline)
            else -> HealthState.Down(offline, failures = HealthPolicy.FAILURE_THRESHOLD, since = event.now)
        }
    }
    is HealthEvent.Observed -> when (val signal = event.signal) {
        HealthSignal.Up -> when (this) {
            is HealthState.Down -> HealthState.BackOnline(at = event.now, downSince = since)
            is HealthState.BackOnline -> this
            HealthState.Unknown, HealthState.Healthy, is HealthState.Suspect -> HealthState.Healthy
        }
        is HealthSignal.Failed -> onFailure(signal.outage, confirming = event.source == ObservationSource.PROBE, now = event.now)
    }
}

private fun HealthState.onFailure(outage: Outage, confirming: Boolean, now: Long): HealthState = when (this) {
    HealthState.Unknown, HealthState.Healthy, is HealthState.BackOnline -> HealthState.Suspect(outage, failures = 1)
    // A burst of parallel calls failing together is one observation: only a probe confirms.
    is HealthState.Suspect -> if (!confirming) {
        this
    } else {
        val count = failures + 1
        if (count >= HealthPolicy.FAILURE_THRESHOLD) HealthState.Down(outage, count, since = now) else HealthState.Suspect(outage, count)
    }
    is HealthState.Down -> if (confirming) copy(outage = outage, failures = saturatingInc(failures)) else this
}

private fun saturatingInc(n: Int): Int = if (n == Int.MAX_VALUE) n else n + 1

/** How long the monitor waits before the next probe in this state (exponential backoff while down). */
fun HealthState.nextProbeDelayMillis(): Long = when (this) {
    HealthState.Unknown, HealthState.Healthy, is HealthState.BackOnline -> HealthPolicy.HEALTHY_PROBE_MILLIS
    is HealthState.Suspect -> HealthPolicy.SUSPECT_PROBE_MILLIS
    is HealthState.Down -> {
        val doublings = (failures - HealthPolicy.FAILURE_THRESHOLD).coerceIn(0, 30)
        (HealthPolicy.DOWN_BACKOFF_BASE_MILLIS shl doublings).coerceAtMost(HealthPolicy.DOWN_BACKOFF_MAX_MILLIS)
    }
}

/** True when going from [before] to this state entered [HealthState.Suspect]: the next probe should come at once. */
fun HealthState.becameSuspectFrom(before: HealthState): Boolean = this is HealthState.Suspect && before !is HealthState.Suspect
