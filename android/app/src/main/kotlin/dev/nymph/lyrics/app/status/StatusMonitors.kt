package dev.nymph.lyrics.app.status

import dev.nymph.lyrics.data.status.StatusRepository
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/** The health and alerts monitors of one process, and how they run while the app is visible. */
class StatusMonitors(status: StatusRepository, clock: () -> Long = System::currentTimeMillis) {
    val health = HealthMonitor(status::probeHealth, clock)
    val alerts = AlertsMonitor(status::fetchAlerts, clock)

    /**
     * Poll both until cancelled (call from `repeatOnLifecycle(STARTED)`: each start polls at
     * once, leaving the foreground stops polling). Connectivity regained probes at once; losing
     * it marks the server unreachable without waiting for timeouts.
     */
    suspend fun runInForeground(connectivity: Flow<ConnectivityChange>) {
        coroutineScope {
            launch { health.run() }
            launch { alerts.run() }
            connectivity.collect { change ->
                when (change) {
                    ConnectivityChange.Available -> {
                        health.wakeUp()
                        alerts.wakeUp()
                    }
                    ConnectivityChange.Lost -> health.connectivityLost()
                }
            }
        }
    }
}
