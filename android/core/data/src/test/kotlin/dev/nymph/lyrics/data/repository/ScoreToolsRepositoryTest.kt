package dev.nymph.lyrics.data.repository

import dev.nymph.lyrics.data.MockServerTest
import dev.nymph.lyrics.data.contentDispositionFileName
import dev.nymph.lyrics.model.SnippetId
import dev.nymph.lyrics.model.error.ApiError
import dev.nymph.lyrics.model.error.ApiResult
import dev.nymph.lyrics.model.score.DraftMeter
import dev.nymph.lyrics.model.score.ScoreDraftRequest
import dev.nymph.lyrics.model.score.ScoreExportFormat
import dev.nymph.lyrics.model.score.ScoreSnap
import dev.nymph.lyrics.model.voice.RecordingId
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okio.Buffer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.util.UUID

private const val DRAFT = """{"score":{"v":1,"ppq":48,"pickup":0,"meters":[{"bar":0,"num":4,"den":4}],"tempos":[{"bar":0,"bpm":90}],
    "snap":"1/8","lines":[{"notes":[{"s":0,"t":0,"d":24,"a":1}]}]},"bpm_estimated":null,"unplaced":0,"unmatched_words":0}"""

class ScoreToolsRepositoryTest : MockServerTest() {
    private lateinit var dir: File
    private val repo by lazy { HttpScoreToolsRepository(api) }

    @BeforeEach
    fun makeDir() {
        dir = File("build/tmp/score-tools-repository-test/${UUID.randomUUID()}").apply { mkdirs() }
    }

    @AfterEach
    fun removeDir() {
        dir.deleteRecursively()
    }

    @Test
    fun `draft posts the grid body to the snippet's draft route`() = runTest {
        enqueue(200, DRAFT)
        val result = repo.draft(SnippetId("s1"), ScoreDraftRequest.FromGrid(ScoreSnap.EIGHTH, DraftMeter(4, 4), 90.0))
        val draft = (result as ApiResult.Success).value
        assertEquals(24, draft.score.lines.single().notes.single().d)
        assertRequest(take(), "POST", "/api/snippets/s1/score/draft", """{"source":"grid","note":"1/8","meter":{"num":4,"den":4},"bpm":90.0}""")
    }

    @Test
    fun `draft posts a recording body with bpm null`() = runTest {
        enqueue(200, DRAFT)
        repo.draft(SnippetId("s1"), ScoreDraftRequest.FromRecording(RecordingId("r9"), DraftMeter(4, 4), null))
        assertRequest(take(), "POST", "/api/snippets/s1/score/draft", """{"source":"recording","recording_id":"r9","meter":{"num":4,"den":4},"bpm":null}""")
    }

    @Test
    fun `draft errors keep the server's code`() = runTest {
        enqueue(422, """{"detail":{"error":"recording_has_no_timings","message":"This take has no word timings"}}""")
        val result = repo.draft(SnippetId("s1"), ScoreDraftRequest.FromRecording(RecordingId("r9"), DraftMeter(4, 4), null))
        assertEquals(ApiResult.Failure(ApiError.Validation(422, "This take has no word timings", "recording_has_no_timings")), result)
    }

    @Test
    fun `export downloads the file with the format query and the server's file name`() = runTest {
        tokens.save("tok")
        val bytes = byteArrayOf(0x4d, 0x54, 0x68, 0x64, 0, 0, 0, 6)
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "audio/midi")
                .setHeader("Content-Disposition", "attachment; filename=\"Night drive.mid\"")
                .setBody(Buffer().write(bytes)),
        )
        val target = File(dir, "exports/s1.mid")
        val result = repo.export(SnippetId("s1"), ScoreExportFormat.MIDI, target)
        val file = (result as ApiResult.Success).value
        assertEquals(target, file.file)
        assertEquals("Night drive.mid", file.suggestedName)
        assertArrayEquals(bytes, target.readBytes())
        val request = take()
        assertRequest(request, "GET", "/api/snippets/s1/score/export?format=midi")
        assertEquals("Bearer tok", request.getHeader("Authorization"))
        assertEquals("*/*", request.getHeader("Accept"))
    }

    @Test
    fun `export without a content disposition has no suggested name`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("<score-partwise/>"))
        val result = repo.export(SnippetId("s1"), ScoreExportFormat.MUSICXML, File(dir, "s1.musicxml"))
        assertNull((result as ApiResult.Success).value.suggestedName)
        assertEquals("/api/snippets/s1/score/export?format=musicxml", take().path)
    }

    @Test
    fun `export of a snippet without a score is score_not_found and leaves no file`() = runTest {
        enqueue(404, """{"detail":{"error":"score_not_found","message":"This snippet has no score"}}""")
        val target = File(dir, "none.mid")
        val result = repo.export(SnippetId("s1"), ScoreExportFormat.MIDI, target)
        assertEquals(ApiResult.Failure(ApiError.NotFound("This snippet has no score", "score_not_found")), result)
        assertFalse(target.exists())
    }

    @Test
    fun `content disposition file names`() {
        assertEquals("a.mid", contentDispositionFileName("attachment; filename=\"a.mid\""))
        assertEquals("a.mid", contentDispositionFileName("attachment; filename=a.mid"))
        assertEquals("Nuit été.mid", contentDispositionFileName("attachment; filename=\"x.mid\"; filename*=UTF-8''Nuit%20%C3%A9t%C3%A9.mid"))
        assertEquals("dir.mid", contentDispositionFileName("attachment; filename=\"../../dir.mid\""))
        assertNull(contentDispositionFileName("attachment"))
        assertNull(contentDispositionFileName("attachment; filename=\"\""))
        assertNull(contentDispositionFileName(null))
    }
}
