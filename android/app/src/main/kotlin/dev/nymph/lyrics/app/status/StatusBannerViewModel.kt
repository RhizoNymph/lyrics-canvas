package dev.nymph.lyrics.app.status

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.nymph.lyrics.logic.status.AlertsState
import dev.nymph.lyrics.logic.status.HealthState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

/** One visible banner item and whether its description is expanded. */
data class BannerRow(val item: BannerItem, val expanded: Boolean)

/** What the banner composable draws. Empty rows means no banner at all. */
data class StatusBannerUiState(val rows: List<BannerRow>, val alertsStale: Boolean) {
    val isVisible: Boolean get() = rows.isNotEmpty()

    /** The stale note only makes sense next to alert rows. */
    val showStaleNote: Boolean get() = alertsStale && rows.any { it.item.source == BannerSource.ALERT }

    companion object {
        val Empty = StatusBannerUiState(emptyList(), alertsStale = false)
    }
}

/**
 * The app-wide status banner: maps the monitors' state through [buildBannerModel] and holds the
 * per-incident dismissals and expanded descriptions (for the activity's lifetime). Dismissals of
 * incidents that ended are dropped, so the set never grows without bound.
 */
class StatusBannerViewModel(
    health: StateFlow<HealthState>,
    alerts: StateFlow<AlertsState>,
    private val format: BannerFormat = BannerFormat(),
) : ViewModel() {
    private val dismissed = MutableStateFlow<Set<BannerKey>>(emptySet())
    private val expanded = MutableStateFlow<Set<BannerKey>>(emptySet())

    val state: StateFlow<StatusBannerUiState> = combine(health, alerts, dismissed, expanded) { h, a, d, e ->
        val model = buildBannerModel(h, a, format)
        val keys = model.items.map { it.key }
        dismissed.update { pruneDismissed(it, keys) }
        expanded.update { pruneDismissed(it, keys) }
        StatusBannerUiState(
            rows = model.visibleItems(d).map { BannerRow(it, expanded = it.key in e) },
            alertsStale = model.alertsStale,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, StatusBannerUiState.Empty)

    /** Hide this incident; a later incident (new key) shows again. */
    fun dismiss(key: BannerKey) {
        dismissed.update { it + key }
        expanded.update { it - key }
    }

    fun toggleExpanded(key: BannerKey) {
        expanded.update { if (key in it) it - key else it + key }
    }
}
