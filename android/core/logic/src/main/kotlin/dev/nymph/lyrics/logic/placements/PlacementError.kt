package dev.nymph.lyrics.logic.placements

import dev.nymph.lyrics.model.PlacementErrorCode
import dev.nymph.lyrics.model.error.ApiError

/** Why a rename, transfer, removal or delete failed; UI state, never thrown. */
sealed interface PlacementError {
    /** The server refused with one of the placement codes ([PlacementErrorCode]). */
    data class Rejected(val code: PlacementErrorCode, val status: Int, val serverMessage: String?) : PlacementError

    /** The session expired (401); the app is signing out. */
    data object SessionExpired : PlacementError

    /** Any other HTTP failure (no placement code, or one this client does not know). */
    data class Http(val status: Int, val message: String) : PlacementError

    /** No response: offline, timeout, connection reset. */
    data class Network(val message: String) : PlacementError

    /** A 2xx response the app could not read. */
    data class Unexpected(val message: String) : PlacementError
}

fun toPlacementError(error: ApiError): PlacementError {
    val status = when (error) {
        is ApiError.Network -> return PlacementError.Network(error.message)
        ApiError.NeedsConnection -> return PlacementError.Network(error.message)
        is ApiError.Decoding -> return PlacementError.Unexpected(error.message)
        is ApiError.Unauthorized -> if (error.status == 401) return PlacementError.SessionExpired else error.status
        is ApiError.NotFound -> 404
        is ApiError.Validation -> error.status
        is ApiError.Server -> error.status
    }
    val code = PlacementErrorCode.parse(error.code) ?: return PlacementError.Http(status, error.message)
    return PlacementError.Rejected(code, status, error.message)
}
