package dev.nymph.lyrics.logic.rhyme.pattern

import dev.nymph.lyrics.logic.tags.TagExprError
import dev.nymph.lyrics.logic.tags.TagExprErrorCode
import dev.nymph.lyrics.model.PatternErrorCode
import dev.nymph.lyrics.model.error.ApiError

/** Which pattern-mode call failed. */
enum class PatternOperation { DETECT, SUGGEST, MAKE_SNIPPET, MATERIALIZE }

/** Why a rhyme-pattern call failed; UI state, never thrown. */
sealed interface PatternError {
    val message: String

    /** The server rejected the request with one of the pattern codes (422). */
    data class Rejected(
        val code: PatternErrorCode,
        override val message: String,
        /** Where an `invalid_tag_expression` failed, when the server located it. */
        val expressionError: TagExprError? = null,
    ) : PatternError

    /** 404: the canvas or snippet is gone or not the caller's. */
    data class NotFound(override val message: String) : PatternError

    /** Any other 400 / 422 (a dialect rule, a pydantic bound). */
    data class Invalid(val status: Int, override val message: String) : PatternError

    /** The server is unreachable (rhyme is server-only); nothing was sent. */
    data object NeedsConnection : PatternError {
        override val message: String get() = "Needs connection"
    }

    /** No response: offline, timeout, connection reset. */
    data class Network(override val message: String) : PatternError

    /** 401 / 403 or 5xx. */
    data class Server(val status: Int, override val message: String) : PatternError

    /** A 2xx response the app could not read. */
    data class Unexpected(override val message: String) : PatternError
}

/** A failed call: which one, and why. */
data class PatternFailure(val operation: PatternOperation, val error: PatternError)

fun toPatternError(error: ApiError): PatternError = when (error) {
    ApiError.NeedsConnection -> PatternError.NeedsConnection
    is ApiError.Network -> PatternError.Network(error.message)
    is ApiError.Decoding -> PatternError.Unexpected(error.message)
    is ApiError.NotFound -> PatternError.NotFound(error.message)
    is ApiError.Unauthorized -> PatternError.Server(error.status, error.message)
    is ApiError.Server -> PatternError.Server(error.status, error.message)
    is ApiError.Validation -> when (val code = PatternErrorCode.parse(error.code)) {
        null -> PatternError.Invalid(error.status, error.message)
        else -> PatternError.Rejected(
            code,
            error.message,
            error.expressionError?.let { body -> TagExprErrorCode.parse(body.code)?.let { TagExprError(it, body.position, body.message) } },
        )
    }
}
