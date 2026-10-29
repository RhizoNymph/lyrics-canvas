package dev.nymph.lyrics.data.status

import dev.nymph.lyrics.model.LyricsJson
import dev.nymph.lyrics.model.status.AlertSeverity
import dev.nymph.lyrics.model.status.AlertsFetch
import dev.nymph.lyrics.model.status.AlertsFetchError
import dev.nymph.lyrics.model.status.ClusterAlert
import dev.nymph.lyrics.model.status.IncidentKey
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/** The result of reading a `/_status/alerts` 200 body. */
sealed interface AlertsParse {
    /**
     * The body was a JSON array. [alerts] are the active ones; [malformed] counts entries that
     * are not usable alerts. Well-formed suppressed or unprocessed alerts are skipped, not counted.
     */
    data class Parsed(val alerts: List<ClusterAlert>, val malformed: Int) : AlertsParse

    /** Not a JSON array (HTML, an object, empty). */
    data object NotAnArray : AlertsParse
}

/** What an Alertmanager v2 entry turned out to be. */
private sealed interface Entry {
    data class Active(val alert: ClusterAlert) : Entry

    /** Well-formed but not firing for the user (`suppressed`, `unprocessed`, ...). */
    data object Inactive : Entry

    data object Malformed : Entry
}

/**
 * Read an Alertmanager v2 alert array defensively. An entry is usable when it is an object with
 * a `labels` object holding a non-blank string `alertname`, an RFC 3339 `startsAt`, and a
 * `status` object with a string `state`. `severity`, `summary` and `description` fall back to
 * [AlertSeverity.Other] / null when missing or of the wrong type; unknown fields are ignored.
 */
fun parseAlerts(body: String): AlertsParse {
    val root = try {
        LyricsJson.parseToJsonElement(body)
    } catch (e: SerializationException) {
        return AlertsParse.NotAnArray
    }
    if (root !is JsonArray) return AlertsParse.NotAnArray
    val entries = root.map(::entryOf)
    return AlertsParse.Parsed(
        alerts = entries.filterIsInstance<Entry.Active>().map { it.alert },
        malformed = entries.count { it == Entry.Malformed },
    )
}

/**
 * The `/_status/alerts` contract (feat/status-alerts-endpoint): 200 + array is the list, 200 `[]`
 * none; 404 or a 200 that is not an array means the endpoint is not here; any 5xx (503
 * `alertmanager_unavailable`, or a proxy error) means unknown; anything else is a typed failure.
 */
fun interpretAlertsResponse(status: Int, body: String): AlertsFetch = when {
    status == 200 -> when (val parsed = parseAlerts(body)) {
        is AlertsParse.Parsed -> AlertsFetch.Alerts(parsed.alerts, parsed.malformed)
        AlertsParse.NotAnArray -> AlertsFetch.Absent
    }
    status == 404 -> AlertsFetch.Absent
    status in 500..599 -> AlertsFetch.Unavailable(status)
    else -> AlertsFetch.Failed(AlertsFetchError.Http(status))
}

private fun entryOf(element: JsonElement): Entry {
    val obj = element as? JsonObject ?: return Entry.Malformed
    val labels = obj["labels"] as? JsonObject ?: return Entry.Malformed
    val name = labels.string("alertname")?.takeIf { it.isNotBlank() } ?: return Entry.Malformed
    val startsAt = obj.string("startsAt")?.let(::instantOrNull) ?: return Entry.Malformed
    val state = (obj["status"] as? JsonObject)?.string("state") ?: return Entry.Malformed
    if (state != "active") return Entry.Inactive
    val annotations = obj["annotations"] as? JsonObject
    val severity = AlertSeverity.fromLabel(labels.string("severity"))
    return Entry.Active(
        ClusterAlert(
            key = incidentKey(obj.string("fingerprint"), labels, startsAt, severity),
            name = name,
            severity = severity,
            summary = annotations?.string("summary")?.takeIf { it.isNotBlank() },
            description = annotations?.string("description")?.takeIf { it.isNotBlank() },
            startsAt = startsAt,
        ),
    )
}

/** Fingerprint when Alertmanager sent one, else the sorted label set; plus start time and severity. */
private fun incidentKey(fingerprint: String?, labels: JsonObject, startsAt: Instant, severity: AlertSeverity): IncidentKey {
    val identity = fingerprint?.takeIf { it.isNotBlank() }
        ?: labels.entries.sortedBy { it.key }.joinToString(",", prefix = "labels:") { (k, v) -> "$k=$v" }
    return IncidentKey("$identity@${startsAt.toEpochMilli()}#${severity.id}")
}

private fun JsonObject.string(name: String): String? = (get(name) as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun instantOrNull(text: String): Instant? = try {
    OffsetDateTime.parse(text).toInstant()
} catch (e: DateTimeParseException) {
    null
}
