package dev.nymph.lyrics.logic.rhyme

import dev.nymph.lyrics.model.error.ApiError

enum class RhymeErrorKind {
    /** No response (offline, timeout). */
    NETWORK,

    /** 404: canvas or snippet not owned / missing. */
    NOT_FOUND,

    /** 400 / 422: bad k, stress string, empty anchors. */
    INVALID_REQUEST,

    /** 5xx or any other HTTP failure. */
    SERVER,

    /** A non-HTTP failure (an unreadable response). */
    UNKNOWN,
}

enum class RhymeOperation { QUERY, MATERIALIZE }

/** Typed failure for rhyme-view API calls; held as UI state, never thrown. */
data class RhymeError(
    val kind: RhymeErrorKind,
    val operation: RhymeOperation,
    val message: String,
    val status: Int? = null,
)

fun toRhymeError(error: ApiError, operation: RhymeOperation): RhymeError = when (error) {
    is ApiError.Network -> RhymeError(RhymeErrorKind.NETWORK, operation, error.message, null)
    is ApiError.NotFound -> RhymeError(RhymeErrorKind.NOT_FOUND, operation, error.message, 404)
    is ApiError.Validation -> RhymeError(RhymeErrorKind.INVALID_REQUEST, operation, error.message, error.status)
    is ApiError.Unauthorized -> RhymeError(RhymeErrorKind.SERVER, operation, error.message, error.status)
    is ApiError.Server -> RhymeError(RhymeErrorKind.SERVER, operation, error.message, error.status)
    is ApiError.Decoding -> RhymeError(RhymeErrorKind.UNKNOWN, operation, error.message, null)
}

/** An existing RhymeError passes through unchanged (keeps its original operation). */
@Suppress("UNUSED_PARAMETER")
fun toRhymeError(error: RhymeError, operation: RhymeOperation): RhymeError = error
