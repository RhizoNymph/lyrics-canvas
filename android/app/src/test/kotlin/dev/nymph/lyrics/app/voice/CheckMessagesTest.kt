package dev.nymph.lyrics.app.voice

import dev.nymph.lyrics.app.voice.check.CheckError
import dev.nymph.lyrics.app.voice.check.PlayerFailure
import dev.nymph.lyrics.app.voice.check.userMessage
import dev.nymph.lyrics.logic.voice.VerificationInputError
import dev.nymph.lyrics.logic.voice.VoiceUploadError
import dev.nymph.lyrics.model.voice.VoiceErrorCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CheckMessagesTest {
    @Test
    fun `every check error has its own text`() {
        assertEquals("Write what was said before saving.", CheckError.Input(VerificationInputError.Empty).userMessage())
        assertEquals(
            "The transcript is too long: 10001 characters, at most 10000.",
            CheckError.Input(VerificationInputError.TooLong(10_001, 10_000)).userMessage(),
        )
        assertEquals(
            "The server could not save this transcript: it is empty or too long.",
            CheckError.Server(VoiceUploadError.Rejected(VoiceErrorCode.INVALID_VERIFICATION, 422, "x")).userMessage(),
        )
        assertEquals(
            "That recording no longer exists.",
            CheckError.Server(VoiceUploadError.Rejected(VoiceErrorCode.RECORDING_NOT_FOUND, 404, null)).userMessage(),
        )
        assertEquals(
            "The dataset is too large to export.",
            VoiceUploadError.Rejected(VoiceErrorCode.EXPORT_TOO_LARGE, 413, null).userMessage(),
        )
    }

    @Test
    fun `player failures`() {
        assertEquals("This recording could not be played.", PlayerFailure.Unplayable.userMessage())
        assertEquals("Can't reach the server. Check your connection.", PlayerFailure.Download(VoiceUploadError.Network("x")).userMessage())
    }
}
