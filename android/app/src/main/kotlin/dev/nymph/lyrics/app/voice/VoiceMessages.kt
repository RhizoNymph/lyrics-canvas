package dev.nymph.lyrics.app.voice

import dev.nymph.lyrics.app.ui.SESSION_EXPIRED
import dev.nymph.lyrics.logic.voice.HoldReason
import dev.nymph.lyrics.logic.voice.RecorderFailure
import dev.nymph.lyrics.logic.voice.VoiceUploadError
import dev.nymph.lyrics.model.voice.VoiceErrorCode

/** The text shown for a failed upload, by server code where there is one. */
fun VoiceUploadError.userMessage(): String = when (this) {
    is VoiceUploadError.Rejected -> when (code) {
        VoiceErrorCode.CANVAS_NOT_FOUND -> "This canvas no longer exists."
        VoiceErrorCode.SNIPPET_NOT_FOUND -> "The snippet this take was for no longer exists."
        VoiceErrorCode.RECORDING_NOT_FOUND -> "That recording no longer exists."
        VoiceErrorCode.AUDIO_TOO_LARGE -> "The recording is too long to upload."
        VoiceErrorCode.UNSUPPORTED_AUDIO_TYPE -> "The server does not accept this audio format."
        VoiceErrorCode.INVALID_META -> "The server rejected the take's details."
        VoiceErrorCode.EMPTY_TRANSCRIPT -> "No words were heard in the recording."
        VoiceErrorCode.STT_DISABLED -> "Speech to text is not set up on this server."
        VoiceErrorCode.STT_UNAVAILABLE -> "Speech to text is not available right now. Try again in a moment."
        VoiceErrorCode.STT_FAILED -> "Transcription failed. Try again."
        VoiceErrorCode.INVALID_VERIFICATION -> "The server could not save this transcript: it is empty or too long."
        VoiceErrorCode.EXPORT_TOO_LARGE -> "The dataset is too large to export."
    }
    VoiceUploadError.SessionExpired -> SESSION_EXPIRED
    is VoiceUploadError.Http -> message
    is VoiceUploadError.Network -> "Can't reach the server. Check your connection."
    is VoiceUploadError.Unexpected -> "Unexpected response from the server."
}

fun HoldReason.userMessage(): String = when (this) {
    is HoldReason.UploadFailed -> error.userMessage()
    HoldReason.Interrupted -> "Recording stopped when the app went to the background. Upload what was recorded?"
}

/** The Retry button's label for a held take. */
fun HoldReason.retryLabel(): String = when (this) {
    is HoldReason.UploadFailed -> "Retry"
    HoldReason.Interrupted -> "Upload"
}

fun RecorderFailure.userMessage(): String = when (this) {
    RecorderFailure.START_FAILED -> "The microphone could not be started. Another app may be using it."
    RecorderFailure.NO_AUDIO -> "Nothing was recorded. Try a longer take."
}
