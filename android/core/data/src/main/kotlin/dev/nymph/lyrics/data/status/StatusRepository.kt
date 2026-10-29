package dev.nymph.lyrics.data.status

import dev.nymph.lyrics.data.await
import dev.nymph.lyrics.data.unreachableReasonOf
import dev.nymph.lyrics.model.LyricsJson
import dev.nymph.lyrics.model.status.AlertsFetch
import dev.nymph.lyrics.model.status.AlertsFetchError
import dev.nymph.lyrics.model.status.DownCause
import dev.nymph.lyrics.model.status.HealthSignal
import dev.nymph.lyrics.model.status.Outage
import dev.nymph.lyrics.model.status.UnreachableReason
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * Where cluster alerts live. `/_status/alerts` is served only by the frontend nginx origin, not
 * by the backend; see docs/features/android_health_banner.md for how a build chooses.
 */
sealed interface AlertsEndpoint {
    /** Alerts are never fetched. */
    data object Disabled : AlertsEndpoint

    /** The full URL of `GET /_status/alerts`. */
    data class At(val url: HttpUrl) : AlertsEndpoint

    companion object {
        /** The value of the `statusAlertsUrl` build property that turns alerts off. */
        const val DISABLED_VALUE: String = "none"

        /**
         * Resolve the `statusAlertsUrl` build property against the API base URL:
         * blank is `<apiBase>/_status/alerts` (right when the API base is the nginx origin; a
         * backend-direct base answers 404 there, which stays quiet), `none` is [Disabled], an
         * http(s) URL is used as given, and anything else is null (a misconfigured build).
         */
        fun resolve(raw: String, apiBase: HttpUrl): AlertsEndpoint? {
            val text = raw.trim()
            return when {
                text.isEmpty() -> At(apiBase.newBuilder().addPathSegment("_status").addPathSegment("alerts").build())
                text == DISABLED_VALUE -> Disabled
                else -> text.toHttpUrlOrNull()?.let(::At)
            }
        }
    }
}

/** Server health and cluster alerts. Both calls are unauthenticated and never throw. */
interface StatusRepository {
    /** `GET /api/health`: [HealthSignal.Up] only for 200 `{"status":"ok"}`. */
    suspend fun probeHealth(): HealthSignal

    suspend fun fetchAlerts(): AlertsFetch
}

/** The probe contract: 200 `{"status":"ok"}` is up; any other status or body is down. */
fun healthSignalFromProbe(status: Int, body: String): HealthSignal = when {
    status != 200 -> HealthSignal.Failed(Outage.BackendDown(DownCause.Http(status)))
    isStatusOk(body) -> HealthSignal.Up
    else -> HealthSignal.Failed(Outage.BackendDown(DownCause.InvalidBody))
}

private fun isStatusOk(body: String): Boolean {
    val root = try {
        LyricsJson.parseToJsonElement(body)
    } catch (e: SerializationException) {
        return false
    }
    val status = (root as? JsonObject)?.get("status") as? JsonPrimitive ?: return false
    return status.isString && status.content == "ok"
}

/**
 * Talks to OkHttp directly rather than through [dev.nymph.lyrics.data.ApiClient], so probes are not
 * also reported through the client's health observer (which would count each probe twice) and so
 * the alerts URL can live on a different origin than the API.
 */
class HttpStatusRepository(
    baseUrl: HttpUrl,
    private val alerts: AlertsEndpoint,
    private val okHttpClient: OkHttpClient,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : StatusRepository {
    private val healthUrl: HttpUrl = baseUrl.newBuilder().addPathSegment("api").addPathSegment("health").build()

    override suspend fun probeHealth(): HealthSignal = when (val answer = get(healthUrl)) {
        is Answer.NoResponse -> HealthSignal.Failed(Outage.Unreachable(unreachableReasonOf(answer.cause)))
        is Answer.Response -> healthSignalFromProbe(answer.status, answer.body)
    }

    override suspend fun fetchAlerts(): AlertsFetch {
        val url = when (alerts) {
            AlertsEndpoint.Disabled -> return AlertsFetch.Disabled
            is AlertsEndpoint.At -> alerts.url
        }
        return when (val answer = get(url)) {
            is Answer.NoResponse -> AlertsFetch.Failed(
                when (unreachableReasonOf(answer.cause)) {
                    UnreachableReason.TIMEOUT -> AlertsFetchError.Timeout(answer.cause)
                    UnreachableReason.NETWORK, UnreachableReason.OFFLINE -> AlertsFetchError.Network(answer.cause)
                },
            )
            is Answer.Response -> interpretAlertsResponse(answer.status, answer.body)
        }
    }

    private sealed interface Answer {
        data class Response(val status: Int, val body: String) : Answer

        data class NoResponse(val cause: IOException) : Answer
    }

    private suspend fun get(url: HttpUrl): Answer {
        val request = Request.Builder().url(url).get().header("Accept", "application/json").build()
        val response = try {
            okHttpClient.newCall(request).await()
        } catch (e: IOException) {
            return Answer.NoResponse(e)
        }
        return withContext(ioDispatcher) {
            response.use {
                try {
                    Answer.Response(it.code, it.body.string())
                } catch (e: IOException) {
                    Answer.NoResponse(e)
                }
            }
        }
    }
}
