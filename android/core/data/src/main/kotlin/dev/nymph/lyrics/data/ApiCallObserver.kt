package dev.nymph.lyrics.data

import dev.nymph.lyrics.model.error.ApiError
import dev.nymph.lyrics.model.error.ApiResult
import dev.nymph.lyrics.model.status.DownCause
import dev.nymph.lyrics.model.status.HealthSignal
import dev.nymph.lyrics.model.status.Outage
import dev.nymph.lyrics.model.status.UnreachableReason
import java.io.InterruptedIOException

/**
 * Told the health meaning of every [ApiClient] call that completed (not cancelled), so regular
 * traffic feeds the health monitor without callers knowing. Must be cheap and non-blocking; it
 * runs on the calling coroutine.
 */
fun interface ApiCallObserver {
    fun onCallOutcome(signal: HealthSignal)

    companion object {
        val None: ApiCallObserver = ApiCallObserver { }
    }
}

/** Statuses nginx (or a load balancer) answers with when the backend behind it is gone. */
val GATEWAY_STATUSES: Set<Int> = setOf(502, 503, 504)

/** OkHttp signals every timeout (connect, read, call) with an [InterruptedIOException]. */
fun unreachableReasonOf(cause: Throwable): UnreachableReason =
    if (cause is InterruptedIOException) UnreachableReason.TIMEOUT else UnreachableReason.NETWORK

/**
 * No response is [Outage.Unreachable]; 502/503/504 is [Outage.BackendDown]; any other answer,
 * including a 500 or an unreadable 2xx, came from a live backend and is [HealthSignal.Up].
 */
fun healthSignalOf(result: ApiResult<*>): HealthSignal = when (val error = result.errorOrNull()) {
    null -> HealthSignal.Up
    is ApiError.Network -> HealthSignal.Failed(Outage.Unreachable(unreachableReasonOf(error.cause)))
    is ApiError.Server ->
        if (error.status in GATEWAY_STATUSES) HealthSignal.Failed(Outage.BackendDown(DownCause.Http(error.status))) else HealthSignal.Up
    is ApiError.Unauthorized, is ApiError.NotFound, is ApiError.Validation, is ApiError.Decoding -> HealthSignal.Up
}
