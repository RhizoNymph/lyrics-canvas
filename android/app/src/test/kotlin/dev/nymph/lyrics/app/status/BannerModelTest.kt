package dev.nymph.lyrics.app.status

import dev.nymph.lyrics.logic.status.AlertsState
import dev.nymph.lyrics.logic.status.HealthState
import dev.nymph.lyrics.logic.status.StaleReason
import dev.nymph.lyrics.model.status.AlertSeverity
import dev.nymph.lyrics.model.status.DownCause
import dev.nymph.lyrics.model.status.Outage
import dev.nymph.lyrics.model.status.UnreachableReason
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale

class BannerModelTest {
    private val now = Instant.parse("2026-10-28T15:30:00Z")
    private val format = BannerFormat(zone = ZoneOffset.UTC, locale = Locale.US, now = { now })
    private val since = Instant.parse("2026-10-28T14:03:00Z").toEpochMilli()
    private val down502 = HealthState.Down(Outage.BackendDown(DownCause.Http(502)), 2, since)

    private val critical = alert("BackendDown", AlertSeverity.Critical, "Backend has no ready pods", "0 of 1 replicas ready.")
    private val warning = alert("DiskFilling", AlertSeverity.Warning, startsAt = Instant.parse("2026-10-27T09:15:00Z"))
    private fun known(vararg alerts: dev.nymph.lyrics.model.status.ClusterAlert) = AlertsState.Known(alerts.toList(), fetchedAt = 0)

    private fun backend(health: HealthState) = buildBannerModel(health, AlertsState.Idle, format).items.single()

    @Test
    fun `empty when healthy, unknown or merely suspect`() {
        val quiet = listOf(HealthState.Unknown, HealthState.Healthy, HealthState.Suspect(Outage.Unreachable(UnreachableReason.NETWORK), 1))
        quiet.forEach { assertEquals(BannerModel.Empty, buildBannerModel(it, AlertsState.Idle, format), "$it") }
    }

    @Test
    fun `backend down is an assertive danger item saying changes may not be saving`() {
        val item = backend(down502)
        assertEquals(BannerSource.BACKEND, item.source)
        assertEquals(Announce.ASSERTIVE, item.announce)
        assertEquals(BannerTone.DANGER, item.tone)
        assertEquals("Server is down", item.title)
        assertEquals("The server answered HTTP 502. Changes may not be saving.", item.detail)
        assertEquals("Since 14:03", item.started)
    }

    @Test
    fun `an invalid health body still reads as down`() {
        val item = backend(HealthState.Down(Outage.BackendDown(DownCause.InvalidBody), 2, since))
        assertEquals("Server is not responding correctly", item.title)
        assertTrue(item.detail!!.contains("may not be saving"))
    }

    @Test
    fun `offline names the device connection, unreachable the connection or server`() {
        val offline = backend(HealthState.Down(Outage.Unreachable(UnreachableReason.OFFLINE), 2, since))
        assertEquals("You're offline", offline.title)
        assertTrue(offline.detail!!.contains("may not be saving"))
        val unreachable = backend(HealthState.Down(Outage.Unreachable(UnreachableReason.NETWORK), 2, since))
        assertEquals("Can't reach the server", unreachable.title)
        assertTrue(unreachable.detail!!.contains("connection"))
        assertEquals(Announce.ASSERTIVE, unreachable.announce)
        val timeout = backend(HealthState.Down(Outage.Unreachable(UnreachableReason.TIMEOUT), 2, since))
        assertTrue(timeout.detail!!.contains("in time"))
    }

    @Test
    fun `an older start time shows the date`() {
        val item = backend(HealthState.Down(Outage.Unreachable(UnreachableReason.NETWORK), 2, Instant.parse("2026-10-27T23:50:00Z").toEpochMilli()))
        assertEquals("Since Oct 27, 23:50", item.started)
    }

    @Test
    fun `down and unreachable are different incidents, a new outage is a new incident`() {
        val unreachable = backend(down502.copy(outage = Outage.Unreachable(UnreachableReason.NETWORK)))
        assertNotEquals(backend(down502).key, unreachable.key)
        assertNotEquals(backend(down502).key, backend(down502.copy(since = since + 1)).key)
        assertEquals(backend(down502).key, backend(down502.copy(failures = 7, outage = Outage.BackendDown(DownCause.Http(504)))).key)
    }

    @Test
    fun `back online is a polite success item`() {
        val item = backend(HealthState.BackOnline(at = 300, downSince = 100))
        assertEquals(Announce.POLITE, item.announce)
        assertEquals(BannerTone.SUCCESS, item.tone)
        assertEquals("Back online", item.title)
    }

    @Test
    fun `alerts keep the state's order and map severity to tone and announcement`() {
        val other = alert("Odd", AlertSeverity.Other("info"))
        val items = buildBannerModel(HealthState.Healthy, known(critical, warning, other), format).items
        assertEquals(listOf("Critical", "Warning", "Info"), items.map { it.label })
        assertEquals(listOf(BannerTone.DANGER, BannerTone.WARNING, BannerTone.INFO), items.map { it.tone })
        assertEquals(listOf(Announce.ASSERTIVE, Announce.POLITE, Announce.POLITE), items.map { it.announce })
    }

    @Test
    fun `summary is the title with the name as detail, and without a summary the name is the title`() {
        val (c, w) = buildBannerModel(HealthState.Healthy, known(critical, warning), format).items
        assertEquals("Backend has no ready pods", c.title)
        assertEquals("BackendDown", c.detail)
        assertEquals("0 of 1 replicas ready.", c.description)
        assertEquals("Since 14:03", c.started)
        assertEquals("DiskFilling", w.title)
        assertEquals(null, w.detail)
        assertEquals("Since Oct 27, 09:15", w.started)
    }

    @Test
    fun `the backend item comes first and alerts stay visible while the backend is down`() {
        val items = buildBannerModel(down502, known(critical), format).items
        assertEquals(listOf(BannerSource.BACKEND, BannerSource.ALERT), items.map { it.source })
    }

    @Test
    fun `stale alerts are shown and flagged, and quiet states are empty`() {
        val stale = buildBannerModel(HealthState.Healthy, AlertsState.Stale(listOf(critical), StaleReason.UNAVAILABLE), format)
        assertEquals(1, stale.items.size)
        assertTrue(stale.alertsStale)
        listOf(AlertsState.Stale(emptyList(), StaleReason.FAILED), AlertsState.Absent, AlertsState.Idle).forEach {
            assertEquals(BannerModel.Empty, buildBannerModel(HealthState.Healthy, it, format), "$it")
        }
        assertFalse(buildBannerModel(HealthState.Healthy, known(critical), format).alertsStale)
    }

    @Test
    fun `visible items hide dismissed keys only`() {
        val model = buildBannerModel(down502, known(critical, warning), format)
        val (b, c, w) = model.items
        assertEquals(listOf(c), model.visibleItems(setOf(b.key, w.key)))
    }

    @Test
    fun `pruneDismissed drops ended incidents and keeps identity when unchanged`() {
        val a = BannerKey("a")
        val b = BannerKey("b")
        val d = setOf(a, b)
        assertEquals(setOf(b), pruneDismissed(d, listOf(b, BannerKey("c"))))
        assertSame(d, pruneDismissed(d, listOf(a, b, BannerKey("c"))))
    }

    @Test
    fun `severity labels`() {
        assertEquals("Alert", severityLabel(AlertSeverity.Other(null)))
        assertEquals("Alert", severityLabel(AlertSeverity.Other("")))
        assertEquals("Info", severityLabel(AlertSeverity.Other("info")))
    }
}
