package dev.nymph.lyrics.sync.engine

import dev.nymph.lyrics.model.LyricsJson
import dev.nymph.lyrics.model.Snippet
import dev.nymph.lyrics.model.SyncSnapshot
import dev.nymph.lyrics.model.User
import dev.nymph.lyrics.model.error.ApiError
import dev.nymph.lyrics.model.error.ApiResult
import dev.nymph.lyrics.sync.Fx
import dev.nymph.lyrics.sync.outbox.Batch
import dev.nymph.lyrics.sync.outbox.ContentPatch
import dev.nymph.lyrics.sync.outbox.OpState
import dev.nymph.lyrics.sync.outbox.SyncOp
import dev.nymph.lyrics.sync.store.InMemoryStoreFile
import dev.nymph.lyrics.sync.store.LocalData
import dev.nymph.lyrics.sync.store.LocalStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

/** Answers from a script (by call order), recording what was sent. */
private class FakeSender(var answer: (Batch) -> SendOutcome = { ok(it) }) : OpSender {
    val sent = mutableListOf<SyncOp>()
    var snapshots = 0
    var snapshotResult: ApiResult<SyncSnapshot> = ApiResult.Success(SyncSnapshot("2026-11-05T00:00:00Z", canvases = listOf(Fx.canvas(Fx.c1, "One"))))
    var gate: CompletableDeferred<Unit>? = null

    override suspend fun send(batch: Batch): SendOutcome {
        gate?.await()
        sent += batch.entries.map { it.op }
        return answer(batch)
    }

    override suspend fun snapshot(): ApiResult<SyncSnapshot> {
        snapshots++
        return snapshotResult
    }

    override suspend fun me(): ApiResult<User> = ApiResult.Success(User(Fx.user, "a@b.c", "A", createdAt = Fx.T0))

    companion object {
        fun ok(batch: Batch): SendOutcome = when (val op = batch.first.op) {
            is SyncOp.EditSnippet -> SendOutcome.Ok(OpResult.SnippetSaved(Fx.serverSnippet(op.id, op.patch.rawText ?: "", op.baseVersion + 1)))
            else -> SendOutcome.Ok(OpResult.Done)
        }
    }
}

class SyncEngineTest {
    private fun edit(id: dev.nymph.lyrics.model.SnippetId, text: String) = SyncOp.EditSnippet(id, Fx.c1, checkNotNull(ContentPatch.of(rawText = text)), 1)

    private fun storeWith(vararg ops: SyncOp, data: LocalData = Fx.data()): LocalStore =
        LocalStore(InMemoryStoreFile(data.withOutbox(ops.fold(data.outbox) { o, op -> o.enqueue(op) })))

    @Test
    fun `a pass sends in order, then merges a snapshot`() = runTest {
        val store = storeWith(edit(Fx.s1, "a"), edit(Fx.s2, "b"))
        val sender = FakeSender()
        val engine = SyncEngine(store, sender)
        assertTrue(engine.pass(Wake.EDIT))
        assertEquals(listOf(edit(Fx.s1, "a"), edit(Fx.s2, "b")), sender.sent)
        assertTrue(store.data.value.outbox.isEmpty)
        assertEquals(1, sender.snapshots)
        assertEquals("2026-11-05T00:00:00Z", store.data.value.snapshotAt)
    }

    @Test
    fun `nothing sent and nothing to refresh fetches no snapshot`() = runTest {
        val sender = FakeSender()
        assertTrue(SyncEngine(storeWith(), sender).pass(Wake.TICK))
        assertEquals(0, sender.snapshots)
        assertTrue(SyncEngine(storeWith(), sender).pass(Wake.FOREGROUND))
        assertEquals(1, sender.snapshots)
    }

    @Test
    fun `a transient failure stops the pass and keeps the order`() = runTest {
        val store = storeWith(edit(Fx.s1, "a"), edit(Fx.s2, "b"))
        val sender = FakeSender { SendOutcome.Transient(ApiError.Network(IOException("down"))) }
        val engine = SyncEngine(store, sender)
        assertEquals(false, engine.pass(Wake.EDIT))
        assertEquals(1, sender.sent.size)
        assertEquals(OpState.Failed(1, "down"), store.data.value.outbox.entries[0].state)
        sender.answer = { FakeSender.ok(it) }
        assertTrue(engine.pass(Wake.TICK))
        assertEquals(listOf(edit(Fx.s1, "a"), edit(Fx.s1, "a"), edit(Fx.s2, "b")), sender.sent)
    }

