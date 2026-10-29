package dev.nymph.lyrics.app.status

import dev.nymph.lyrics.logic.status.AlertsState
import dev.nymph.lyrics.logic.status.HealthState
import dev.nymph.lyrics.logic.status.shown
import dev.nymph.lyrics.model.status.AlertSeverity
import dev.nymph.lyrics.model.status.ClusterAlert
import dev.nymph.lyrics.model.status.DownCause
import dev.nymph.lyrics.model.status.Outage
import dev.nymph.lyrics.model.status.UnreachableReason
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Per-incident identity of a banner item; dismissing hides exactly this key. */
@JvmInline
value class BannerKey(val value: String)

enum class BannerSource { BACKEND, ALERT }

/** How urgently accessibility services announce the item (Compose `LiveRegionMode`). */
enum class Announce { ASSERTIVE, POLITE }

enum class BannerTone { DANGER, WARNING, INFO, SUCCESS }

/** One thing worth telling the user. */
data class BannerItem(
    val key: BannerKey,
    val source: BannerSource,
    val announce: Announce,
    val tone: BannerTone,
    /** "Critical", "Warning", ... for alerts; null for the backend item. */
    val label: String?,
    val title: String,
    val detail: String?,
    /** Long text, shown behind an expander. */
    val description: String?,
    /** "Since 14:03", or null. */
    val started: String?,
)

/**
 * Ordered: the backend item first, then alerts critical / warning / other, newest first.
 * [alertsStale] says the alerts shown are the last known ones (the source cannot be read now).
 */
data class BannerModel(val items: List<BannerItem>, val alertsStale: Boolean) {
    companion object {
        val Empty = BannerModel(emptyList(), alertsStale = false)
    }
}

/** Time zone, locale and clock for the banner's timestamps; injected so tests are deterministic. */
class BannerFormat(
    private val zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
    private val now: () -> Instant = Instant::now,
) {
    private val time = DateTimeFormatter.ofPattern("HH:mm", locale)
    private val dateTime = DateTimeFormatter.ofPattern("MMM d, HH:mm", locale)

    /** "14:03" today, "Oct 27, 14:03" otherwise. */
    fun format(instant: Instant): String {
        val at = instant.atZone(zone)
        val today = now().atZone(zone).toLocalDate()
        return if (at.toLocalDate() == today) time.format(at) else dateTime.format(at)
    }

    fun since(instant: Instant): String = "Since ${format(instant)}"
}

const val NOT_SAVING: String = "Changes may not be saving."

fun buildBannerModel(health: HealthState, alerts: AlertsState, format: BannerFormat): BannerModel {
    val backend = backendItem(health, format)
    val alertItems = alerts.shown.map { alertItem(it, format) }
    return BannerModel(
        items = listOfNotNull(backend) + alertItems,
        alertsStale = alerts is AlertsState.Stale && alertItems.isNotEmpty(),
    )
}

/** The items not dismissed. */
fun BannerModel.visibleItems(dismissed: Set<BannerKey>): List<BannerItem> = items.filter { it.key !in dismissed }

/** Drop dismissals of incidents that are over; returns [dismissed] itself when nothing changes. */
fun pruneDismissed(dismissed: Set<BannerKey>, current: Collection<BannerKey>): Set<BannerKey> {
    val kept = dismissed.filterTo(LinkedHashSet()) { it in current }
    return if (kept.size == dismissed.size) dismissed else kept
}

fun severityLabel(severity: AlertSeverity): String = when (severity) {
    AlertSeverity.Critical -> "Critical"
    AlertSeverity.Warning -> "Warning"
    is AlertSeverity.Other -> severity.label?.takeIf { it.isNotBlank() }?.replaceFirstChar { it.titlecase(Locale.ROOT) } ?: "Alert"
}

private fun backendItem(health: HealthState, format: BannerFormat): BannerItem? = when (health) {
    HealthState.Unknown, HealthState.Healthy, is HealthState.Suspect -> null
    is HealthState.BackOnline -> BannerItem(
        key = BannerKey("backend:recovered@${health.at}"),
        source = BannerSource.BACKEND,
        announce = Announce.POLITE,
        tone = BannerTone.SUCCESS,
        label = null,
        title = "Back online",
        detail = "The server is reachable again. Check that recent changes were saved.",
        description = null,
        started = null,
    )
    is HealthState.Down -> {
        val (kind, title, detail) = when (val outage = health.outage) {
            is Outage.BackendDown -> when (val cause = outage.cause) {
                is DownCause.Http -> Triple("down", "Server is down", "The server answered HTTP ${cause.status}. $NOT_SAVING")
                DownCause.InvalidBody -> Triple("down", "Server is not responding correctly", NOT_SAVING)
            }
            is Outage.Unreachable -> when (outage.reason) {
                UnreachableReason.OFFLINE -> Triple("offline", "You're offline", "No network connection. $NOT_SAVING")
                UnreachableReason.NETWORK -> Triple("unreachable", "Can't reach the server", "Check your connection; the server may be down. $NOT_SAVING")
                UnreachableReason.TIMEOUT -> Triple("unreachable", "Can't reach the server", "The server is not answering in time. $NOT_SAVING")
            }
        }
        BannerItem(
            key = BannerKey("backend:$kind@${health.since}"),
            source = BannerSource.BACKEND,
            announce = Announce.ASSERTIVE,
            tone = BannerTone.DANGER,
            label = null,
            title = title,
            detail = detail,
            description = null,
            started = format.since(Instant.ofEpochMilli(health.since)),
        )
    }
}

private fun alertItem(alert: ClusterAlert, format: BannerFormat): BannerItem = BannerItem(
    key = BannerKey("alert:${alert.key.value}"),
    source = BannerSource.ALERT,
    announce = if (alert.severity == AlertSeverity.Critical) Announce.ASSERTIVE else Announce.POLITE,
    tone = when (alert.severity) {
        AlertSeverity.Critical -> BannerTone.DANGER
        AlertSeverity.Warning -> BannerTone.WARNING
        is AlertSeverity.Other -> BannerTone.INFO
    },
    label = severityLabel(alert.severity),
    title = alert.summary ?: alert.name,
    detail = if (alert.summary != null) alert.name else null,
    description = alert.description,
    started = format.since(alert.startsAt),
)
