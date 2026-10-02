package dev.nymph.lyrics.data

import dev.nymph.lyrics.model.LyricsJson
import kotlinx.serialization.json.JsonElement
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import java.util.concurrent.TimeUnit

/** A MockWebServer plus an [ApiClient] pointed at it. */
abstract class MockServerTest {
    protected lateinit var server: MockWebServer
    protected lateinit var tokens: InMemoryTokenStore
    protected lateinit var api: ApiClient

    protected val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    @BeforeEach
    fun startServer() {
        server = MockWebServer()
        server.start()
        tokens = InMemoryTokenStore()
        api = ApiClient(server.url("/"), tokens, http)
    }

    @AfterEach
    fun stopServer() {
        server.shutdown()
    }

    protected fun enqueue(status: Int, body: String = "") {
        server.enqueue(MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body))
    }

    protected fun take(): RecordedRequest = server.takeRequest(5, TimeUnit.SECONDS) ?: error("no request reached the server")

    protected fun json(text: String): JsonElement = LyricsJson.parseToJsonElement(text)

    protected fun assertRequest(request: RecordedRequest, method: String, path: String, body: String? = null) {
        assertEquals(method, request.method)
        assertEquals(path, request.path)
        if (body != null) {
            assertEquals(json(body), json(request.body.readUtf8()))
            assertEquals("application/json; charset=utf-8", request.getHeader("Content-Type"))
        }
    }
}
