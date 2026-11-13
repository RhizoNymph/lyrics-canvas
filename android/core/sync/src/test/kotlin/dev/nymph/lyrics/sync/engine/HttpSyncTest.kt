package dev.nymph.lyrics.sync.engine

import dev.nymph.lyrics.data.ApiClient
import dev.nymph.lyrics.data.InMemoryTokenStore
import dev.nymph.lyrics.model.BankWord
import dev.nymph.lyrics.model.BankWordId
import dev.nymph.lyrics.model.Canvas
import dev.nymph.lyrics.model.CanvasId
import dev.nymph.lyrics.model.LyricsJson
import dev.nymph.lyrics.model.MergeMode
import dev.nymph.lyrics.model.MergeResponse
import dev.nymph.lyrics.model.Snippet
import dev.nymph.lyrics.model.SnippetCreateRequest
import dev.nymph.lyrics.model.SnippetId
import dev.nymph.lyrics.model.TagId
import dev.nymph.lyrics.model.TagLinkOp
import dev.nymph.lyrics.model.TransferMode
import dev.nymph.lyrics.model.TransferResponse
import dev.nymph.lyrics.model.UserTag
import dev.nymph.lyrics.model.ViewportState
import dev.nymph.lyrics.model.WordBankAddResponse
import dev.nymph.lyrics.model.WordBankTagResponse
import dev.nymph.lyrics.sync.Fx
import dev.nymph.lyrics.sync.conflict.Conflict
import dev.nymph.lyrics.sync.outbox.ContentPatch
import dev.nymph.lyrics.sync.outbox.EntityRef
import dev.nymph.lyrics.sync.outbox.LayoutPatch
import dev.nymph.lyrics.sync.outbox.OpState
import dev.nymph.lyrics.sync.outbox.SnippetVersion
import dev.nymph.lyrics.sync.outbox.SyncOp
import dev.nymph.lyrics.sync.store.InMemoryStoreFile
import dev.nymph.lyrics.sync.store.LocalData
import dev.nymph.lyrics.sync.store.LocalStore
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

/**
 * End-to-end sync of every operation kind against a fake server: the request the engine sends
 * (method, path, query, body with `base_version` / client ids) and how the answer lands in the
 * store, including 409 and 404.
 */
class HttpSyncTest {
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

    private fun <T> body(serializer: KSerializer<T>, value: T) = LyricsJson.encodeToString(serializer, value)

