package dev.nymph.lyrics.logic.voice

import dev.nymph.lyrics.model.error.ApiError
import dev.nymph.lyrics.model.voice.VoiceErrorCode

/** Why a voice route call failed; UI state, never thrown. */
sealed interface VoiceUploadError {
    /** The server refused with one of the voice codes. */
    data class Rejected(val code: VoiceErrorCode, val status: Int, val serverMessage: String?) : VoiceUploadError

    /** The session expired (401); the app is signing out. */
    data object SessionExpired : VoiceUploadError

    /** Any other HTTP failure (no voice code, or one this client does not know). */
    data class Http(val status: Int, val message: String) : VoiceUploadError

    /** No response: offline, timeout, connection reset. */
    data class Network(val message: String) : VoiceUploadError

    /** A 2xx response the app could not read. */
    data class Unexpected(val message: String) : VoiceUploadError
}

private fun ApiError.status(): Int? = when (this) {
    is ApiError.Unauthorized -> status
    is ApiError.NotFound -> 404
    is ApiError.Validation -> status
    is ApiError.Server -> status
    is ApiError.Network, is ApiError.Decoding, ApiError.NeedsConnection -> null
}

fun toVoiceUploadError(error: ApiError): VoiceUploadError {
    if (error is ApiError.Network || error is ApiError.NeedsConnection) return VoiceUploadError.Network(error.message)
    if (error is ApiError.Decoding) return VoiceUploadError.Unexpected(error.message)
    if (error is ApiError.Unauthorized && error.status == 401) return VoiceUploadError.SessionExpired
    val status = error.status() ?: 0
    val code = VoiceErrorCode.parse(error.code)
    return if (code != null) {
        VoiceUploadError.Rejected(code, status, error.message)
    } else {
        VoiceUploadError.Http(status, error.message)
    }
}
