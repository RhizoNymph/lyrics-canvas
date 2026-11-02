package dev.nymph.lyrics.app.voice

import app.cash.turbine.test
import dev.nymph.lyrics.app.MainDispatcherExtension
import dev.nymph.lyrics.app.voice.recordings.AudioPlayer
import dev.nymph.lyrics.app.voice.recordings.Playback
import dev.nymph.lyrics.app.voice.recordings.RecordingsList
import dev.nymph.lyrics.app.voice.recordings.RecordingsViewModel
import dev.nymph.lyrics.model.SnippetId
import dev.nymph.lyrics.model.error.ApiError
import dev.nymph.lyrics.model.error.ApiResult
import dev.nymph.lyrics.model.voice.RecordingId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.io.File
import java.util.UUID

private class FakeAudioPlayer : AudioPlayer {
    val played = mutableListOf<File>()
    var canPlay = true
    var pauses = 0
    var resumes = 0
    var stops = 0
    var onComplete: (() -> Unit)? = null

    override fun play(file: File, onComplete: () -> Unit): Boolean {
        played += file
        this.onComplete = onComplete
        return canPlay
    }

    override fun pause() {
        pauses++
    }

    override fun resume() {
        resumes++
    }

    override fun stop() {
        stops++
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class RecordingsViewModelTest {
    @JvmField
    @RegisterExtension
    val main = MainDispatcherExtension()

    private val repo = FakeVoiceRepository()
    private val player = FakeAudioPlayer()

    /** Under the module's build dir: java.io.tmpdir may be read-only in sandboxed builds. */
    private val cache = File("build/tmp/recordings-test/${UUID.randomUUID()}").apply { mkdirs() }

    private val r1 = recording("r1", createdAt = "2026-11-01T10:00:00Z")
    private val r2 = recording("r2", createdAt = "2026-10-31T10:00:00Z")

    @AfterEach
    fun cleanUp() {
        cache.deleteRecursively()
    }

    private fun TestScope.vm(): RecordingsViewModel {
        repo.list = ApiResult.Success(listOf(r1, r2))
        repo.downloadResult = { _, f -> f.writeBytes(byteArrayOf(1, 2, 3)); ApiResult.Success(f) }
        val vm = RecordingsViewModel(SnippetId("s1"), repo, cache, player)
        runCurrent()
        return vm
    }

    @Test
    fun `loads the snippet's recordings`() = runTest(main.dispatcher) {
        val vm = vm()
        assertEquals(RecordingsList.Loaded(listOf(r1, r2)), vm.state.value.list)
    }

    @Test
    fun `a failed load shows a message`() = runTest(main.dispatcher) {
        repo.list = ApiResult.Failure(ApiError.Network(java.io.IOException("x")))
        val vm = RecordingsViewModel(SnippetId("s1"), repo, cache, player)
        runCurrent()
        assertEquals(RecordingsList.Failed("Can't reach the server. Check your connection."), vm.state.value.list)
    }

    @Test
    fun `add puts a new take first`() = runTest(main.dispatcher) {
        val vm = vm()
        val r3 = recording("r3")
        vm.add(r3)
        assertEquals(RecordingsList.Loaded(listOf(r3, r1, r2)), vm.state.value.list)
        vm.add(r3)
        assertEquals(3, (vm.state.value.list as RecordingsList.Loaded).items.size)
    }

    @Test
    fun `play downloads once into the cache, then pause and resume`() = runTest(main.dispatcher) {
        val vm = vm()
        vm.togglePlay(RecordingId("r1"))
        assertEquals(Playback.Loading(RecordingId("r1")), vm.state.value.playback)
        runCurrent()
        assertEquals(Playback.Playing(RecordingId("r1")), vm.state.value.playback)
        assertEquals(File(cache, "recording-r1.ogg"), player.played.single())
        vm.togglePlay(RecordingId("r1"))
        assertEquals(Playback.Paused(RecordingId("r1")), vm.state.value.playback)
        assertEquals(1, player.pauses)
        vm.togglePlay(RecordingId("r1"))
        assertEquals(Playback.Playing(RecordingId("r1")), vm.state.value.playback)
        assertEquals(1, player.resumes)
        player.onComplete!!()
        assertEquals(Playback.Idle, vm.state.value.playback)
        vm.togglePlay(RecordingId("r1"))
        runCurrent()
        assertEquals(1, repo.downloads.size, "second play uses the cached file")
        assertEquals(2, player.played.size)
    }

    @Test
    fun `playing another recording stops the current one`() = runTest(main.dispatcher) {
        val vm = vm()
        vm.togglePlay(RecordingId("r1"))
        runCurrent()
        val stopsBefore = player.stops
        vm.togglePlay(RecordingId("r2"))
        runCurrent()
        assertEquals(stopsBefore + 1, player.stops)
        assertEquals(Playback.Playing(RecordingId("r2")), vm.state.value.playback)
        // The first one's completion arriving late does not stop the second.
        player.onComplete = null
    }

    @Test
    fun `a failed download returns to idle with a message`() = runTest(main.dispatcher) {
        val vm = vm()
        repo.downloadResult = { _, _ -> ApiResult.Failure(ApiError.NotFound("Recording not found", "recording_not_found")) }
        vm.messages.test {
            vm.togglePlay(RecordingId("r1"))
            runCurrent()
            assertEquals("Recording not found", awaitItem())
        }
        assertEquals(Playback.Idle, vm.state.value.playback)
        assertTrue(player.played.isEmpty())
    }

    @Test
    fun `a file the player refuses is dropped from the cache`() = runTest(main.dispatcher) {
        val vm = vm()
        player.canPlay = false
        vm.messages.test {
            vm.togglePlay(RecordingId("r1"))
            runCurrent()
            assertEquals("This recording could not be played.", awaitItem())
        }
        assertFalse(File(cache, "recording-r1.ogg").exists())
        assertEquals(Playback.Idle, vm.state.value.playback)
    }

    @Test
    fun `stopping while the audio is still downloading does not start it later`() = runTest(main.dispatcher) {
        val vm = vm()
        repo.downloadGate = CompletableDeferred()
        vm.togglePlay(RecordingId("r1"))
        runCurrent()
        vm.stopPlayback()
        repo.downloadGate!!.complete(Unit)
        runCurrent()
        assertTrue(player.played.isEmpty())
        assertEquals(Playback.Idle, vm.state.value.playback)
    }

    @Test
    fun `delete asks first, then removes it, stopping its playback`() = runTest(main.dispatcher) {
        val vm = vm()
        vm.togglePlay(RecordingId("r1"))
        runCurrent()
        vm.requestDelete(RecordingId("r1"))
        assertEquals(r1, vm.state.value.confirmDelete)
        assertTrue(repo.deletes.isEmpty())
        vm.confirmDelete()
        assertEquals(Playback.Idle, vm.state.value.playback)
        assertNull(vm.state.value.confirmDelete)
        assertEquals(setOf(RecordingId("r1")), vm.state.value.deleting)
        runCurrent()
        assertEquals(listOf(RecordingId("r1")), repo.deletes)
        assertEquals(RecordingsList.Loaded(listOf(r2)), vm.state.value.list)
        assertTrue(vm.state.value.deleting.isEmpty())
        assertFalse(File(cache, "recording-r1.ogg").exists())
    }

    @Test
    fun `dismissing the confirmation deletes nothing`() = runTest(main.dispatcher) {
        val vm = vm()
        vm.requestDelete(RecordingId("r2"))
        vm.dismissDelete()
        vm.confirmDelete()
        runCurrent()
        assertTrue(repo.deletes.isEmpty())
        assertEquals(RecordingsList.Loaded(listOf(r1, r2)), vm.state.value.list)
    }

    @Test
    fun `a failed delete keeps the row and says why`() = runTest(main.dispatcher) {
        val vm = vm()
        repo.deleteResult = ApiResult.Failure(ApiError.Server(500, "boom"))
        vm.messages.test {
            vm.requestDelete(RecordingId("r2"))
            vm.confirmDelete()
            runCurrent()
            assertEquals("boom", awaitItem())
        }
        assertEquals(RecordingsList.Loaded(listOf(r1, r2)), vm.state.value.list)
        assertTrue(vm.state.value.deleting.isEmpty())
    }
}