    private fun respond(status: Int, body: String = "") =
        server.enqueue(MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body))

    private fun conflict(current: JsonElement) =
        respond(409, """{"detail":{"error":"version_conflict","message":"changed","current":$current}}""")

    private class Synced(val data: LocalData, val requests: List<RecordedRequest>)

    /**
     * Queue [ops] on the sample data, answer with [answers] (each a status and body), and run one
     * pass. The snapshot that follows is refused (503) so the store shows the answers alone.
     */
    private fun sync(vararg ops: SyncOp, data: LocalData = Fx.data(), answers: () -> Unit): Synced = runBlocking {
        val store = LocalStore(InMemoryStoreFile(data.withOutbox(ops.fold(data.outbox) { o, op -> o.enqueue(op) })))
        answers()
        respond(503, """{"detail":"snapshot not under test"}""")
        SyncEngine(store, HttpOpSender(api)).pass(Wake.EDIT)
        val requests = generateSequence { server.takeRequest(100, TimeUnit.MILLISECONDS) }.toList()
        Synced(store.data.value, requests.filterNot { it.path == "/api/sync/snapshot" })
    }

    private fun RecordedRequest.json(): JsonObject = LyricsJson.parseToJsonElement(body.clone().readUtf8()).jsonObject

    private fun assertCall(r: RecordedRequest, method: String, path: String) {
        assertEquals(method, r.method)
        assertEquals(path, r.path)
    }

    private val edit = SyncOp.EditSnippet(Fx.s1, Fx.c1, checkNotNull(ContentPatch.of(rawText = "mine")), 3)

    @Test
    fun `create canvas sends the client id`() {
        val id = CanvasId("new-c")
        val s = sync(SyncOp.CreateCanvas(id, "New"), data = Fx.data().withCanvas(Fx.canvas(id, "New"))) {
            respond(201, body(Canvas.serializer(), Fx.canvas(id, "New")))
        }
        assertCall(s.requests.single(), "POST", "/api/canvases")
        assertEquals(JsonPrimitive("new-c"), s.requests.single().json()["id"])
        assertTrue(s.data.outbox.isEmpty)
    }

    @Test
    fun `rename canvas sends base_version and a 409 parks it`() {
        val s = sync(SyncOp.RenameCanvas(Fx.c1, "Mine", 1)) {
            conflict(LyricsJson.encodeToJsonElement(Canvas.serializer(), Fx.canvas(Fx.c1, "Theirs", version = 2)))
        }
        assertCall(s.requests.single(), "PUT", "/api/canvases/c1")
        assertEquals(JsonPrimitive(1), s.requests.single().json()["base_version"])
        val conflict = (s.data.outbox.entries.single().state as OpState.Parked).conflict as Conflict.Changed
        assertEquals(2, conflict.server.version)
    }

    @Test
    fun `viewport is a plain put and delete canvas treats 404 as done`() {
        val s = sync(SyncOp.SaveViewport(Fx.c1, ViewportState(1.0, 2.0, 3.0)), SyncOp.DeleteCanvas(Fx.c2, 4)) {
            respond(200, body(Canvas.serializer(), Fx.canvas(Fx.c1, "One")))
            respond(404, """{"detail":"Canvas not found"}""")
        }
        assertTrue("base_version" !in s.requests[0].json())
        assertCall(s.requests[1], "DELETE", "/api/canvases/c2?base_version=4")
        assertTrue(s.data.outbox.isEmpty)
    }

    @Test
    fun `create snippet posts with the client id and takes the server's placement`() {
        val id = SnippetId("new-s")
        val data = Fx.data().withSnippet(Fx.content(id, "x")).withPlacement(Fx.placement(id, Fx.c1))
        val s = sync(SyncOp.CreateSnippet(id, Fx.c1, SnippetCreateRequest(5.0, 6.0, rawText = "x")), data = data) {
            respond(201, body(Snippet.serializer(), Fx.serverSnippet(id, "x", 1)))
        }
        assertCall(s.requests.single(), "POST", "/api/canvases/c1/snippets")
        assertEquals(JsonPrimitive("new-s"), s.requests.single().json()["id"])
        assertEquals("srv-new-s", s.data.placement(id, Fx.c1)?.placementId?.value)
    }

    @Test
    fun `a content edit sends base_version, a 409 parks it with the server's copy`() {
        val s = sync(edit) { conflict(LyricsJson.encodeToJsonElement(Snippet.serializer(), Fx.serverSnippet(Fx.s1, "theirs", 4))) }
        assertCall(s.requests.single(), "PUT", "/api/snippets/s1?canvas_id=c1")
        assertEquals(JsonPrimitive(3), s.requests.single().json()["base_version"])
        assertEquals("mine", s.requests.single().json()["raw_text"]?.let { (it as JsonPrimitive).content })
        assertTrue(s.data.outbox.entries.single().isParked)
    }

    @Test
    fun `a content edit answered 404 is deleted on the server`() {
        val s = sync(edit) { respond(404, """{"detail":"Snippet not found"}""") }
        assertEquals(Conflict.DeletedOnServer(EntityRef.Snippet(Fx.s1)), (s.data.outbox.entries.single().state as OpState.Parked).conflict)
    }

    @Test
    fun `a move is layout only, a remove and a delete send base_version`() {
        val move = SyncOp.MoveSnippet(Fx.s1, Fx.c2, checkNotNull(LayoutPatch.of(positionX = 9.0, positionY = 8.0)))
        val s = sync(move, SyncOp.RemoveFromCanvas(Fx.s2, Fx.c1, 2), SyncOp.DeleteSnippet(Fx.s1, 5)) {
            respond(200, body(Snippet.serializer(), Fx.serverSnippet(Fx.s1, "first line\nsecond", 1, canvas = Fx.c2)))
            respond(200, """{"snippet_deleted":true}""")
            respond(204)
        }
        assertCall(s.requests[0], "PUT", "/api/snippets/s1?canvas_id=c2")
        assertEquals(setOf("position_x", "position_y"), s.requests[0].json().keys)
        assertCall(s.requests[1], "DELETE", "/api/canvases/c1/snippets/s2?base_version=2")
        assertCall(s.requests[2], "DELETE", "/api/snippets/s1?base_version=5")
        assertTrue(s.data.outbox.isEmpty)
    }

    @Test
    fun `tag link and unlink carry the snippet's base version and move it`() {
        val s = sync(SyncOp.SnippetTag(Fx.s1, Fx.t1, true, 1), SyncOp.SnippetTag(Fx.s1, Fx.t1, false, 1)) {
            respond(200, """{"ok":true,"version":2}""")
            respond(200, """{"ok":true,"version":3}""")
        }
        assertCall(s.requests[0], "POST", "/api/snippets/s1/tags?tag_id=t1")
        assertEquals(JsonPrimitive(1), s.requests[0].json()["base_version"])
        assertCall(s.requests[1], "DELETE", "/api/snippets/s1/tags/t1?base_version=2")
        assertEquals(3, s.data.snippet(Fx.s1)?.version)
    }

    @Test
    fun `a merge sends merged_id and base_versions, a 409 parks the whole merge`() {
        val merged = SnippetId("m")
        val op = SyncOp.Merge(Fx.c1, merged, MergeMode.VERTICAL, listOf(SnippetVersion(Fx.s1, 1), SnippetVersion(Fx.s2, 2)))
        val s = sync(op, data = Fx.data().withSnippet(Fx.content(merged, "x")).withPlacement(Fx.placement(merged, Fx.c1))) {
            conflict(LyricsJson.encodeToJsonElement(ListSerializer(Snippet.serializer()), listOf(Fx.serverSnippet(Fx.s2, "changed", 3))))
        }
        val sent = s.requests.single().json()
        assertCall(s.requests.single(), "POST", "/api/canvases/c1/merge")
        assertEquals(JsonPrimitive("m"), sent["merged_id"])
        assertEquals(JsonPrimitive(2), sent["base_versions"]?.jsonObject?.get("s2"))
        assertTrue((s.data.outbox.entries.single().state as OpState.Parked).conflict is Conflict.MergeChanged)
    }

    @Test
    fun `a merge that succeeds takes the server's merged snippet`() {
        val merged = SnippetId("m")
        val op = SyncOp.Merge(Fx.c1, merged, MergeMode.VERTICAL, listOf(SnippetVersion(Fx.s1, 1), SnippetVersion(Fx.s2, 1)))
        val s = sync(op, data = Fx.data().withSnippet(Fx.content(merged, "local guess")).withPlacement(Fx.placement(merged, Fx.c1))) {
            respond(200, body(MergeResponse.serializer(), MergeResponse(Fx.serverSnippet(merged, "server merge", 1), listOf(Fx.s1, Fx.s2))))
        }
        assertEquals("server merge", s.data.snippet(merged)?.rawText)
    }

    @Test
    fun `transfer is layout only`() {
        val s = sync(SyncOp.Transfer(Fx.c1, Fx.c2, listOf(Fx.s2), TransferMode.SHOW)) {
            respond(200, body(TransferResponse.serializer(), TransferResponse(Fx.c2, listOf(Fx.s2), emptyList(), false)))
        }
        assertCall(s.requests.single(), "POST", "/api/canvases/c1/snippets/transfer")
        assertTrue("base_version" !in s.requests.single().json())
        assertTrue(s.data.outbox.isEmpty)
    }

    @Test
    fun `a tag name taken on the server becomes the server's tag`() {
        val local = TagId("local")
        val data = Fx.data().withTag(Fx.tag(local, "Bridge"))
        val s = sync(SyncOp.CreateTag(local, "Bridge"), SyncOp.SnippetTag(Fx.s1, local, true, 1), data = data) {
            respond(409, """{"detail":{"error":"tag_name_taken","message":"taken"}}""")
            respond(200, body(ListSerializer(UserTag.serializer()), listOf(Fx.serverTag(TagId("srv"), "bridge", 1))))
            respond(200, """{"ok":true,"version":2}""")
        }
        assertCall(s.requests[0], "POST", "/api/tags")
        assertEquals(JsonPrimitive("local"), s.requests[0].json()["id"])
        assertCall(s.requests[1], "GET", "/api/tags")
        assertCall(s.requests[2], "POST", "/api/snippets/s1/tags?tag_id=srv")
        assertNull(s.data.tag(local))
    }

    @Test
    fun `tag update and delete send base_version`() {
        val s = sync(SyncOp.UpdateTag(Fx.t1, name = "New", baseVersion = 2), SyncOp.DeleteTag(TagId("t9"), 1)) {
            respond(200, body(UserTag.serializer(), Fx.serverTag(Fx.t1, "New", 3)))
            respond(204)
        }
        assertEquals(JsonPrimitive(2), s.requests[0].json()["base_version"])
        assertCall(s.requests[1], "DELETE", "/api/tags/t9?base_version=1")
        assertEquals(3, s.data.tag(Fx.t1)?.version)
    }

    @Test
    fun `word adds go as one batch with ids, and a word already banked takes the server's id`() {
        val a = BankWordId("a")
        val b = BankWordId("b")
        val data = Fx.data().withWord(Fx.word(a, "sun")).withWord(Fx.word(b, "star"))
        val s = sync(SyncOp.AddWord(a, "sun"), SyncOp.AddWord(b, "star"), data = data) {
            respond(200, body(WordBankAddResponse.serializer(), WordBankAddResponse(listOf(Fx.serverWord(a, "sun", 1)), listOf(Fx.serverWord(BankWordId("old"), "Star", 4)))))
        }
        val sent = s.requests.single().json()
        val words = sent["words"] as kotlinx.serialization.json.JsonArray
        assertEquals(listOf("a", "b"), words.map { (it.jsonObject["id"] as JsonPrimitive).content })
        assertNull(s.data.word(b))
        assertEquals(4, s.data.word(BankWordId("old"))?.version)
    }

    @Test
    fun `a word delete batch conflict parks only the changed word`() {
        val w2 = BankWordId("w2")
        val data = Fx.data().withWord(Fx.word(w2, "sun"))
        val s = sync(SyncOp.DeleteWord(Fx.w1, 1), SyncOp.DeleteWord(w2, 1), data = data) {
            conflict(LyricsJson.encodeToJsonElement(ListSerializer(BankWord.serializer()), listOf(Fx.serverWord(w2, "sun!", 2))))
            respond(204)
        }
        assertCall(s.requests[0], "POST", "/api/word-bank/delete")
        assertEquals(JsonPrimitive(1), s.requests[0].json()["base_versions"]?.jsonObject?.get("w1"))
        assertCall(s.requests[1], "POST", "/api/word-bank/delete")
        assertEquals(1, s.data.outbox.entries.size)
        assertTrue(s.data.outbox.entries.single().isParked)
    }

    @Test
    fun `word rename and tag batch`() {
        val s = sync(SyncOp.RenameWord(Fx.w1, "Moon", 1), SyncOp.WordTag(Fx.w1, Fx.t1, TagLinkOp.LINK, 1)) {
            respond(200, body(BankWord.serializer(), Fx.serverWord(Fx.w1, "Moon", 2)))
            respond(200, body(WordBankTagResponse.serializer(), WordBankTagResponse(listOf(Fx.serverWord(Fx.w1, "Moon", 3)))))
        }
        assertCall(s.requests[0], "PUT", "/api/word-bank/w1")
        assertEquals(JsonPrimitive(2), s.requests[1].json()["base_versions"]?.jsonObject?.get("w1"), "rebased on the rename's answer")
        assertEquals(3, s.data.word(Fx.w1)?.version)
    }

    @Test
    fun `a 5xx stops the pass and keeps the change pending for a retry`() {
        val s = sync(edit) { respond(503, """{"detail":"down"}""") }
        assertTrue(s.data.outbox.entries.single().state is OpState.Failed)
    }
}
