package dev.nymph.lyrics.logic.canvas

import dev.nymph.lyrics.model.error.ApiError

/** Why a block merge failed; UI state, never thrown. */
sealed interface MergeError {
    val message: String

    /** Fewer than two distinct snippets selected; caught before any request. */
    data object TooFewSnippets : MergeError {
        override val message: String get() = "Select at least two snippets to merge"
    }

    /** 404: a snippet is gone or not in this canvas. */
    data class NotFound(override val message: String) : MergeError

    /** 400 / 422 from the server. */
    data class Invalid(override val message: String) : MergeError

    /** Any other HTTP failure, including an expired session. */
    data class Server(val status: Int, override val message: String) : MergeError

    data class Network(override val message: String) : MergeError

    /** The server answered 2xx with a body the app could not read. */
    data class Unknown(override val message: String) : MergeError
}

fun toMergeError(error: ApiError): MergeError = when (error) {
    is ApiError.NotFound -> MergeError.NotFound(error.message)
    is ApiError.Validation -> MergeError.Invalid(error.message)
    is ApiError.Unauthorized -> MergeError.Server(error.status, error.message)
    is ApiError.Server -> MergeError.Server(error.status, error.message)
    is ApiError.Network -> MergeError.Network(error.message)
    is ApiError.Decoding -> MergeError.Unknown(error.message)
}
