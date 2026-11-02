package dev.nymph.lyrics.app.voice

import dev.nymph.lyrics.app.ui.SESSION_EXPIRED
import dev.nymph.lyrics.app.voice.recordings.formatDuration
import dev.nymph.lyrics.app.voice.recordings.formatRecordingDate
import dev.nymph.lyrics.app.voice.recordings.transcriptPreview
import dev.nymph.lyrics.logic.voice.HoldReason
import dev.nymph.lyrics.logic.voice.VoiceUploadError
import dev.nymph.lyrics.model.voice.VoiceErrorCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.ZoneId
import java.time.ZoneOffset

class VoiceTextTest {
    @Test
    fun `durations`() {
        assertEquals("0:00", formatDuration(0))
        assertEquals("0:00", formatDuration(-10))
        assertEquals("0:59", formatDuration(59_999))
        assertEquals("1:01", formatDuration(61_234))
        assertEquals("1:00:05", formatDuration(3_605_000))
    }

    @Test
    fun `recording dates in the viewer's zone, raw text when unreadable`() {
        assertEquals("Nov 1, 2026 10:05", formatRecordingDate("2026-11-01T10:05:00Z", ZoneOffset.UTC))
        assertEquals("Nov 1, 2026 11:05", formatRecordingDate("2026-11-01 10:05:00.123000+00:00", ZoneId.of("Europe/Paris")))
        assertEquals("yesterday", formatRecordingDate("yesterday", ZoneOffset.UTC))
    }

    @Test
    fun `transcript preview joins lines and truncates`() {
        assertEquals("hello / world", transcriptPreview("hello\n\n  world "))
        val long = transcriptPreview("a".repeat(200), max = 10)
        assertEquals(10, long.length)
        assertTrue(long.endsWith("…"))
    }

    @Test
    fun `every server code has its own message`() {
        val messages = VoiceErrorCode.entries.map { VoiceUploadError.Rejected(it, 500, "x").userMessage() }
        assertEquals(messages.size, messages.toSet().size)
        assertEquals(SESSION_EXPIRED, VoiceUploadError.SessionExpired.userMessage())
        assertEquals("teapot", VoiceUploadError.Http(418, "teapot").userMessage())
    }

    @Test
    fun `held takes say why and label the retry`() {
        assertEquals("Upload", HoldReason.Interrupted.retryLabel())
        assertEquals("Retry", HoldReason.UploadFailed(VoiceUploadError.Network("x")).retryLabel())
        assertEquals(
            "Speech to text is not set up on this server.",
            HoldReason.UploadFailed(VoiceUploadError.Rejected(VoiceErrorCode.STT_DISABLED, 503, "x")).userMessage(),
        )
    }

    @Test
    fun `permission decisions`() {
        assertEquals(PermissionAction.PROCEED, permissionAction(granted = true, shouldShowRationale = true, firstAsk = true))
        assertEquals(PermissionAction.SHOW_RATIONALE, permissionAction(granted = false, shouldShowRationale = true, firstAsk = true))
        assertEquals(PermissionAction.LAUNCH_REQUEST, permissionAction(granted = false, shouldShowRationale = true, firstAsk = false))
        assertEquals(PermissionAction.LAUNCH_REQUEST, permissionAction(granted = false, shouldShowRationale = false, firstAsk = true))
        assertEquals(true, deniedPermanently(shouldShowRationaleAfterDenial = false))
        assertEquals(false, deniedPermanently(shouldShowRationaleAfterDenial = true))
    }
}
