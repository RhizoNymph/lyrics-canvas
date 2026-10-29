package dev.nymph.lyrics.model.status

import java.time.Instant

/** `labels.severity` of an Alertmanager alert. */
sealed interface AlertSeverity {
    /** Sort rank: critical first, then warning, then everything else. */
    val rank: Int

    /** Stable identifier, part of the incident key (a severity change is a new incident). */
    val id: String

    data object Critical : AlertSeverity {
        override val rank: Int get() = 0
        override val id: String get() = "critical"
    }

    data object Warning : AlertSeverity {
        override val rank: Int get() = 1
        override val id: String get() = "warning"
    }

    /** Any other label value ("info", "none", ...), or null when the label is missing or not a string. */
    data class Other(val label: String?) : AlertSeverity {
        override val rank: Int get() = 2
        override val id: String get() = "other"
    }

    companion object {
        fun fromLabel(label: String?): AlertSeverity = when (label?.lowercase()) {
            "critical" -> Critical
            "warning" -> Warning
            else -> Other(label)
        }
    }
}

/**
 * Identity of one firing incident: the alert's identity (fingerprint, or name plus sorted labels),
 * its start time and severity. A dismissed incident stays hidden; the same alert firing again
 * later, or changing severity, is a new incident.
 */
@JvmInline
value class IncidentKey(val value: String)

/**
 * One firing (Alertmanager state `active`) alert from `/_status/alerts`. Only alerts with a
 * non-blank name and a valid start time are represented; the parser drops the rest.
 */
data class ClusterAlert(
    val key: IncidentKey,
    /** `labels.alertname`; never blank. */
    val name: String,
    val severity: AlertSeverity,
    /** `annotations.summary`, null when missing or blank. */
    val summary: String?,
    /** `annotations.description`, null when missing or blank. */
    val description: String?,
    val startsAt: Instant,
) {
    init {
        require(name.isNotBlank()) { "alertname must not be blank" }
    }
}
