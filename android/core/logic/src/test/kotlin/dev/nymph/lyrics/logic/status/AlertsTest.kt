package dev.nymph.lyrics.logic.status

import dev.nymph.lyrics.model.status.AlertSeverity
import dev.nymph.lyrics.model.status.AlertsFetch
import dev.nymph.lyrics.model.status.AlertsFetchError
import dev.nymph.lyrics.model.status.ClusterAlert
import dev.nymph.lyrics.model.status.IncidentKey
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.IOException
import java.time.Instant

class AlertsTest {
    private fun alert(key: String, severity: AlertSeverity, startsAt: Long, name: String = "Alert-$key") =
        ClusterAlert(IncidentKey(key), name, severity, summary = null, description = null, startsAt = Instant.ofEpochSecond(startsAt))

    private val w = alert("w", AlertSeverity.Warning, 1)
    private val known = AlertsState.Known(listOf(w), fetchedAt = 5)
    private val failed = AlertsFetch.Failed(AlertsFetchError.Network(IOException("x")))

    @Test
    fun `orders critical, warning, other, newest first within a severity, then by name`() {
        val sorted = sortAlerts(
            listOf(
                alert("w1", AlertSeverity.Warning, 10),
                alert("o", AlertSeverity.Other("info"), 50),
                alert("c1", AlertSeverity.Critical, 5),
                alert("c2", AlertSeverity.Critical, 20),
                alert("w2", AlertSeverity.Warning, 30),
                alert("w3b", AlertSeverity.Warning, 10, name = "B"),
                alert("w3a", AlertSeverity.Warning, 10, name = "A"),
            ),
        )
        // Same severity and start: by name ("A" < "Alert-w1" < "B").
        assertEquals(listOf("c2", "c1", "w2", "w3a", "w1", "w3b", "o"), sorted.map { it.key.value })
    }

    @Test
    fun `one entry per incident`() {
        assertEquals(1, sortAlerts(listOf(w, w.copy(name = "dup"))).size)
    }

    @Test
    fun `a list replaces the state, sorted`() {
        val c = alert("c", AlertSeverity.Critical, 0)
        assertEquals(AlertsState.Known(listOf(c, w), fetchedAt = 9), applyAlertsFetch(AlertsState.Idle, AlertsFetch.Alerts(listOf(w, c), 0), 9))
        assertEquals(AlertsState.Known(emptyList(), fetchedAt = 9), applyAlertsFetch(known, AlertsFetch.Alerts(emptyList(), 0), 9))
    }

    @Test
    fun `absent and disabled are absent`() {
        assertEquals(AlertsState.Absent, applyAlertsFetch(known, AlertsFetch.Absent, 9))
        assertEquals(AlertsState.Absent, applyAlertsFetch(AlertsState.Idle, AlertsFetch.Disabled, 9))
    }

    @Test
    fun `unavailable keeps the last known alerts, marked stale`() {
        assertEquals(AlertsState.Stale(listOf(w), StaleReason.UNAVAILABLE), applyAlertsFetch(known, AlertsFetch.Unavailable(503), 9))
    }

    @Test
    fun `a failure keeps the last known alerts too, and stays stale across failures`() {
        val s1 = applyAlertsFetch(known, failed, 9)
        assertEquals(AlertsState.Stale(listOf(w), StaleReason.FAILED), s1)
        assertEquals(AlertsState.Stale(listOf(w), StaleReason.UNAVAILABLE), applyAlertsFetch(s1, AlertsFetch.Unavailable(503), 10))
    }

    @Test
    fun `a failure with nothing known is stale with nothing to show`() {
        assertEquals(AlertsState.Stale(emptyList(), StaleReason.UNAVAILABLE), applyAlertsFetch(AlertsState.Idle, AlertsFetch.Unavailable(503), 9))
        assertEquals(AlertsState.Stale(emptyList(), StaleReason.FAILED), applyAlertsFetch(AlertsState.Absent, failed, 9))
    }

    @Test
    fun `shown alerts per state`() {
        assertEquals(emptyList<ClusterAlert>(), AlertsState.Idle.shown)
        assertEquals(emptyList<ClusterAlert>(), AlertsState.Absent.shown)
        assertEquals(listOf(w), known.shown)
        assertEquals(listOf(w), AlertsState.Stale(listOf(w), StaleReason.FAILED).shown)
    }
}
