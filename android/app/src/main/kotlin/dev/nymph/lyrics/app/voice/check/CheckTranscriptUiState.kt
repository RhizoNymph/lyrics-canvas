package dev.nymph.lyrics.app.voice.check

import dev.nymph.lyrics.logic.voice.TranscriptReference
import dev.nymph.lyrics.logic.voice.VerificationInputError
import dev.nymph.lyrics.logic.voice.VoiceUploadError
import dev.nymph.lyrics.logic.voice.wordIndexAt
import dev.nymph.lyrics.model.voice.Recording

/** Why the player cannot play. */
sealed interface PlayerFailure {
    /** The audio could not be fetched. */
    data class Download(val error: VoiceUploadError) : PlayerFailure

    /** The file is on disk but the platform cannot play it. */
    data object Unplayable : PlayerFailure
}

sealed interface PlayerUi {
    /** Fetching the audio. */
    data object Loading : PlayerUi

    data class Failed(val failure: PlayerFailure) : PlayerUi

    /** [positionMs] is within `[0, durationMs]`; [durationMs] is the recording's. */
    data class Ready(val positionMs: Long, val durationMs: Long, val playing: Boolean) : PlayerUi
}

/** The request in flight, if any; at most one at a time. */
enum class CheckAction { IDLE, SAVING, CLEARING }

/** An inline error under the correction field. */
sealed interface CheckError {
    /** Refused before sending (the server would answer `invalid_verification`). */
    data class Input(val error: VerificationInputError) : CheckError

    /** The server refused or could not be reached. */
    data class Server(val error: VoiceUploadError) : CheckError
}

sealed interface CheckTranscriptUiState {
    data object Loading : CheckTranscriptUiState

    /** The recording could not be loaded (`recording_not_found` when it is gone). */
    data class LoadFailed(val error: VoiceUploadError) : CheckTranscriptUiState

    data class Ready(
        val recording: Recording,
        val reference: TranscriptReference,
        /** The correction field. */
        val text: String,
        /** What the field started with; [dirty] compares against it. */
        val baseline: String,
        val player: PlayerUi,
        val action: CheckAction = CheckAction.IDLE,
        val error: CheckError? = null,
        /** Cancel was pressed on a dirty field. */
        val confirmDiscard: Boolean = false,
    ) : CheckTranscriptUiState {
        val dirty: Boolean get() = text != baseline

        val checked: Boolean get() = recording.isChecked

        val busy: Boolean get() = action != CheckAction.IDLE

        /** The word being played (an index into the take's words), only with timings. */
        val currentWord: Int?
            get() {
                val timed = reference as? TranscriptReference.Timed ?: return null
                val position = (player as? PlayerUi.Ready)?.positionMs ?: return null
                return wordIndexAt(timed.words, position)
            }
    }
}

sealed interface CheckTranscriptEvent {
    /** Saved or cancelled: leave the screen. */
    data object Close : CheckTranscriptEvent
}
