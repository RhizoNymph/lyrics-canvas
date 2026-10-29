package dev.nymph.lyrics.app.status

import dev.nymph.lyrics.app.MainDispatcherExtension
import dev.nymph.lyrics.logic.status.AlertsState
import dev.nymph.lyrics.logic.status.HealthState
import dev.nymph.lyrics.logic.status.StaleReason
import dev.nymph.lyrics.model.status.AlertSeverity
import dev.nymph.lyrics.model.status.DownCause
import dev.nymph.lyrics.model.status.Outage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale

@OptIn(ExperimentalCoroutinesApi::class)
class StatusBannerViewModelTest {
    @JvmField
    @RegisterExtension
    val main = MainDispatcherExtension()

    private val now = Instant.parse("2026-10-28T15:30:00Z")
    private val format = BannerFormat(zone = ZoneOffset.UTC, locale = Locale.US, now = { now })
    private val critical = alert("BackendDown", AlertSeverity.Critical, "Backend has no ready pods", "0 of 1 replicas ready.")
    private val warning = alert("DiskFilling", AlertSeverity.Warning)
    private val down = HealthState.Down(Outage.BackendDown(DownCause.Http(504)), 2, now.toEpochMilli())

    private fun known(vararg alerts: dev.nymph.lyrics.model.status.ClusterAlert) = AlertsState.Known(alerts.toList(), 0)

    private fun StatusBannerViewModel.keys() = state.value.rows.map { it.item.key.value }

    @Test
    fun `follows health and alerts`() = runTest(main.dispatcher) {
        val health = MutableStateFlow<HealthState>(HealthState.Unknown)
        val alerts = MutableStateFlow<AlertsState>(AlertsState.Idle)
        val vm = StatusBannerViewModel(health, alerts, format)
        runCurrent()
        assertFalse(vm.state.value.isVisible)
        health.value = down
        alerts.value = known(critical)
        runCurrent()
        assertEquals(listOf(BannerSource.BACKEND, BannerSource.ALERT), vm.state.value.rows.map { it.item.source })
        health.value = HealthState.Healthy
        runCurrent()
        assertEquals(listOf(BannerSource.ALERT), vm.state.value.rows.map { it.item.source })
    }

    @Test
    fun `dismissing hides that incident only, and the same alert firing again shows`() = runTest(main.dispatcher) {
        val alerts = MutableStateFlow<AlertsState>(known(critical, warning))
        val vm = StatusBannerViewModel(MutableStateFlow(HealthState.Healthy), alerts, format)
        runCurrent()
        val criticalKey = vm.state.value.rows.first().item.key
        vm.dismiss(criticalKey)
        runCurrent()
        assertEquals(listOf("alert:${warning.key.value}"), vm.keys())
        val refired = alert("BackendDown", AlertSeverity.Critical, startsAt = Instant.parse("2026-10-28T15:00:00Z"))
        alerts.value = known(refired, warning)
        runCurrent()
        assertEquals(listOf("alert:${refired.key.value}", "alert:${warning.key.value}"), vm.keys())
    }

    @Test
    fun `a dismissed outage stays hidden while it lasts and a new outage shows again`() = runTest(main.dispatcher) {
        val health = MutableStateFlow<HealthState>(down)
        val vm = StatusBannerViewModel(health, MutableStateFlow(AlertsState.Idle), format)
        runCurrent()
        vm.dismiss(vm.state.value.rows.single().item.key)
        runCurrent()
        assertFalse(vm.state.value.isVisible)
        health.value = down.copy(failures = 5)
        runCurrent()
        assertFalse(vm.state.value.isVisible)
        health.value = HealthState.BackOnline(at = now.toEpochMilli() + 10, downSince = down.since)
        runCurrent()
        assertEquals("Back online", vm.state.value.rows.single().item.title)
        health.value = down.copy(since = now.toEpochMilli() + 20)
        runCurrent()
        assertTrue(vm.state.value.isVisible)
    }

    @Test
    fun `toggling expands and collapses a description`() = runTest(main.dispatcher) {
        val vm = StatusBannerViewModel(MutableStateFlow(HealthState.Healthy), MutableStateFlow(known(critical)), format)
        runCurrent()
        val key = vm.state.value.rows.single().item.key
        vm.toggleExpanded(key)
        runCurrent()
        assertTrue(vm.state.value.rows.single().expanded)
        vm.toggleExpanded(key)
        runCurrent()
        assertFalse(vm.state.value.rows.single().expanded)
    }

    @Test
    fun `the stale note shows only next to alert rows`() = runTest(main.dispatcher) {
        val alerts = MutableStateFlow<AlertsState>(AlertsState.Stale(listOf(critical), StaleReason.UNAVAILABLE))
        val vm = StatusBannerViewModel(MutableStateFlow(down), alerts, format)
        runCurrent()
        assertTrue(vm.state.value.showStaleNote)
        vm.dismiss(vm.state.value.rows.last().item.key)
        runCurrent()
        assertFalse(vm.state.value.showStaleNote)
        assertTrue(vm.state.value.isVisible)
    }
}
