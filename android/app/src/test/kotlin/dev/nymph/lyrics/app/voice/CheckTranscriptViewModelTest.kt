package dev.nymph.lyrics.app.voice

import app.cash.turbine.test
import dev.nymph.lyrics.app.MainDispatcherExtension
import dev.nymph.lyrics.app.voice.check.CheckAction
import dev.nymph.lyrics.app.voice.check.CheckError
import dev.nymph.lyrics.app.voice.check.CheckTranscriptEvent
import dev.nymph.lyrics.app.voice.check.CheckTranscriptUiState
import dev.nymph.lyrics.app.voice.check.CheckTranscriptViewModel
import dev.nymph.lyrics.app.voice.check.PlayerFailure
import dev.nymph.lyrics.app.voice.check.PlayerUi
import dev.nymph.lyrics.app.voice.recordings.RecordingUpdates
import dev.nymph.lyrics.logic.voice.TranscriptReference
import dev.nymph.lyrics.logic.voice.VerificationInputError
import dev.nymph.lyrics.logic.voice.VoiceUploadError
import dev.nymph.lyrics.model.SnippetId
import dev.nymph.lyrics.model.error.ApiError
import dev.nymph.lyrics.model.error.ApiResult
import dev.nymph.lyrics.model.voice.Recording
import dev.nymph.lyrics.model.voice.RecordingId
import dev.nymph.lyrics.model.voice.TimedWord
import dev.nymph.lyrics.model.voice.VoiceErrorCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

private val WORDS = listOf(
    TimedWord("one", 0, 400),
    TimedWord("two", 400, 900),
    TimedWord("three", 1_000, 1_800),
)

private fun timed(id: String = "r1"): Recording = recording(id, transcript = "one two\nthree").copy(
    durationMs = 2_000,
    lines = listOf("one two", "three"),
    hasTimings = true,
    words = WORDS,
    lineSpans = listOf(listOf(0, 2), listOf(2, 3)),
)

