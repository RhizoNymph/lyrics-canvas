package dev.nymph.lyrics.logic.voice

import dev.nymph.lyrics.model.SnippetId
import dev.nymph.lyrics.model.voice.Recording
import dev.nymph.lyrics.model.voice.RecordingId
import dev.nymph.lyrics.model.voice.SplitMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class VerificationTextTest {
    private fun recording(lines: List<String>, verifiedText: String? = null, verifiedAt: String? = null) = Recording(
        RecordingId("r1"), SnippetId("s1"), "audio/ogg", 2_000, 10, "whisper one\nwhisper two", SplitMode.TAPS, "2026-11-01T10:00:00Z",
        lines = lines, verifiedText = verifiedText, verifiedAt = verifiedAt,
    )

    @Test
    fun `the field starts with the checked text when there is one`() {
        assertEquals("said one\nsaid two", initialCorrectionText(recording(listOf("a", "b"), "said one\nsaid two", "2026-11-02T00:00:00Z")))
    }

    @Test
    fun `otherwise with the whisper lines`() {
        assertEquals("a\n\nb", initialCorrectionText(recording(listOf("a", "", "b"))))
        assertEquals("whisper one\nwhisper two", initialCorrectionText(recording(emptyList())))
        // half a verification is not one
        assertEquals("a", initialCorrectionText(recording(listOf("a"), verifiedText = "x")))
    }

    @Test
    fun `normalising follows the server`() {
        assertEquals("a\nb", normalizeVerification("a\r\nb"))
        assertEquals("a\nb", normalizeVerification("a\rb"))
        assertEquals("a\nb", normalizeVerification("a  \t\nb  "))
        assertEquals("a\nb", normalizeVerification("\n\n  \na\nb\n \n\n"))
        assertEquals("a\n\nb", normalizeVerification("a\n\n\n \n\nb"))
        assertEquals("  a", normalizeVerification("  a"))
        assertEquals("", normalizeVerification(" \n\t\r\n"))
    }

    @Test
    fun `an empty or overlong text is refused before sending`() {
        assertEquals(VerificationInput.Invalid(VerificationInputError.Empty), validateVerification(" \n \n"))
        val max = "x".repeat(MAX_VERIFICATION_CODE_POINTS)
        assertEquals(VerificationInput.Valid(max), validateVerification(max))
        assertEquals(
            VerificationInput.Invalid(VerificationInputError.TooLong(MAX_VERIFICATION_CODE_POINTS + 1, MAX_VERIFICATION_CODE_POINTS)),
            validateVerification(max + "y"),
        )
        // code points, not UTF-16 units
        val emoji = "🎤".repeat(MAX_VERIFICATION_CODE_POINTS)
        assertEquals(VerificationInput.Valid(emoji), validateVerification(emoji))
        assertEquals(VerificationInput.Valid("a\nb"), validateVerification("a  \r\nb\n\n"))
    }
}
