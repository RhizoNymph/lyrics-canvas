package dev.nymph.lyrics.model.status

import java.io.IOException

/** The outcome of one `GET /_status/alerts`. Nothing is thrown. */
sealed interface AlertsFetch {
    /** 200 with a JSON array; empty means none firing (or alerting not configured on the server). */
    data class Alerts(val alerts: List<ClusterAlert>, val malformed: Int) : AlertsFetch

    /** 503 `{"error":"alertmanager_unavailable"}` (or another 5xx): alerts are unknown right now. */
    data class Unavailable(val status: Int) : AlertsFetch

    /** 404, or a 200 that is not a JSON array (an SPA fallback page): no endpoint here. Stay quiet. */
    data object Absent : AlertsFetch

    /** The build has no alerts URL; never fetched. */
    data object Disabled : AlertsFetch

    data class Failed(val error: AlertsFetchError) : AlertsFetch
}

/** Why a fetch produced no answer about alerts. */
sealed interface AlertsFetchError {
    /** A status the contract does not define (not 200, 404 or 5xx). */
    data class Http(val status: Int) : AlertsFetchError

    /** No response. */
    data class Network(val cause: IOException) : AlertsFetchError

    /** No response in time. */
    data class Timeout(val cause: IOException) : AlertsFetchError
}
