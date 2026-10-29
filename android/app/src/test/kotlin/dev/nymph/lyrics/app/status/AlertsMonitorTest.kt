package dev.nymph.lyrics.app.status

import dev.nymph.lyrics.logic.status.AlertsState
import dev.nymph.lyrics.logic.status.StaleReason
import dev.nymph.lyrics.model.status.AlertSeverity
import dev.nymph.lyrics.model.status.AlertsFetch
import dev.nymph.lyrics.model.status.AlertsFetchError
import dev.nymph.lyrics.model.status.ClusterAlert
import dev.nymph.lyrics.model.status.IncidentKey
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class AlertsMonitorTest {
    private val warning = alert("disk", AlertSeverity.Warning)
    private val critical = alert("backend", AlertSeverity.Critical)

    private class FakeFetch(private val scope: TestScope, vararg script: AlertsFetch) {
        val queue = ArrayDeque(script.toList())
        var last: AlertsFetch = script.last()
        val times = mutableListOf<Long>()

        suspend fun fetch(): AlertsFetch {
            times += scope.testScheduler.currentTime
            last = queue.removeFirstOrNull() ?: last
            return last
        }
    }

    private fun TestScope.monitor(fetch: FakeFetch) = AlertsMonitor(fetch::fetch, clock = { testScheduler.currentTime })

    @Test
    fun `fetches at once, keeps alerts critical first, and polls`() = runTest {
        val fetch = FakeFetch(this, AlertsFetch.Alerts(listOf(warning, critical), 0))
        val monitor = monitor(fetch)
        assertEquals(AlertsState.Idle, monitor.state.value)
        backgroundScope.launch { monitor.run() }
        runCurrent()
        assertEquals(AlertsState.Known(listOf(critical, warning), fetchedAt = 0), monitor.state.value)
        advanceTimeBy(AlertsMonitor.POLL_MILLIS)
        runCurrent()
        assertEquals(listOf(0L, AlertsMonitor.POLL_MILLIS), fetch.times)
    }

    @Test
    fun `a failure keeps the last alerts visible but stale, and recovers`() = runTest {
        val failed = AlertsFetch.Failed(AlertsFetchError.Network(IOException("down")))
        val fetch = FakeFetch(this, AlertsFetch.Alerts(listOf(critical), 0), failed, AlertsFetch.Unavailable(503), AlertsFetch.Alerts(emptyList(), 0))
        val monitor = monitor(fetch)
        backgroundScope.launch { monitor.run() }
        runCurrent()
        advanceTimeBy(AlertsMonitor.POLL_MILLIS)
        runCurrent()
        assertEquals(AlertsState.Stale(listOf(critical), StaleReason.FAILED), monitor.state.value)
        advanceTimeBy(AlertsMonitor.POLL_MILLIS)
        runCurrent()
        assertEquals(AlertsState.Stale(listOf(critical), StaleReason.UNAVAILABLE), monitor.state.value)
        advanceTimeBy(AlertsMonitor.POLL_MILLIS)
        runCurrent()
        assertEquals(AlertsState.Known(emptyList(), fetchedAt = 3 * AlertsMonitor.POLL_MILLIS), monitor.state.value)
    }

    @Test
    fun `absent stays quiet and checks rarely`() = runTest {
        val fetch = FakeFetch(this, AlertsFetch.Absent)
        val monitor = monitor(fetch)
        backgroundScope.launch { monitor.run() }
        runCurrent()
        assertEquals(AlertsState.Absent, monitor.state.value)
        advanceTimeBy(AlertsMonitor.ABSENT_POLL_MILLIS - 1)
        assertEquals(1, fetch.times.size)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(2, fetch.times.size)
    }

    @Test
    fun `disabled fetches once and stops`() = runTest {
        val fetch = FakeFetch(this, AlertsFetch.Disabled)
        val monitor = monitor(fetch)
        val job = launch { monitor.run() }
        advanceTimeBy(3_600_000)
        assertEquals(AlertsState.Absent, monitor.state.value)
        assertEquals(1, fetch.times.size)
        assertTrue(job.isCompleted)
    }

    @Test
    fun `wake up fetches at once`() = runTest {
        val fetch = FakeFetch(this, AlertsFetch.Absent)
        val monitor = monitor(fetch)
        backgroundScope.launch { monitor.run() }
        runCurrent()
        advanceTimeBy(1_000)
        monitor.wakeUp()
        runCurrent()
        assertEquals(listOf(0L, 1_000L), fetch.times)
    }

    @Test
    fun `stopping the run stops fetching`() = runTest {
        val fetch = FakeFetch(this, AlertsFetch.Alerts(emptyList(), 0))
        val monitor = monitor(fetch)
        val job = launch { monitor.run() }
        runCurrent()
        job.cancel()
        advanceTimeBy(10 * AlertsMonitor.POLL_MILLIS)
        assertEquals(1, fetch.times.size)
    }
}

internal fun alert(
    name: String,
    severity: AlertSeverity,
    summary: String? = null,
    description: String? = null,
    startsAt: Instant = Instant.parse("2026-10-28T14:03:00Z"),
): ClusterAlert = ClusterAlert(IncidentKey("$name@${startsAt.toEpochMilli()}#${severity.id}"), name, severity, summary, description, startsAt)
