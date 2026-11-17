package dev.nymph.lyrics.logic.score.tools

import dev.nymph.lyrics.model.error.ApiError
import dev.nymph.lyrics.model.score.NOT_SYNCED_ERROR_CODE
import dev.nymph.lyrics.model.score.ScoreDraftResponse
import dev.nymph.lyrics.model.score.Score
import dev.nymph.lyrics.model.score.ScoreMeter
import dev.nymph.lyrics.model.score.ScoreSnap
import dev.nymph.lyrics.model.score.ScoreTempo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.IOException

class ScoreToolErrorTest {
    private fun kind(error: ApiError) = toScoreToolError(error).kind

    @Test
    fun `server codes map to their kinds`() {
        assertEquals(ScoreToolErrorKind.SCORE_NOT_FOUND, kind(ApiError.NotFound("x", "score_not_found")))
        assertEquals(ScoreToolErrorKind.SNIPPET_NOT_FOUND, kind(ApiError.NotFound("x", "snippet_not_found")))
        assertEquals(ScoreToolErrorKind.RECORDING_NOT_FOUND, kind(ApiError.NotFound("x", "recording_not_found")))
        assertEquals(ScoreToolErrorKind.RECORDING_HAS_NO_TIMINGS, kind(ApiError.Validation(422, "x", "recording_has_no_timings")))
        assertEquals(ScoreToolErrorKind.DRAFT_EMPTY, kind(ApiError.Validation(422, "x", "draft_empty")))
        assertEquals(ScoreToolErrorKind.INVALID_SETTINGS, kind(ApiError.Validation(422, "Invalid request (422)", "invalid_score")))
        assertEquals(ScoreToolErrorKind.INVALID_SETTINGS, kind(ApiError.Validation(422, "bpm: too big")))
    }

    @Test
    fun `transport and gate failures`() {
        assertEquals(ScoreToolErrorKind.NEEDS_CONNECTION, kind(ApiError.NeedsConnection))
        assertEquals(ScoreToolErrorKind.NETWORK, kind(ApiError.Network(IOException("reset"))))
        assertEquals(ScoreToolErrorKind.NOT_SYNCED, kind(ApiError.Validation(409, "wait", NOT_SYNCED_ERROR_CODE)))
        assertEquals(ScoreToolErrorKind.UNAUTHORIZED, kind(ApiError.Unauthorized(401, null)))
        assertEquals(ScoreToolErrorKind.SERVER, kind(ApiError.Server(503, null)))
        assertEquals(ScoreToolErrorKind.UNKNOWN, kind(ApiError.Decoding(IllegalStateException("bad"))))
        // A 404 without a code: an old server without the route, or the snippet is gone.
        assertEquals(ScoreToolErrorKind.NOT_FOUND, kind(ApiError.NotFound("Not Found")))
    }

    @Test
    fun `messages are fixed for known kinds and the server's for server errors`() {
        assertEquals("Making a draft or exporting needs a connection", toScoreToolError(ApiError.NeedsConnection).message)
        assertEquals("This snippet has no score to export yet", toScoreToolError(ApiError.NotFound("x", "score_not_found")).message)
        assertEquals("This take has no word timings; record it again to draft from it", toScoreToolError(ApiError.Validation(422, "x", "recording_has_no_timings")).message)
        assertEquals("Upstream down", toScoreToolError(ApiError.Server(502, "Upstream down")).message)
        assertEquals(404, toScoreToolError(ApiError.NotFound("x", "score_not_found")).status)
        ScoreToolErrorKind.entries.forEach { k -> assert(k.defaultMessage.isNotBlank()) { "$k" } }
    }

    private val score = Score(meters = listOf(ScoreMeter(0, 4, 4)), tempos = listOf(ScoreTempo(0, 90.0)), snap = ScoreSnap.EIGHTH, lines = emptyList())

    @Test
    fun `draft summaries`() {
        assertEquals("Every syllable placed", draftSummary(ScoreDraftResponse(score)))
        assertEquals(
            "Estimated tempo 101.5 bpm · 2 syllables unplaced · 1 word not matched",
            draftSummary(ScoreDraftResponse(score, bpmEstimated = 101.5, unplaced = 2, unmatchedWords = 1)),
        )
        assertEquals("Estimated tempo 96 bpm · 1 syllable unplaced · 3 words not matched", draftSummary(ScoreDraftResponse(score, 96.0, 1, 3)))
    }

    @Test
    fun `export file names`() {
        assertEquals("Night drive.mid", exportFileName("Night drive.mid", dev.nymph.lyrics.model.score.ScoreExportFormat.MIDI, "snippet"))
        assertEquals("Night drive.mid", exportFileName("Night drive", dev.nymph.lyrics.model.score.ScoreExportFormat.MIDI, "snippet"))
        assertEquals("a_b_c.musicxml", exportFileName("a/b:c.musicxml", dev.nymph.lyrics.model.score.ScoreExportFormat.MUSICXML, "snippet"))
        assertEquals("snippet-1234abcd.mid", exportFileName(null, dev.nymph.lyrics.model.score.ScoreExportFormat.MIDI, "snippet-1234abcd"))
        assertEquals("snippet.mid", exportFileName("  ", dev.nymph.lyrics.model.score.ScoreExportFormat.MIDI, "snippet"))
        assertEquals("x.MID", exportFileName("x.MID", dev.nymph.lyrics.model.score.ScoreExportFormat.MIDI, "snippet"))
    }
}
