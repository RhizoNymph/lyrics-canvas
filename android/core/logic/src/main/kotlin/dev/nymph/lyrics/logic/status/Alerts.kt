package dev.nymph.lyrics.logic.status

import dev.nymph.lyrics.model.status.AlertsFetch
import dev.nymph.lyrics.model.status.ClusterAlert

/** Why the latest alerts fetch produced no list. */
enum class StaleReason {
    /** 5xx: the endpoint cannot reach Alertmanager. */
    UNAVAILABLE,

    /** No answer, or a status outside the contract. */
    FAILED,
}

/** What the app knows about cluster alerts. Transitions only through [applyAlertsFetch]. */
sealed interface AlertsState {
    /** Not fetched yet. */
    data object Idle : AlertsState

    /** No alerts endpoint for this build or deployment. Stay quiet. */
    data object Absent : AlertsState

    /** The latest list, firing alerts only, in [displayOrder]. */
    data class Known(val alerts: List<ClusterAlert>, val fetchedAt: Long) : AlertsState

    /** The last fetch produced no list; [lastKnown] (possibly empty) is what was firing before. */
    data class Stale(val lastKnown: List<ClusterAlert>, val reason: StaleReason) : AlertsState
}

/** Critical first, then warning, then the rest; newest first within a severity; then by name. */
val displayOrder: Comparator<ClusterAlert> =
    compareBy<ClusterAlert> { it.severity.rank }
        .thenByDescending { it.startsAt }
        .thenBy { it.name }

fun sortAlerts(alerts: List<ClusterAlert>): List<ClusterAlert> = alerts.distinctBy { it.key }.sortedWith(displayOrder)

/** The alerts a state still shows. */
val AlertsState.shown: List<ClusterAlert>
    get() = when (this) {
        AlertsState.Idle, AlertsState.Absent -> emptyList()
        is AlertsState.Known -> alerts
        is AlertsState.Stale -> lastKnown
    }

/** Fold one fetch into the state. A failure keeps the last known alerts visible, marked stale. */
fun applyAlertsFetch(state: AlertsState, fetch: AlertsFetch, now: Long): AlertsState = when (fetch) {
    is AlertsFetch.Alerts -> AlertsState.Known(sortAlerts(fetch.alerts), fetchedAt = now)
    AlertsFetch.Absent, AlertsFetch.Disabled -> AlertsState.Absent
    is AlertsFetch.Unavailable -> AlertsState.Stale(state.shown, StaleReason.UNAVAILABLE)
    is AlertsFetch.Failed -> AlertsState.Stale(state.shown, StaleReason.FAILED)
}
