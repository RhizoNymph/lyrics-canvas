package dev.nymph.lyrics.sync.store

import dev.nymph.lyrics.model.UserId
import dev.nymph.lyrics.sync.Fx
import dev.nymph.lyrics.sync.conflict.Conflict
import dev.nymph.lyrics.sync.conflict.ServerCopy
import dev.nymph.lyrics.sync.outbox.ContentPatch
import dev.nymph.lyrics.sync.outbox.EntityRef
import dev.nymph.lyrics.sync.outbox.OpState
import dev.nymph.lyrics.sync.outbox.SyncOp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import java.io.File
import java.util.UUID

class LocalStoreTest {
    /** Under the module's build dir rather than java.io.tmpdir, which may be read-only (sandboxed builds). */
    private lateinit var dir: File

    @BeforeEach
    fun makeDir() {
        dir = File("build/tmp/local-store-test/${UUID.randomUUID()}").apply { mkdirs() }
    }

    @AfterEach
    fun removeDir() {
        dir.deleteRecursively()
    }

    private fun withOps(): dev.nymph.lyrics.sync.store.LocalData {
        val d = Fx.data()
        val o = d.outbox.enqueue(SyncOp.EditSnippet(Fx.s1, Fx.c1, checkNotNull(ContentPatch.of(rawText = "x")), 1))
            .enqueue(SyncOp.DeleteTag(Fx.t1, 1))
        val parked = o.park(o.entries[1].id, Conflict.Changed(EntityRef.Tag(Fx.t1), ServerCopy.OfTag(Fx.serverTag(Fx.t1, "Other", 2))))
        return d.withOutbox(parked.markInFlight(setOf(parked.entries[0].id)))
    }

    @Test
    fun `the copy and its outbox survive a restart, in-flight operations come back pending`() = runTest {
        val file = File(dir, "store.json")
        val first = LocalStore(JsonStoreFile(file, Dispatchers.Unconfined))
        first.update { withOps() to Unit }
        assertFalse(File(dir, "store.json.tmp").exists(), "the temp file was renamed into place")

        val second = LocalStore(JsonStoreFile(file, Dispatchers.Unconfined))
        val loaded = second.ensureLoaded()
        assertEquals(Fx.data().snippets, loaded.snippets)
        assertEquals(OpState.Pending, loaded.outbox.entries[0].state)
        assertTrue(loaded.outbox.entries[1].isParked)
        assertEquals(withOps().outbox.entries[1], loaded.outbox.entries[1])
    }

    @Test
    fun `an unreadable file is set aside, not deleted, and the app starts empty`() = runTest {
        val file = File(dir, "store.json").apply { writeText("{not json") }
        val store = LocalStore(JsonStoreFile(file, Dispatchers.Unconfined))
        assertFalse(store.ensureLoaded().hasSnapshot)
        assertTrue(dir.listFiles().orEmpty().any { it.name.startsWith("store.json.corrupt-") })
    }

    @Test
    fun `claim keeps the same account's copy and deletes another account's`() = runTest {
        val file = InMemoryStoreFile(Fx.data())
        val store = LocalStore(file)
        store.claim(Fx.user)
        assertTrue(store.data.value.hasSnapshot)
        store.claim(UserId("someone else"))
        assertEquals(UserId("someone else"), store.data.value.account)
        assertFalse(store.data.value.hasSnapshot)
        assertEquals(UserId("someone else"), file.saved?.account)
    }

    @Test
    fun `an unclaimed copy is claimed`() = runTest {
        val store = LocalStore(InMemoryStoreFile(Fx.data().copy(account = null)))
        store.claim(Fx.user)
        assertEquals(Fx.user, store.data.value.account)
        assertTrue(store.data.value.hasSnapshot)
    }

    @Test
    fun `wipe deletes the copy`() = runTest {
        val file = InMemoryStoreFile(Fx.data())
        val store = LocalStore(file)
        store.ensureLoaded()
        store.wipe()
        assertFalse(store.data.value.hasSnapshot)
        assertEquals(null, file.saved)
    }

    @Test
    fun `an update that changes nothing writes nothing`() = runTest {
        val file = InMemoryStoreFile(Fx.data())
        val store = LocalStore(file)
        store.update { it to Unit }
        assertEquals(0, file.writes)
        store.update { it.withoutWord(Fx.w1) to Unit }
        assertEquals(1, file.writes)
        assertNotNull(store.data.value.canvas(Fx.c1))
    }
}