    @Test
    fun `a conflict parks once, raises one alert, and other entities continue`() = runTest {
        val store = storeWith(edit(Fx.s1, "a"), edit(Fx.s2, "b"))
        val current = LyricsJson.encodeToJsonElement(Snippet.serializer(), Fx.serverSnippet(Fx.s1, "server", 4))
        val sender = FakeSender { b -> if ((b.first.op as SyncOp.EditSnippet).id == Fx.s1) SendOutcome.VersionConflict(current) else FakeSender.ok(b) }
        val engine = SyncEngine(store, sender)
        val alert = backgroundScope.launch { engine.alerts.first() }
        assertTrue(engine.pass(Wake.EDIT))
        runCurrent()
        assertTrue(alert.isCompleted)
        assertEquals(1, store.data.value.outbox.entries.size)
        assertTrue(store.data.value.outbox.entries[0].isParked)
        assertTrue(engine.pass(Wake.EDIT))
        assertEquals(2, sender.sent.size, "a parked change is never sent again on its own")
    }

    @Test
    fun `after a transient failure only a refreshing wake ends the backoff early`() = runTest {
        val store = storeWith(edit(Fx.s1, "a"))
        val sender = FakeSender { SendOutcome.Transient(ApiError.Network(IOException("down"))) }
        val engine = SyncEngine(store, sender, policy = SyncPolicy(tickMillis = 30_000, backoffBaseMillis = 2_000))
        backgroundScope.launch { engine.run() }
        runCurrent()
        assertEquals(1, sender.sent.size)
        engine.wake(Wake.EDIT)
        runCurrent()
        assertEquals(1, sender.sent.size, "an edit does not hammer a failing server")
        engine.wake(Wake.HEALTHY)
        runCurrent()
        assertEquals(2, sender.sent.size)
        advanceTimeBy(4_001)
        assertEquals(3, sender.sent.size, "the second backoff is 4 s")
    }

    /** A parked edit of s1 with a move of s1 blocked behind it: something unsent, nothing sendable. */
    private fun blockedStore(): LocalStore {
        val move = SyncOp.MoveSnippet(Fx.s1, Fx.c1, checkNotNull(dev.nymph.lyrics.sync.outbox.LayoutPatch.of(positionX = 1.0)))
        val d = Fx.data().let { it.withOutbox(it.outbox.enqueue(edit(Fx.s1, "a")).enqueue(move)) }
        val parked = d.outbox.park(d.outbox.entries[0].id, dev.nymph.lyrics.sync.conflict.Conflict.DeletedOnServer(dev.nymph.lyrics.sync.outbox.EntityRef.Snippet(Fx.s1)))
        return LocalStore(InMemoryStoreFile(d.withOutbox(parked)))
    }

    @Test
    fun `while something is unsent the foreground tick retries without a wake`() = runTest {
        val store = blockedStore()
        val sender = FakeSender()
        val engine = SyncEngine(store, sender, policy = SyncPolicy(tickMillis = 30_000))
        backgroundScope.launch { engine.run() }
        runCurrent()
        store.update { d -> d.withOutbox(d.outbox.enqueue(edit(Fx.s2, "queued without a wake"))) to Unit }
        advanceTimeBy(29_000)
        assertEquals(0, sender.sent.size)
        advanceTimeBy(1_001)
        assertEquals(listOf(edit(Fx.s2, "queued without a wake")), sender.sent)
    }

    @Test
    fun `in the background there is no tick`() = runTest {
        val store = blockedStore()
        val sender = FakeSender()
        val engine = SyncEngine(store, sender, policy = SyncPolicy(tickMillis = 30_000), foreground = MutableStateFlow(false))
        backgroundScope.launch { engine.run() }
        runCurrent()
        store.update { d -> d.withOutbox(d.outbox.enqueue(edit(Fx.s2, "b"))) to Unit }
        advanceTimeBy(120_000)
        assertEquals(0, sender.sent.size)
        engine.wake(Wake.EDIT)
        runCurrent()
        assertEquals(1, sender.sent.size)
    }

    @Test
    fun `awaitSnapshot answers the first fetch's failure`() = runTest {
        val store = LocalStore(InMemoryStoreFile())
        val sender = FakeSender().apply { snapshotResult = ApiResult.Failure(ApiError.Network(IOException("offline"))) }
        val engine = SyncEngine(store, sender)
        backgroundScope.launch { engine.run() }
        runCurrent()
        val error = engine.awaitSnapshot()
        assertTrue(error is ApiError.Network)
        sender.snapshotResult = ApiResult.Success(SyncSnapshot("t"))
        engine.wake(Wake.HEALTHY)
        runCurrent()
        assertNull(engine.awaitSnapshot())
        assertEquals(Fx.user, store.data.value.account, "the copy was claimed for the token's account")
    }

    @Test
    fun `a send cancelled with the app goes back to pending`() = runTest {
        val store = storeWith(edit(Fx.s1, "a"))
        val sender = FakeSender().apply { gate = CompletableDeferred() }
        val engine = SyncEngine(store, sender)
        val job = launch { engine.run() }
        runCurrent()
        assertEquals(OpState.InFlight, store.data.value.outbox.entries[0].state)
        job.cancel()
        runCurrent()
        assertEquals(OpState.Pending, store.data.value.outbox.entries[0].state)
    }
}
