package dev.nymph.lyrics.app.status

import dev.nymph.lyrics.logic.status.HealthPolicy
import dev.nymph.lyrics.logic.status.HealthState
import dev.nymph.lyrics.model.status.DownCause
import dev.nymph.lyrics.model.status.HealthSignal
import dev.nymph.lyrics.model.status.Outage
import dev.nymph.lyrics.model.status.UnreachableReason
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HealthMonitorTest {
    private val down502 = Outage.BackendDown(DownCause.Http(502))
    private val down = HealthSignal.Failed(down502)
    private val network = Outage.Unreachable(UnreachableReason.NETWORK)
    private val unreachable = HealthSignal.Failed(network)

    /** Answers probes from [script] (repeating the last entry) and records when each probe ran. */
    private class FakeProbe(private val scope: TestScope, vararg script: HealthSignal) {
        val queue = ArrayDeque(script.toList())
        var last: HealthSignal = script.last()
        val times = mutableListOf<Long>()

        suspend fun probe(): HealthSignal {
            times += scope.testScheduler.currentTime
            last = queue.removeFirstOrNull() ?: last
            return last
        }

        fun then(vararg next: HealthSignal) = queue.addAll(next)
    }

    private fun TestScope.monitor(probe: FakeProbe) = HealthMonitor(probe::probe, clock = { testScheduler.currentTime })

    @Test
    fun `probes on start and every healthy interval`() = runTest {
        val probe = FakeProbe(this, HealthSignal.Up)
        val monitor = monitor(probe)
        assertEquals(HealthState.Unknown, monitor.state.value)
        backgroundScope.launch { monitor.run() }
        runCurrent()
        assertEquals(HealthState.Healthy, monitor.state.value)
        advanceTimeBy(HealthPolicy.HEALTHY_PROBE_MILLIS * 3)
        runCurrent()
        assertEquals(listOf(0L, 15_000L, 30_000L, 45_000L), probe.times)
    }

    @Test
    fun `a single failure is confirmed quickly and cleared by a success`() = runTest {
        val probe = FakeProbe(this, down, HealthSignal.Up)
        val monitor = monitor(probe)
        backgroundScope.launch { monitor.run() }
        runCurrent()
        assertEquals(HealthState.Suspect(down502, 1), monitor.state.value)
        advanceTimeBy(HealthPolicy.SUSPECT_PROBE_MILLIS)
        runCurrent()
        assertEquals(HealthState.Healthy, monitor.state.value)
        assertEquals(listOf(0L, 3_000L), probe.times)
    }

    @Test
    fun `two failures mark it down, then probes back off up to the cap`() = runTest {
        val probe = FakeProbe(this, unreachable)
        val monitor = monitor(probe)
        backgroundScope.launch { monitor.run() }
        advanceTimeBy(3_001)
        assertEquals(HealthState.Down(network, failures = 2, since = 3_000), monitor.state.value)
        advanceTimeBy(60_000)
        // 0, +3s confirm, then +5s, +10s, +20s, +40s backoff.
        assertEquals(listOf(0L, 3_000L, 8_000L, 18_000L, 38_000L), probe.times)
        advanceTimeBy(300_000)
        val gaps = probe.times.zipWithNext { a, b -> b - a }
        assertEquals(HealthPolicy.DOWN_BACKOFF_MAX_MILLIS, gaps.last())
    }

    @Test
    fun `recovery shows back online briefly, then healthy`() = runTest {
        val probe = FakeProbe(this, down, down, HealthSignal.Up)
        val monitor = monitor(probe)
        backgroundScope.launch { monitor.run() }
        advanceTimeBy(3_001)
        assertEquals(HealthState.Down(down502, 2, since = 3_000), monitor.state.value)
        advanceTimeBy(5_000)
        assertEquals(HealthState.BackOnline(at = 8_000, downSince = 3_000), monitor.state.value)
        advanceTimeBy(HealthPolicy.RECOVERY_DISPLAY_MILLIS - 2)
        assertEquals(HealthState.BackOnline(at = 8_000, downSince = 3_000), monitor.state.value)
        advanceTimeBy(2)
        runCurrent()
        assertEquals(HealthState.Healthy, monitor.state.value)
    }

    @Test
    fun `a failing regular call pulls a confirming probe forward`() = runTest {
        val probe = FakeProbe(this, HealthSignal.Up)
        val monitor = monitor(probe)
        backgroundScope.launch { monitor.run() }
        runCurrent()
        advanceTimeBy(1_000)
        probe.then(down)
        monitor.onCallOutcome(HealthSignal.Failed(Outage.BackendDown(DownCause.Http(504))))
        assertEquals(HealthState.Suspect(Outage.BackendDown(DownCause.Http(504)), 1), monitor.state.value)
        runCurrent()
        assertEquals(listOf(0L, 1_000L), probe.times)
        assertEquals(HealthState.Down(down502, failures = 2, since = 1_000), monitor.state.value)
    }

    @Test
    fun `a burst of failing regular calls is one observation and one extra probe`() = runTest {
        val probe = FakeProbe(this, HealthSignal.Up)
        val monitor = monitor(probe)
        backgroundScope.launch { monitor.run() }
        runCurrent()
        advanceTimeBy(1_000)
        repeat(5) { monitor.onCallOutcome(down) }
        assertEquals(HealthState.Suspect(down502, 1), monitor.state.value)
        runCurrent()
        // The confirming probe answered Up.
        assertEquals(listOf(0L, 1_000L), probe.times)
        assertEquals(HealthState.Healthy, monitor.state.value)
    }

    @Test
    fun `failing calls while down neither add probes nor reset the backoff`() = runTest {
        val probe = FakeProbe(this, down)
        val monitor = monitor(probe)
        backgroundScope.launch { monitor.run() }
        advanceTimeBy(3_001)
        val before = monitor.state.value
        val probes = probe.times.size
        monitor.onCallOutcome(down)
        runCurrent()
        assertEquals(probes, probe.times.size)
        assertEquals(before, monitor.state.value)
    }

    @Test
    fun `a successful regular call while down counts as recovery`() = runTest {
        val probe = FakeProbe(this, down)
        val monitor = monitor(probe)
        backgroundScope.launch { monitor.run() }
        advanceTimeBy(3_001)
        monitor.onCallOutcome(HealthSignal.Up)
        assertEquals(HealthState.BackOnline(at = 3_001, downSince = 3_000), monitor.state.value)
    }

    @Test
    fun `wake up probes immediately and the cadence restarts from there`() = runTest {
        val probe = FakeProbe(this, HealthSignal.Up)
        val monitor = monitor(probe)
        backgroundScope.launch { monitor.run() }
        runCurrent()
        advanceTimeBy(2_000)
        monitor.wakeUp()
        monitor.wakeUp()
        runCurrent()
        assertEquals(listOf(0L, 2_000L), probe.times)
        advanceTimeBy(HealthPolicy.HEALTHY_PROBE_MILLIS)
        runCurrent()
        assertEquals(listOf(0L, 2_000L, 17_000L), probe.times)
    }

    @Test
    fun `connectivity lost is offline at once, and the next probe success recovers`() = runTest {
        val probe = FakeProbe(this, HealthSignal.Up)
        val monitor = monitor(probe)
        backgroundScope.launch { monitor.run() }
        runCurrent()
        advanceTimeBy(1_000)
        monitor.connectivityLost()
        assertEquals(HealthState.Down(Outage.Unreachable(UnreachableReason.OFFLINE), 2, since = 1_000), monitor.state.value)
        monitor.wakeUp()
        runCurrent()
        assertEquals(HealthState.BackOnline(at = 1_000, downSince = 1_000), monitor.state.value)
    }

    @Test
    fun `stopping the run stops probing, and restarting probes at once and keeps the state`() = runTest {
        val probe = FakeProbe(this, down)
        val monitor = monitor(probe)
        val job = launch { monitor.run() }
        advanceTimeBy(3_001)
        job.cancel()
        val stopped = probe.times.size
        advanceTimeBy(600_000)
        assertEquals(stopped, probe.times.size)
        assertEquals(HealthState.Down(down502, 2, since = 3_000), monitor.state.value)
        probe.then(HealthSignal.Up)
        val again = launch { monitor.run() }
        runCurrent()
        assertEquals(stopped + 1, probe.times.size)
        assertEquals(HealthState.BackOnline(at = 603_001, downSince = 3_000), monitor.state.value)
        again.cancel()
    }

    @Test
    fun `back online that outlived a background stop settles on resume`() = runTest {
        val probe = FakeProbe(this, down, down, HealthSignal.Up)
        val monitor = monitor(probe)
        val job = launch { monitor.run() }
        advanceTimeBy(8_001)
        assertEquals(HealthState.BackOnline(at = 8_000, downSince = 3_000), monitor.state.value)
        job.cancel()
        advanceTimeBy(60_000)
        assertEquals(HealthState.BackOnline(at = 8_000, downSince = 3_000), monitor.state.value)
        val again = launch { monitor.run() }
        runCurrent()
        assertEquals(HealthState.Healthy, monitor.state.value)
        again.cancel()
    }
}
