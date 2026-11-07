package dev.nymph.lyrics.data.repository

import dev.nymph.lyrics.data.MockServerTest
import dev.nymph.lyrics.model.error.ApiError
import dev.nymph.lyrics.model.error.ApiResult
import dev.nymph.lyrics.model.voice.RecordingId
import dev.nymph.lyrics.model.voice.RecordingStats
import dev.nymph.lyrics.model.voice.Verification
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

private const val CHECKED = """{"id":"r1","snippet_id":"s1","mime_type":"audio/ogg","duration_ms":2000,"byte_size":4,
    "transcript":"tin","split_mode":"single","created_at":"2026-11-01T10:00:00Z","lines":["tin"],"has_timings":false,
    "words":null,"line_spans":null,"language":"en","initial_prompt":null,
    "verified_text":"tin can","verified_at":"2026-11-02T10:00:00Z"}"""
private const val UNCHECKED = """{"id":"r1","snippet_id":"s1","mime_type":"audio/ogg","duration_ms":2000,"byte_size":4,
    "transcript":"tin","split_mode":"single","created_at":"2026-11-01T10:00:00Z","lines":["tin"],"has_timings":false,
    "words":null,"line_spans":null,"language":"en","initial_prompt":null,"verified_text":null,"verified_at":null}"""

class VoiceDatasetRepositoryTest : MockServerTest() {
    private val repo by lazy { HttpVoiceRepository(api) }

    @Test
    fun `save verification puts the text and returns the recording`() = runTest {
        tokens.save("tok")
        enqueue(200, CHECKED)
        val result = repo.saveVerification(RecordingId("r1"), "tin can")
        assertEquals(Verification("tin can", "2026-11-02T10:00:00Z"), (result as ApiResult.Success).value.verification)
        val request = take()
        assertRequest(request, "PUT", "/api/recordings/r1/verification", """{"text":"tin can"}""")
        assertEquals("Bearer tok", request.getHeader("Authorization"))
    }

    @Test
    fun `an invalid verification keeps its code`() = runTest {
        enqueue(422, """{"detail":{"error":"invalid_verification","message":"text is empty"}}""")
        val error = (repo.saveVerification(RecordingId("r1"), " ") as ApiResult.Failure).error
        assertEquals(ApiError.Validation(422, "text is empty", "invalid_verification"), error)
    }

    @Test
    fun `a foreign recording is recording_not_found`() = runTest {
        enqueue(404, """{"detail":{"error":"recording_not_found","message":"Recording not found"}}""")
        val error = (repo.saveVerification(RecordingId("rx"), "a") as ApiResult.Failure).error
        assertEquals(ApiError.NotFound("Recording not found", "recording_not_found"), error)
    }

    @Test
    fun `clear verification sends DELETE and returns the cleared recording`() = runTest {
        enqueue(200, UNCHECKED)
        val result = repo.clearVerification(RecordingId("r1"))
        assertNull((result as ApiResult.Success).value.verification)
        val request = take()
        assertRequest(request, "DELETE", "/api/recordings/r1/verification")
        assertEquals(0L, request.bodySize)
    }

    @Test
    fun `stats reads the caller's totals`() = runTest {
        enqueue(200, """{"total":15,"verified":12,"total_duration_ms":400000,"verified_duration_ms":250000,"with_timings":9}""")
        assertEquals(ApiResult.Success(RecordingStats(15, 12, 400_000, 250_000, 9)), repo.stats())
        assertRequest(take(), "GET", "/api/recordings/stats")
    }

    @Test
    fun `stats failures are typed`() = runTest {
        enqueue(500, """{"detail":"boom"}""")
        assertEquals(ApiResult.Failure(ApiError.Server(500, "boom")), repo.stats())
    }
}
