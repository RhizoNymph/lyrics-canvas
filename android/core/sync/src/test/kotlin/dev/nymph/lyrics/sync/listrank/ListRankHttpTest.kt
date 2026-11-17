package dev.nymph.lyrics.sync.listrank

import dev.nymph.lyrics.data.ApiClient
import dev.nymph.lyrics.data.InMemoryTokenStore
import dev.nymph.lyrics.model.LyricsJson
import dev.nymph.lyrics.model.PositionUpdate
import dev.nymph.lyrics.model.Snippet
import dev.nymph.lyrics.model.SnippetCreateRequest
import dev.nymph.lyrics.model.SnippetId
import dev.nymph.lyrics.sync.Fx
import dev.nymph.lyrics.sync.engine.HttpOpSender
import dev.nymph.lyrics.sync.engine.SyncEngine
import dev.nymph.lyrics.sync.engine.Wake
import dev.nymph.lyrics.sync.store.InMemoryStoreFile
import dev.nymph.lyrics.sync.store.LocalData
import dev.nymph.lyrics.sync.store.LocalStore
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

/** What goes on the wire for list ranks, through the engine and a fake server. */
class ListRankHttpTest {
    private lateinit var server: MockWebServer
    private lateinit var api: ApiClient

    @BeforeEach
    fun start() {
        server = MockWebServer().apply { start() }
        val http = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS).build()
        api = ApiClient(server.url("/"), InMemoryTokenStore(), http)
    }

    @AfterEach
    fun stop() = server.shutdown()

    private fun respond(status: Int, body: String = "") =
        server.enqueue(MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body))

    private fun snippetBody(s: Snippet) = LyricsJson.encodeToString(Snippet.serializer(), s)

    private fun RecordedRequest.json(): JsonObject = LyricsJson.parseToJsonElement(body.clone().readUtf8()).jsonObject

    /** Run one pass over [data]'s outbox with [answers] queued; the snapshot after it is refused. */
    private fun pass(data: LocalData, answers: () -> Unit): Pair<LocalData, List<RecordedRequest>> = runBlocking {
        val store = LocalStore(InMemoryStoreFile(data))
        answers()
        respond(503, """{"detail":"snapshot not under test"}""")
        SyncEngine(store, HttpOpSender(api)).pass(Wake.EDIT)
        val requests = generateSequence { server.takeRequest(100, TimeUnit.MILLISECONDS) }.toList()
        store.data.value to requests.filterNot { it.path == "/api/sync/snapshot" }
    }

    @Test
    fun `an offline create sends the key it chose and keeps the server's answer`() {
        val edits = Fx.edits()
        val created = edits.createSnippet(Fx.data(), Fx.c1, SnippetCreateRequest(0.0, 0.0, rawText = "x"))
        val id = created.value().id
        val local = checkNotNull(created.data.placement(id, Fx.c1)).listRank
        val (data, requests) = pass(created.data) {
            respond(201, snippetBody(Fx.serverSnippet(id, "x", 1).copy(listRank = local)))
        }
        assertEquals(JsonPrimitive(local), requests.single().json()["list_rank"])
        assertEquals(local, data.placement(id, Fx.c1)?.listRank)
    }

    @Test
    fun `a re-rank goes out as layout PUTs with list_rank only`() {
        val edits = Fx.edits()
        val e = edits.batchPosition(Fx.data(), Fx.c1, listOf(PositionUpdate(Fx.s2, listRank = "a0"), PositionUpdate(Fx.s1, listRank = "a1")))
        val (data, requests) = pass(e.data) {
            respond(200, snippetBody(Fx.serverSnippet(Fx.s2, "other", 1).copy(listRank = "a0")))
            respond(200, snippetBody(Fx.serverSnippet(Fx.s1, "first line\nsecond", 1).copy(listRank = "a1")))
        }
        assertEquals(listOf("PUT /api/snippets/s2?canvas_id=c1", "PUT /api/snippets/s1?canvas_id=c1"), requests.map { "${it.method} ${it.path}" })
        assertEquals(setOf("list_rank"), requests[0].json().keys)
        assertEquals(JsonPrimitive("a1"), requests[1].json()["list_rank"])
        assertTrue(data.outbox.isEmpty)
        assertEquals("a1", data.placement(SnippetId("s1"), Fx.c1)?.listRank)
    }

    private fun <T> dev.nymph.lyrics.sync.apply.Edited<T>.value(): T = (result as dev.nymph.lyrics.model.error.ApiResult.Success).value
}
