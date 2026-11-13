package dev.nymph.lyrics.sync.repo

import dev.nymph.lyrics.data.repository.AuthRepository
import dev.nymph.lyrics.data.repository.NlpRepository
import dev.nymph.lyrics.data.repository.TagPresetRepository
import dev.nymph.lyrics.model.CanvasUpdateRequest
import dev.nymph.lyrics.model.GridData
import dev.nymph.lyrics.model.RhymeSuggestRequest
import dev.nymph.lyrics.model.RhymeSuggestResponse
import dev.nymph.lyrics.model.SnippetCreateRequest
import dev.nymph.lyrics.model.SnippetUpdateRequest
import dev.nymph.lyrics.model.TagPreset
import dev.nymph.lyrics.model.TagPresetId
import dev.nymph.lyrics.model.TagPresetUpdateRequest
import dev.nymph.lyrics.model.User
import dev.nymph.lyrics.model.UserId
import dev.nymph.lyrics.model.ViewportState
import dev.nymph.lyrics.model.error.ApiError
import dev.nymph.lyrics.model.error.ApiResult
import dev.nymph.lyrics.sync.Fx
import dev.nymph.lyrics.sync.engine.SyncTrigger
import dev.nymph.lyrics.sync.engine.Wake
import dev.nymph.lyrics.sync.outbox.SyncOp
import dev.nymph.lyrics.sync.store.InMemoryStoreFile
import dev.nymph.lyrics.sync.store.LocalData
import dev.nymph.lyrics.sync.store.LocalStore
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

private class RecordingTrigger(private val snapshotError: ApiError? = null, private val onSnapshot: suspend () -> Unit = {}) : SyncTrigger {
    val wakes = mutableListOf<Wake>()

    override fun wake(reason: Wake) {
        wakes += reason
    }

    override suspend fun awaitSnapshot(): ApiError? {
        onSnapshot()
        return snapshotError
    }
}

class LocalRepositoriesTest {
    private fun local(data: LocalData = Fx.data(), trigger: SyncTrigger = RecordingTrigger()) = LocalFirst(LocalStore(InMemoryStoreFile(data)), trigger)

    @Test
    fun `a write applies locally, queues one operation, wakes the engine and answers at once`() = runTest {
        val trigger = RecordingTrigger()
        val lf = local(trigger = trigger)
        val repo = LocalSnippetRepository(lf, Fx.edits())
        val result = repo.update(Fx.c1, Fx.s1, SnippetUpdateRequest(rawText = "offline edit"))
        assertEquals("offline edit", (result as ApiResult.Success).value.rawText)
        assertEquals(listOf(Wake.EDIT), trigger.wakes)
        assertTrue(lf.store.data.value.outbox.entries.single().op is SyncOp.EditSnippet)
        val detail = (LocalCanvasRepository(lf, Fx.edits()).get(Fx.c1) as ApiResult.Success).value
        assertEquals("offline edit", detail.snippets.first { it.id == Fx.s1 }.rawText)
    }

    @Test
    fun `a refused write changes nothing and does not wake the engine`() = runTest {
        val trigger = RecordingTrigger()
        val lf = local(trigger = trigger)
        val result = LocalSnippetRepository(lf, Fx.edits()).create(dev.nymph.lyrics.model.CanvasId("gone"), SnippetCreateRequest(0.0, 0.0))
        assertTrue(result is ApiResult.Failure)
        assertTrue(trigger.wakes.isEmpty())
        assertTrue(lf.store.data.value.outbox.isEmpty)
    }

    @Test
    fun `a canvas update renames and saves the viewport in one change`() = runTest {
        val lf = local()
        val result = LocalCanvasRepository(lf, Fx.edits()).update(Fx.c1, CanvasUpdateRequest(title = "T", viewportState = ViewportState(1.0, 1.0, 2.0)))
        assertEquals("T", (result as ApiResult.Success).value.title)
        assertEquals(2, lf.store.data.value.outbox.entries.size)
    }

    @Test
    fun `observed lists follow the store`() = runTest {
        val lf = local()
        val repo = LocalCanvasRepository(lf, Fx.edits())
        repo.create("Fresh")
        assertEquals("Fresh", repo.observeList().first().first().title)
        assertEquals(listOf("Hook"), LocalTagRepository(lf, Fx.edits()).observeTags().first().map { it.name })
    }

    @Test
    fun `a fresh install waits for the first snapshot and answers its failure`() = runTest {
        val empty = LocalData()
        val failing = local(empty, RecordingTrigger(snapshotError = ApiError.Network(IOException("offline"))))
        assertTrue((LocalCanvasRepository(failing, Fx.edits()).list() as ApiResult.Failure).error is ApiError.Network)

        lateinit var lf: LocalFirst
        lf = local(empty, RecordingTrigger(onSnapshot = { lf.store.update { Fx.data() to Unit } }))
        assertEquals(2, (LocalCanvasRepository(lf, Fx.edits()).list() as ApiResult.Success).value.size)
    }