@OptIn(ExperimentalCoroutinesApi::class)
@Timeout(value = 30, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class CheckTranscriptViewModelTest {
    @JvmField
    @RegisterExtension
    val main = MainDispatcherExtension()

    private val repo = FakeVoiceRepository()
    private val player = FakeSeekablePlayer()
    private val updates = RecordingUpdates()
    private val cache = File("build/tmp/check-transcript-test/${UUID.randomUUID()}").apply { mkdirs() }

    @AfterEach
    fun cleanUp() {
        cache.deleteRecursively()
    }

    private fun TestScope.vm(vararg items: Recording = arrayOf(timed()), id: String = "r1"): CheckTranscriptViewModel {
        repo.list = ApiResult.Success(items.toList())
        repo.downloadResult = { _, f -> f.parentFile.mkdirs(); f.writeBytes(byteArrayOf(1, 2)); ApiResult.Success(f) }
        val vm = CheckTranscriptViewModel(SnippetId("s1"), RecordingId(id), repo, cache, player, updates, positionPollMs = 100)
        runCurrent()
        return vm
    }

    private fun CheckTranscriptViewModel.ready(): CheckTranscriptUiState.Ready = state.value as CheckTranscriptUiState.Ready

    // ---- loading -------------------------------------------------------------------------

    @Test
    fun `loads the recording, its reference and the audio`() = runTest(main.dispatcher) {
        val vm = vm()
        val s = vm.ready()
        assertEquals(RecordingId("r1"), s.recording.id)
        assertTrue(s.reference is TranscriptReference.Timed)
        assertEquals(PlayerUi.Ready(positionMs = 0, durationMs = 2_000, playing = false), s.player)
        assertEquals(listOf(RecordingId("r1")), repo.downloads.map { it.first })
        assertEquals(CheckAction.IDLE, s.action)
        assertNull(s.error)
        assertFalse(s.checked)
    }

    @Test
    fun `a recording without timings has a plain reference`() = runTest(main.dispatcher) {
        val vm = vm(recording("r1", transcript = "a\nb"))
        assertEquals(TranscriptReference.Plain(listOf("a", "b")), vm.ready().reference)
        assertNull(vm.ready().currentWord)
    }

    @Test
    fun `a recording that is gone is recording_not_found`() = runTest(main.dispatcher) {
        val vm = vm(timed("other"), id = "r1")
        val failed = vm.state.value as CheckTranscriptUiState.LoadFailed
        assertEquals(VoiceUploadError.Rejected(VoiceErrorCode.RECORDING_NOT_FOUND, 404, null), failed.error)
    }

    @Test
    fun `a failed load can be retried`() = runTest(main.dispatcher) {
        repo.list = ApiResult.Failure(ApiError.Network(IOException("down")))
        val vm = CheckTranscriptViewModel(SnippetId("s1"), RecordingId("r1"), repo, cache, player, updates)
        runCurrent()
        assertEquals(CheckTranscriptUiState.LoadFailed(VoiceUploadError.Network("down")), vm.state.value)
        repo.list = ApiResult.Success(listOf(timed()))
        vm.retryLoad()
        runCurrent()
        assertTrue(vm.state.value is CheckTranscriptUiState.Ready)
    }

    @Test
    fun `a failed audio download leaves the transcript usable`() = runTest(main.dispatcher) {
        repo.list = ApiResult.Success(listOf(timed()))
        repo.downloadResult = { _, _ -> ApiResult.Failure(ApiError.Network(IOException("cut"))) }
        val vm = CheckTranscriptViewModel(SnippetId("s1"), RecordingId("r1"), repo, cache, player, updates)
        runCurrent()
        assertEquals(PlayerUi.Failed(PlayerFailure.Download(VoiceUploadError.Network("cut"))), vm.ready().player)
        vm.togglePlay()
        assertTrue(player.played.isEmpty())
    }

    @Test
    fun `cached audio is not downloaded again`() = runTest(main.dispatcher) {
        File(cache, "recording-r1.ogg").writeBytes(byteArrayOf(7))
        val vm = vm()
        assertTrue(repo.downloads.isEmpty())
        assertTrue(vm.ready().player is PlayerUi.Ready)
    }

    // ---- correction field ----------------------------------------------------------------

    @Test
    fun `the field starts with the whisper lines`() = runTest(main.dispatcher) {
        val s = vm().ready()
        assertEquals("one two\nthree", s.text)
        assertFalse(s.dirty)
    }

    @Test
    fun `the field starts with the checked text when the take is checked`() = runTest(main.dispatcher) {
        val checked = timed().copy(verifiedText = "one too\nthree", verifiedAt = "2026-11-02T00:00:00Z")
        val s = vm(checked).ready()
        assertEquals("one too\nthree", s.text)
        assertTrue(s.checked)
    }

    @Test
    fun `editing makes the field dirty, and editing back makes it clean`() = runTest(main.dispatcher) {
        val vm = vm()
        vm.editText("one too\nthree")
        assertTrue(vm.ready().dirty)
        vm.editText("one two\nthree")
        assertFalse(vm.ready().dirty)
    }

    // ---- save / unchecked ----------------------------------------------------------------

    @Test
    fun `save sends the text, publishes the update and closes`() = runTest(main.dispatcher) {
        val vm = vm()
        val published = mutableListOf<Recording>()
        val events = mutableListOf<CheckTranscriptEvent>()
        backgroundScope.launch { updates.updates.collect { published += it } }
        backgroundScope.launch { vm.events.collect { events += it } }
        runCurrent()
        repo.verifyGate = CompletableDeferred()
        vm.editText("one too\nthree  ")
        vm.save()
        runCurrent()
        assertEquals(CheckAction.SAVING, vm.ready().action)
        vm.save() // ignored while saving
        repo.verifyGate!!.complete(Unit)
        runCurrent()
        assertEquals(listOf(RecordingId("r1") to "one too\nthree  "), repo.verifications)
        assertEquals("one too\nthree  ", published.single().verifiedText)
        assertEquals(listOf(CheckTranscriptEvent.Close), events)
    }

    @Test
    fun `an empty correction is refused without a request`() = runTest(main.dispatcher) {
        val vm = vm()
        vm.editText("  \n \n")
        vm.save()
        runCurrent()
        assertTrue(repo.verifications.isEmpty())
        assertEquals(CheckError.Input(VerificationInputError.Empty), vm.ready().error)
        vm.editText("x")
        assertNull(vm.ready().error, "editing clears the error")
    }

    @Test
    fun `server errors are shown inline, typed`() = runTest(main.dispatcher) {
        val vm = vm()
        val events = mutableListOf<CheckTranscriptEvent>()
        backgroundScope.launch { vm.events.collect { events += it } }
        repo.verifyResult = { _, _ -> ApiResult.Failure(ApiError.Validation(422, "too long", "invalid_verification")) }
        vm.save()
        runCurrent()
        val s = vm.ready()
        assertEquals(CheckError.Server(VoiceUploadError.Rejected(VoiceErrorCode.INVALID_VERIFICATION, 422, "too long")), s.error)
        assertEquals(CheckAction.IDLE, s.action)
        assertTrue(events.isEmpty())

        repo.verifyResult = { _, _ -> ApiResult.Failure(ApiError.NotFound("gone", "recording_not_found")) }
        vm.save()
        runCurrent()
        assertEquals(CheckError.Server(VoiceUploadError.Rejected(VoiceErrorCode.RECORDING_NOT_FOUND, 404, "gone")), vm.ready().error)
    }

    @Test
    fun `mark unchecked clears the verification and stays open`() = runTest(main.dispatcher) {
        val checked = timed().copy(verifiedText = "one too\nthree", verifiedAt = "2026-11-02T00:00:00Z")
        val vm = vm(checked)
        val published = mutableListOf<Recording>()
        backgroundScope.launch { updates.updates.collect { published += it } }
        runCurrent()
        repo.clearResult = { ApiResult.Success(timed()) }
        vm.markUnchecked()
        runCurrent()
        assertEquals(listOf(RecordingId("r1")), repo.clears)
        val s = vm.ready()
        assertFalse(s.checked)
        assertEquals("one too\nthree", s.text, "the field keeps what the user sees")
        assertNull(published.single().verification)
    }

    @Test
    fun `mark unchecked is a no-op on an unchecked take`() = runTest(main.dispatcher) {
        val vm = vm()
        vm.markUnchecked()
        runCurrent()
        assertTrue(repo.clears.isEmpty())
    }

    @Test
    fun `a failed unchecked shows the error and keeps the check`() = runTest(main.dispatcher) {
        val checked = timed().copy(verifiedText = "x", verifiedAt = "2026-11-02T00:00:00Z")
        val vm = vm(checked)
        repo.clearResult = { ApiResult.Failure(ApiError.Server(503, "busy")) }
        vm.markUnchecked()
        runCurrent()
        assertTrue(vm.ready().checked)
        assertEquals(CheckError.Server(VoiceUploadError.Http(503, "busy")), vm.ready().error)
    }

    // ---- cancel ----------------------------------------------------------------------------

    @Test
    fun `cancel on a clean field closes at once`() = runTest(main.dispatcher) {
        val vm = vm()
        vm.events.test {
            vm.cancel()
            assertEquals(CheckTranscriptEvent.Close, awaitItem())
        }
        assertFalse(vm.ready().confirmDiscard)
    }

    @Test
    fun `cancel on a dirty field asks first`() = runTest(main.dispatcher) {
        val vm = vm()
        vm.editText("changed")
        vm.events.test {
            vm.cancel()
            assertTrue(vm.ready().confirmDiscard)
            expectNoEvents()
            vm.keepEditing()
            assertFalse(vm.ready().confirmDiscard)
            vm.cancel()
            vm.confirmDiscard()
            assertEquals(CheckTranscriptEvent.Close, awaitItem())
        }
        assertEquals("changed", vm.ready().text)
        assertTrue(repo.verifications.isEmpty())
    }

    @Test
    fun `cancel while loading closes`() = runTest(main.dispatcher) {
        repo.list = ApiResult.Failure(ApiError.Network(IOException("x")))
        val vm = CheckTranscriptViewModel(SnippetId("s1"), RecordingId("r1"), repo, cache, player, updates)
        runCurrent()
        vm.events.test {
            vm.cancel()
            assertEquals(CheckTranscriptEvent.Close, awaitItem())
        }
    }

    // ---- player ----------------------------------------------------------------------------

    @Test
    fun `play, poll the position, pause and resume`() = runTest(main.dispatcher) {
        val vm = vm()
        vm.togglePlay()
        assertEquals(File(cache, "recording-r1.ogg"), player.played.single())
        assertEquals(PlayerUi.Ready(0, 2_000, playing = true), vm.ready().player)
        player.position = 450
        advanceTimeBy(101)
        assertEquals(450L, (vm.ready().player as PlayerUi.Ready).positionMs)
        assertEquals(1, vm.ready().currentWord)
        vm.togglePlay()
        assertEquals(1, player.pauses)
        assertFalse((vm.ready().player as PlayerUi.Ready).playing)
        player.position = 999 // would be read only while playing
        advanceTimeBy(500)
        assertEquals(450L, (vm.ready().player as PlayerUi.Ready).positionMs)
        vm.togglePlay()
        assertEquals(1, player.resumes)
        assertEquals(1, player.played.size, "resume does not restart")
        vm.pause()
    }

    @Test
    fun `completion rewinds to the start`() = runTest(main.dispatcher) {
        val vm = vm()
        vm.togglePlay()
        player.position = 1_900
        advanceTimeBy(101)
        player.complete()
        assertEquals(PlayerUi.Ready(0, 2_000, playing = false), vm.ready().player)
        advanceTimeBy(500)
        vm.togglePlay()
        assertEquals(2, player.played.size, "after the end, play starts the file again")
        vm.pause()
    }

    @Test
    fun `seeking before playing starts from there`() = runTest(main.dispatcher) {
        val vm = vm()
        vm.seekTo(1_200)
        assertEquals(1_200L, (vm.ready().player as PlayerUi.Ready).positionMs)
        assertEquals(2, vm.ready().currentWord)
        assertTrue(player.seeks.isEmpty(), "nothing loaded in the player yet")
        vm.togglePlay()
        assertEquals(listOf(1_200L), player.seeks)
        vm.pause()
    }

    @Test
    fun `seeking is clamped to the recording`() = runTest(main.dispatcher) {
        val vm = vm()
        vm.seekTo(-5)
        assertEquals(0L, (vm.ready().player as PlayerUi.Ready).positionMs)
        vm.seekTo(99_000)
        assertEquals(2_000L, (vm.ready().player as PlayerUi.Ready).positionMs)
    }

    @Test
    fun `tapping a word seeks to it and plays`() = runTest(main.dispatcher) {
        val vm = vm()
        vm.seekToWord(2)
        assertEquals(1_000L, (vm.ready().player as PlayerUi.Ready).positionMs)
        assertTrue((vm.ready().player as PlayerUi.Ready).playing)
        assertEquals(listOf(1_000L), player.seeks)
        assertEquals(2, vm.ready().currentWord)
        // while playing: seek only
        vm.seekToWord(0)
        assertEquals(listOf(1_000L, 0L), player.seeks)
        assertEquals(1, player.played.size)
        vm.seekToWord(9) // out of range: ignored
        assertEquals(2, player.seeks.size)
        vm.pause()
    }

    @Test
    fun `word taps do nothing without timings`() = runTest(main.dispatcher) {
        val vm = vm(recording("r1", transcript = "a"))
        vm.seekToWord(0)
        assertTrue(player.played.isEmpty())
    }

    @Test
    fun `an unplayable file is reported and removed from the cache`() = runTest(main.dispatcher) {
        val vm = vm()
        player.canPlay = false
        vm.togglePlay()
        assertEquals(PlayerUi.Failed(PlayerFailure.Unplayable), vm.ready().player)
        assertFalse(File(cache, "recording-r1.ogg").exists())
    }

    @Test
    fun `pause from the lifecycle stops polling`() = runTest(main.dispatcher) {
        val vm = vm()
        vm.togglePlay()
        vm.pause()
        assertEquals(1, player.pauses)
        assertFalse((vm.ready().player as PlayerUi.Ready).playing)
        vm.pause() // idempotent
        assertEquals(1, player.pauses)
    }

    @Test
    fun `saving keeps the published events in order`() = runTest(main.dispatcher) {
        val vm = vm()
        val all = mutableListOf<Recording>()
        val job = launch { updates.updates.toList(all) }
        runCurrent()
        vm.save()
        runCurrent()
        job.cancel()
        assertEquals(1, all.size)
    }
}
