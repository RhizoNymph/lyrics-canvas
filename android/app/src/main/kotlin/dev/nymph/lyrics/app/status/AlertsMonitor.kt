package dev.nymph.lyrics.app.status

import dev.nymph.lyrics.logic.status.AlertsState
import dev.nymph.lyrics.logic.status.applyAlertsFetch
import dev.nymph.lyrics.model.status.AlertsFetch
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Polls `/_status/alerts` while the app is in the foreground ([run] is cancelled in the
 * background) and folds each answer into [AlertsState] with [applyAlertsFetch]. A failed fetch
 * keeps the last known alerts, marked stale.
 */
class AlertsMonitor(
    private val fetch: suspend () -> AlertsFetch,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mutableState = MutableStateFlow<AlertsState>(AlertsState.Idle)
    val state: StateFlow<AlertsState> = mutableState.asStateFlow()

    private val wake = Channel<Unit>(Channel.CONFLATED)

    /** Resume or connectivity regained: fetch at once. */
    fun wakeUp() {
        wake.trySend(Unit)
    }

    /** Fetch until cancelled; returns at once when the build has no alerts URL. */
    suspend fun run() {
        while (true) {
            val result = fetch()
            mutableState.update { applyAlertsFetch(it, result, clock()) }
            val interval = when (result) {
                AlertsFetch.Disabled -> return
                AlertsFetch.Absent -> ABSENT_POLL_MILLIS
                is AlertsFetch.Alerts, is AlertsFetch.Unavailable, is AlertsFetch.Failed -> POLL_MILLIS
            }
            wake.tryReceive()
            withTimeoutOrNull(interval) { wake.receive() }
        }
    }

    companion object {
        const val POLL_MILLIS: Long = 30_000

        /** The endpoint may be deployed later; check now and then without noise. */
        const val ABSENT_POLL_MILLIS: Long = 10 * 60_000
    }
}
