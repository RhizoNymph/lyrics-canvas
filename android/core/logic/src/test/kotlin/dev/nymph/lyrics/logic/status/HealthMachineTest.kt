package dev.nymph.lyrics.logic.status

import dev.nymph.lyrics.logic.status.HealthPolicy.FAILURE_THRESHOLD
import dev.nymph.lyrics.logic.status.ObservationSource.PASSIVE
import dev.nymph.lyrics.logic.status.ObservationSource.PROBE
import dev.nymph.lyrics.model.status.DownCause
import dev.nymph.lyrics.model.status.HealthSignal
import dev.nymph.lyrics.model.status.Outage
import dev.nymph.lyrics.model.status.UnreachableReason
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class HealthMachineTest {
    private val down502 = Outage.BackendDown(DownCause.Http(502))
    private val network = Outage.Unreachable(UnreachableReason.NETWORK)
    private val offline = Outage.Unreachable(UnreachableReason.OFFLINE)
    private val up = HealthSignal.Up

    private fun HealthState.probe(outage: Outage?, now: Long) =
        reduce(HealthEvent.Observed(outage?.let(HealthSignal::Failed) ?: up, PROBE, now))

    private fun HealthState.passive(outage: Outage?, now: Long) =
        reduce(HealthEvent.Observed(outage?.let(HealthSignal::Failed) ?: up, PASSIVE, now))

    private val failing = HealthState.Healthy.probe(down502, 0).probe(down502, 100)

    @Test
    fun `starts unknown and a first ok is healthy`() {
        assertEquals(HealthState.Healthy, HealthState.Unknown.probe(null, 0))
    }

    @Test
    fun `one probe failure is only suspect, at startup too`() {
        assertEquals(HealthState.Suspect(down502, 1), HealthState.Healthy.probe(down502, 10))
        assertEquals(HealthState.Suspect(network, 1), HealthState.Unknown.probe(network, 10))
    }

    @Test
    fun `two consecutive probe failures confirm, stamped with the confirming time and latest outage`() {
        assertEquals(HealthState.Down(down502, failures = FAILURE_THRESHOLD, since = 200), HealthState.Healthy.probe(network, 100).probe(down502, 200))
    }

    @Test
    fun `a success between failures resets the count`() {
        assertEquals(HealthState.Suspect(down502, 1), HealthState.Healthy.probe(down502, 0).probe(null, 1).probe(down502, 2))
    }

    @Test
    fun `a passive failure makes the state suspect`() {
        assertEquals(HealthState.Suspect(down502, 1), HealthState.Healthy.passive(down502, 0))
    }

    @Test
    fun `a burst of passive failures never confirms on its own`() {
        var state: HealthState = HealthState.Healthy
        repeat(10) { state = state.passive(down502, it.toLong()) }
        assertEquals(HealthState.Suspect(down502, 1), state)
    }

    @Test
    fun `a passive failure plus a probe failure confirms`() {
        assertEquals(HealthState.Down(network, FAILURE_THRESHOLD, since = 5), HealthState.Healthy.passive(down502, 0).probe(network, 5))
    }

    @Test
    fun `further probe failures count up, take the latest outage and keep the incident time`() {
        assertEquals(HealthState.Down(network, 3, since = 100), failing.probe(network, 900))
    }

    @Test
    fun `passive failures while down change nothing`() {
        assertEquals(failing, failing.passive(network, 900))
    }

    @Test
    fun `connectivity lost confirms offline at once, from any state`() {
        for (state in listOf(HealthState.Unknown, HealthState.Healthy, HealthState.Suspect(down502, 1), HealthState.BackOnline(5, 1))) {
            assertEquals(HealthState.Down(offline, FAILURE_THRESHOLD, since = 50), state.reduce(HealthEvent.ConnectivityLost(50)), "$state")
        }
    }

    @Test
    fun `connectivity lost while down keeps the incident and its count`() {
        assertEquals(HealthState.Down(offline, FAILURE_THRESHOLD, since = 100), failing.reduce(HealthEvent.ConnectivityLost(500)))
    }

    @Test
    fun `an ok while down is back online, from a probe or a regular call`() {
        assertEquals(HealthState.BackOnline(at = 1_000, downSince = 100), failing.probe(null, 1_000))
        assertEquals(HealthState.BackOnline(at = 1_000, downSince = 100), failing.passive(null, 1_000))
    }

    @Test
    fun `further oks keep the notice until it settles`() {
        val back = HealthState.BackOnline(at = 1_000, downSince = 0)
        assertEquals(back, back.probe(null, 1_500))
        assertEquals(HealthState.Healthy, back.reduce(HealthEvent.Settle(1_000)))
    }

    @Test
    fun `a settle for another notice, or outside back online, is a no-op`() {
        val back = HealthState.BackOnline(at = 2_000, downSince = 0)
        assertEquals(back, back.reduce(HealthEvent.Settle(1_000)))
        for (state in listOf(HealthState.Unknown, HealthState.Healthy, HealthState.Suspect(network, 1), failing)) {
            assertEquals(state, state.reduce(HealthEvent.Settle(0)))
        }
    }

    @Test
    fun `a suspect that clears never shows back online`() {
        assertEquals(HealthState.Healthy, HealthState.Healthy.probe(down502, 0).probe(null, 1))
    }

    @Test
    fun `a failure right after recovery starts a new debounce, then a new incident`() {
        val back = failing.probe(null, 1_000)
        val suspect = back.probe(network, 1_100)
        assertEquals(HealthState.Suspect(network, 1), suspect)
        assertEquals(HealthState.Down(network, FAILURE_THRESHOLD, since = 1_200), suspect.probe(network, 1_200))
    }

    @Test
    fun `invalid states cannot be built`() {
        assertThrows<IllegalArgumentException> { HealthState.Down(network, failures = FAILURE_THRESHOLD - 1, since = 0) }
        assertThrows<IllegalArgumentException> { HealthState.Suspect(network, failures = 0) }
        assertThrows<IllegalArgumentException> { HealthState.Suspect(network, failures = FAILURE_THRESHOLD) }
    }

    @Test
    fun `polls at the healthy interval when unknown, healthy or back online, quickly when suspect`() {
        assertEquals(HealthPolicy.HEALTHY_PROBE_MILLIS, HealthState.Unknown.nextProbeDelayMillis())
        assertEquals(HealthPolicy.HEALTHY_PROBE_MILLIS, HealthState.Healthy.nextProbeDelayMillis())
        assertEquals(HealthPolicy.HEALTHY_PROBE_MILLIS, HealthState.BackOnline(0, 0).nextProbeDelayMillis())
        assertEquals(HealthPolicy.SUSPECT_PROBE_MILLIS, HealthState.Suspect(network, 1).nextProbeDelayMillis())
    }

    @Test
    fun `backs off exponentially while down, capped`() {
        val delays = (2..9).map { HealthState.Down(network, it, 0).nextProbeDelayMillis() }
        assertEquals(listOf(5_000L, 10_000L, 20_000L, 40_000L, 60_000L, 60_000L, 60_000L, 60_000L), delays)
        assertEquals(HealthPolicy.DOWN_BACKOFF_MAX_MILLIS, HealthState.Down(network, Int.MAX_VALUE, 0).nextProbeDelayMillis())
        assertEquals(HealthState.Down(network, Int.MAX_VALUE, 0), HealthState.Down(network, Int.MAX_VALUE, 0).probe(network, 1))
    }

    @Test
    fun `entering suspect is detected only on the first failure`() {
        assertTrue(HealthState.Suspect(network, 1).becameSuspectFrom(HealthState.Healthy))
        assertFalse(HealthState.Suspect(network, 1).becameSuspectFrom(HealthState.Suspect(down502, 1)))
        assertFalse(HealthState.Healthy.becameSuspectFrom(HealthState.Unknown))
    }
}
