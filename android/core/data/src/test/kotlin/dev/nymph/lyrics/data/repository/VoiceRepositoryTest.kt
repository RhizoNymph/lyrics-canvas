package dev.nymph.lyrics.data.repository

import dev.nymph.lyrics.data.Bodies
import dev.nymph.lyrics.data.MockServerTest
import dev.nymph.lyrics.model.CanvasId
import dev.nymph.lyrics.model.SnippetId
import dev.nymph.lyrics.model.error.ApiError
import dev.nymph.lyrics.model.error.ApiResult
import dev.nymph.lyrics.model.voice.AudioMime
import dev.nymph.lyrics.model.voice.RecordingId
import dev.nymph.lyrics.model.voice.SplitMode
import dev.nymph.lyrics.model.voice.VoiceTakeMeta
import dev.nymph.lyrics.model.voice.VoiceTakeTarget
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.util.UUID

private const val RECORDING = """{"id":"r1","snippet_id":"s1","mime_type":"audio/ogg","duration_ms":2000,"byte_size":4,
    "transcript":"tin","split_mode":"single","created_at":"2026-11-01T10:00:00Z"}"""
private const val TAKE = """{"snippet":${Bodies.SNIPPET},"recording":$RECORDING,"lines":["tin"],"split_mode":"single","created":true}"""

class VoiceRepositoryTest : MockServerTest() {
    /** Under the module's build dir rather than java.io.tmpdir, which may be read-only (sandboxed builds). */
    private lateinit var dir: File

    @BeforeEach
    fun makeDir() {
        dir = File("build/tmp/voice-repository-test/${UUID.randomUUID()}").apply { mkdirs() }
    }

    @AfterEach
    fun removeDir() {
        dir.deleteRecursively()
    }

    private val repo by lazy { HttpVoiceRepository(api) }

    @Test
    fun `upload posts a multipart form with the audio part and the meta json`() = runTest {
        tokens.save("tok")
        val audio = File(dir, "take.ogg").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        enqueue(201, TAKE)
        val meta = VoiceTakeMeta.of(listOf(500), 2_000, VoiceTakeTarget.New(1.0, 2.0))
        val result = repo.uploadTake(CanvasId("c1"), audio, AudioMime.OGG, meta)

        val value = (result as ApiResult.Success).value
        assertTrue(value.created)
        assertEquals(SplitMode.SINGLE, value.splitMode)
        assertEquals(RecordingId("r1"), value.recording.id)
        assertEquals(listOf("tin"), value.lines)

        val request = take()
        assertEquals("POST", request.method)
        assertEquals("/api/canvases/c1/voice-takes", request.path)
        assertEquals("Bearer tok", request.getHeader("Authorization"))
        val contentType = request.getHeader("Content-Type")!!
        assertTrue(contentType.startsWith("multipart/form-data; boundary="), contentType)
        val body = request.body.readUtf8()
        assertTrue(body.contains("""Content-Disposition: form-data; name="audio"; filename="take.ogg""""), body)
        assertTrue(body.contains("Content-Type: audio/ogg"), body)
        assertTrue(body.contains("""Content-Disposition: form-data; name="meta""""), body)
        assertTrue(
            body.contains("""{"breaks_ms":[500],"duration_ms":2000,"target":{"kind":"new","position_x":1.0,"position_y":2.0}}"""),
            body,
        )
    }

    @Test
    fun `an mp4 take is sent as audio mp4`() = runTest {
        val audio = File(dir, "take.m4a").apply { writeBytes(byteArrayOf(9)) }
        enqueue(201, TAKE)
        repo.uploadTake(CanvasId("c1"), audio, AudioMime.MP4, VoiceTakeMeta.of(emptyList(), 10, VoiceTakeTarget.Append(SnippetId("s1"))))
        val body = take().body.readUtf8()
        assertTrue(body.contains("""filename="take.m4a""""), body)
        assertTrue(body.contains("Content-Type: audio/mp4"), body)
        assertTrue(body.contains(""""target":{"kind":"append","snippet_id":"s1"}"""), body)
    }

    @Test
    fun `upload errors keep the server's code`() = runTest {
        val audio = File(dir, "take.ogg").apply { writeBytes(byteArrayOf(1)) }
        val meta = VoiceTakeMeta.of(emptyList(), 10, VoiceTakeTarget.New(0.0, 0.0))
        enqueue(503, """{"detail":{"error":"stt_disabled","message":"Speech to text is not configured"}}""")
        val error = (repo.uploadTake(CanvasId("c1"), audio, AudioMime.OGG, meta) as ApiResult.Failure).error
        assertEquals(ApiError.Server(503, "Speech to text is not configured", "stt_disabled"), error)

        enqueue(413, """{"detail":{"error":"audio_too_large","message":"max 25 MiB"}}""")
        assertEquals("audio_too_large", (repo.uploadTake(CanvasId("c1"), audio, AudioMime.OGG, meta) as ApiResult.Failure).error.code)
    }

    @Test
    fun `recordings lists a snippet's takes`() = runTest {
        enqueue(200, "[$RECORDING]")
        val list = (repo.recordings(SnippetId("s1")) as ApiResult.Success).value
        assertEquals(listOf(RecordingId("r1")), list.map { it.id })
        assertRequest(take(), "GET", "/api/snippets/s1/recordings")
    }

    @Test
    fun `download writes the bytes to the target file`() = runTest {
        tokens.save("tok")
        val bytes = byteArrayOf(0x4f, 0x67, 0x67, 0x53, 0, 1, 2)
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "audio/ogg").setBody(Buffer().write(bytes)))
        val target = File(dir, "cache/recording-r1.ogg")
        val result = repo.downloadAudio(RecordingId("r1"), target)
        assertEquals(ApiResult.Success(target), result)
        assertArrayEquals(bytes, target.readBytes())
        assertFalse(File(dir, "cache/recording-r1.ogg.part").exists())
        val request = take()
        assertRequest(request, "GET", "/api/recordings/r1/audio")
        assertEquals("Bearer tok", request.getHeader("Authorization"))
        assertEquals("*/*", request.getHeader("Accept"))
    }

    @Test
    fun `a failed download leaves no file and maps the error`() = runTest {
        enqueue(404, """{"detail":{"error":"recording_not_found","message":"Recording not found"}}""")
        val target = File(dir, "r404.ogg")
        val result = repo.downloadAudio(RecordingId("r404"), target)
        assertEquals(ApiResult.Failure(ApiError.NotFound("Recording not found", "recording_not_found")), result)
        assertFalse(target.exists())
    }

    @Test
    fun `a dropped download is a network error with no partial file`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(Buffer().write(ByteArray(64 * 1024))).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY),
        )
        val target = File(dir, "cut.ogg")
        val result = repo.downloadAudio(RecordingId("r1"), target)
        assertTrue((result as ApiResult.Failure).error is ApiError.Network, "was $result")
        assertFalse(target.exists())
        assertFalse(File(dir, "cut.ogg.part").exists())
    }

    @Test
    fun `delete sends DELETE to the recording`() = runTest {
        enqueue(204)
        assertEquals(ApiResult.Success(Unit), repo.deleteRecording(RecordingId("r1")))
        assertRequest(take(), "DELETE", "/api/recordings/r1")
    }
}