    @Test
    fun `a cold start with a stored copy reads it without the server`() = runTest {
        val trigger = RecordingTrigger(snapshotError = ApiError.Network(IOException("offline")))
        val lf = local(trigger = trigger)
        assertEquals(2, (LocalCanvasRepository(lf, Fx.edits()).list() as ApiResult.Success).value.size)
        assertEquals(1, (LocalSearchRepository(lf).search("first") as ApiResult.Success).value.snippets.size)
    }

    @Test
    fun `server-only calls answer NeedsConnection while offline`() = runTest {
        val online = MutableStateFlow(false)
        var calls = 0
        val nlp = GatedNlpRepository(
            object : NlpRepository {
                override suspend fun syllabify(text: String): ApiResult<GridData> = ApiResult.Success(GridData()).also { calls++ }

                override suspend fun rhymeSuggest(request: RhymeSuggestRequest): ApiResult<RhymeSuggestResponse> = error("not called")
            },
            OnlineGate(online),
        )
        assertEquals(ApiResult.Failure(ApiError.NeedsConnection), nlp.syllabify("x"))
        online.value = true
        assertTrue(nlp.syllabify("x") is ApiResult.Success)
        assertEquals(1, calls)
    }

    @Test
    fun `presets read from the copy and a confirmed save updates it`() = runTest {
        val preset = TagPreset(TagPresetId("p"), "Mine", "Hook", Fx.T0, Fx.T0)
        val lf = local(Fx.data().copy(presets = listOf(TagPreset(TagPresetId("q"), "Old", "x", Fx.T0, Fx.T0))))
        val http = object : TagPresetRepository {
            override suspend fun list(): ApiResult<List<TagPreset>> = error("read from the copy")

            override suspend fun create(name: String, expression: String): ApiResult<TagPreset> = ApiResult.Success(preset)

            override suspend fun update(id: TagPresetId, body: TagPresetUpdateRequest): ApiResult<TagPreset> = error("unused")

            override suspend fun delete(id: TagPresetId): ApiResult<Unit> = ApiResult.Success(Unit)
        }
        val online = MutableStateFlow(true)
        val repo = CachedTagPresetRepository(lf, http, OnlineGate(online))
        repo.create("Mine", "Hook")
        assertEquals(listOf("Mine", "Old"), (repo.list() as ApiResult.Success).value.map { it.name })
        repo.delete(TagPresetId("q"))
        assertEquals(listOf("Mine"), (repo.list() as ApiResult.Success).value.map { it.name })
        online.value = false
        assertEquals(ApiResult.Failure(ApiError.NeedsConnection), repo.create("x", "y"))
    }

    @Test
    fun `sign-in claims the copy, sign-out deletes it`() = runTest {
        val store = LocalStore(InMemoryStoreFile(Fx.data()))
        var loggedOut = false
        val other = User(UserId("other"), "o@x.y", "O", createdAt = Fx.T0)
        val http = object : AuthRepository {
            override suspend fun login(email: String, password: String): ApiResult<User> = ApiResult.Success(other)

            override suspend fun register(email: String, password: String, displayName: String): ApiResult<User> = error("unused")

            override suspend fun me(): ApiResult<User> = error("unused")

            override suspend fun logout() {
                loggedOut = true
            }
        }
        val trigger = RecordingTrigger()
        val auth = SyncAuthRepository(http, store, trigger)
        auth.login("o@x.y", "pw")
        assertEquals(UserId("other"), store.data.value.account)
        assertTrue(store.data.value.canvases.isEmpty(), "another account's copy is deleted")
        assertEquals(listOf(Wake.REFRESH), trigger.wakes)
        auth.logout()
        assertTrue(loggedOut)
        assertEquals(null, store.data.value.account)
    }

    @Test
    fun `awaitSettled waits for an entity's queue to drain`() = runTest {
        val lf = local()
        LocalSnippetRepository(lf, Fx.edits()).update(Fx.c1, Fx.s1, SnippetUpdateRequest(rawText = "x"))
        val settled = backgroundScope.async { lf.awaitSettled(setOf(dev.nymph.lyrics.sync.outbox.EntityRef.Snippet(Fx.s1))) }
        testScheduler.runCurrent()
        lf.store.update { d -> d.withOutbox(d.outbox.complete(d.outbox.entries.map { it.id }.toSet())) to Unit }
        assertTrue(settled.await())
    }
}
