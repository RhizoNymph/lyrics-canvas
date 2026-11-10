package dev.nymph.lyrics.app.voice.check

import dev.nymph.lyrics.app.voice.userMessage
import dev.nymph.lyrics.logic.voice.VerificationInputError

fun CheckError.userMessage(): String = when (this) {
    is CheckError.Input -> when (val e = error) {
        VerificationInputError.Empty -> "Write what was said before saving."
        is VerificationInputError.TooLong -> "The transcript is too long: ${e.codePoints} characters, at most ${e.max}."
    }
    is CheckError.Server -> error.userMessage()
}

fun PlayerFailure.userMessage(): String = when (this) {
    is PlayerFailure.Download -> error.userMessage()
    PlayerFailure.Unplayable -> "This recording could not be played."
}

/** Under the correction field (contract wording). */
const val CORRECTION_HINT: String =
    "Write exactly what was said: this trains transcription, not your lyrics. Editing here does not change the snippet."
