package dev.nymph.lyrics.sync.score

import dev.nymph.lyrics.data.ApiClient
import dev.nymph.lyrics.data.InMemoryTokenStore
import dev.nymph.lyrics.model.LyricsJson
import dev.nymph.lyrics.model.Snippet
import dev.nymph.lyrics.model.score.Score
import dev.nymph.lyrics.model.score.ScoreChange
import dev.nymph.lyrics.sync.Fx
import dev.nymph.lyrics.sync.engine.HttpOpSender
import dev.nymph.lyrics.sync.engine.SyncEngine
import dev.nymph.lyrics.sync.engine.Wake
import dev.nymph.lyrics.sync.outbox.ContentPatch
import dev.nymph.lyrics.sync.outbox.SyncOp
import dev.nymph.lyrics.sync.score.ScoreFx.n
import dev.nymph.lyrics.sync.score.ScoreFx.score
import dev.nymph.lyrics.sync.store.InMemoryStoreFile
import dev.nymph.lyrics.sync.store.LocalStore
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

/** The score fields on the wire: the PUT body the engine sends and the response landing in the copy. */
class ScoreHttpSyncTest {
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

    private fun respond(status: Int, body: String) =
        server.enqueue(MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body))

    /** Send [patch] as s1's content edit; the server answers [answer]. The PUT body and the stored s1. */
    private fun send(patch: ContentPatch, answer: Snippet): Pair<JsonObject, dev.nymph.lyrics.sync.store.LocalSnippet?> = runBlocking {
        val d = Fx.data()
        val store = LocalStore(InMemoryStoreFile(d.withOutbox(d.outbox.enqueue(SyncOp.EditSnippet(Fx.s1, Fx.c1, patch, 1)))))
        respond(200, LyricsJson.encodeToString(Snippet.serializer(), answer))
        respond(503, """{"detail":"snapshot not under test"}""")
        SyncEngine(store, HttpOpSender(api)).pass(Wake.EDIT)
        val put = generateSequence { server.takeRequest(100, TimeUnit.MILLISECONDS) }.first { it.method == "PUT" }
        LyricsJson.parseToJsonElement(put.body.clone().readUtf8()).jsonObject to store.data.value.snippet(Fx.s1)
    }

    private val packed: Score = score(listOf(n(0, 0), n(1, 24)))

    @Test
    fun `Remove score is sent as an explicit null`() {
        val (body, stored) = send(checkNotNull(ContentPatch.of(score = ScoreChange.REMOVE)), Fx.serverSnippet(Fx.s1, "first line\nsecond", 2))
        assertEquals(JsonNull, body["score"])
        assertTrue(stored!!.score == null)
    }

    @Test
    fun `a score and the view are sent and the answer's score lands in the copy`() {
        val answer = Fx.serverSnippet(Fx.s1, "first line\nsecond", 2).copy(score = packed, scoreView = true)
        val (body, stored) = send(checkNotNull(ContentPatch.of(score = ScoreChange.set(packed), scoreView = true)), answer)
        assertEquals(packed, LyricsJson.decodeFromJsonElement(Score.serializer(), body.getValue("score")))
        assertEquals(JsonPrimitive(true), body["score_view"])
        assertEquals(JsonPrimitive(1), body["base_version"])
        assertEquals(packed, stored!!.score)
        assertTrue(stored.scoreView)
    }
}
