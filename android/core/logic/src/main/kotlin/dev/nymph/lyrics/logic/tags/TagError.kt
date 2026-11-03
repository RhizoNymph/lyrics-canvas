package dev.nymph.lyrics.logic.tags

import dev.nymph.lyrics.model.TagErrorCode
import dev.nymph.lyrics.model.error.ApiError

/** Why a tag, preset or link call failed; UI state, never thrown. */
sealed interface TagError {
    /**
     * The server refused with one of the tag codes. [expressionError] locates an
     * `invalid_tag_expression` (null for other codes, or a location code this client does not know).
     */
    data class Rejected(
        val code: TagErrorCode,
        val status: Int,
        val serverMessage: String?,
        val expressionError: TagExprError?,
    ) : TagError

    /** The session expired (401); the app is signing out. */
    data object SessionExpired : TagError

    /** Any other HTTP failure (no tag code, or one this client does not know). */
    data class Http(val status: Int, val message: String) : TagError

    /** No response: offline, timeout, connection reset. */
    data class Network(val message: String) : TagError

    /** A 2xx response the app could not read. */
    data class Unexpected(val message: String) : TagError
}

fun toTagError(error: ApiError): TagError {
    val status = when (error) {
        is ApiError.Network -> return TagError.Network(error.message)
        is ApiError.Decoding -> return TagError.Unexpected(error.message)
        is ApiError.Unauthorized -> if (error.status == 401) return TagError.SessionExpired else error.status
        is ApiError.NotFound -> 404
        is ApiError.Validation -> error.status
        is ApiError.Server -> error.status
    }
    val code = TagErrorCode.parse(error.code) ?: return TagError.Http(status, error.message)
    val location = (error as? ApiError.Validation)?.expressionError?.let { body ->
        TagExprErrorCode.parse(body.code)?.let { TagExprError(it, body.position, body.message) }
    }
    return TagError.Rejected(code, status, error.message, location)
}
