package dev.nymph.lyrics.app.voice

import app.cash.turbine.test
import dev.nymph.lyrics.app.MainDispatcherExtension
import dev.nymph.lyrics.app.fakes.CANVAS_ID
import dev.nymph.lyrics.logic.voice.AudioFile
import dev.nymph.lyrics.logic.voice.CapturedTake
import dev.nymph.lyrics.logic.voice.HoldReason
import dev.nymph.lyrics.logic.voice.PermissionStatus
import dev.nymph.lyrics.logic.voice.RecorderFailure
import dev.nymph.lyrics.logic.voice.RecorderState
import dev.nymph.lyrics.logic.voice.SilenceTimeout
import dev.nymph.lyrics.logic.voice.VoiceUploadError
import dev.nymph.lyrics.model.SnippetId
import dev.nymph.lyrics.model.voice.AudioMime
import dev.nymph.lyrics.model.voice.VoiceErrorCode
import dev.nymph.lyrics.model.voice.VoiceTakeTarget
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.extension.RegisterExtension

private val NEW = VoiceTakeTarget.New(120.0, 80.0)

@OptIn(ExperimentalCoroutinesApi::class)
/** Tests that leave a take recording cancel it at the end: the 50 ms polling loop keeps virtual time busy. */
@Timeout(value = 30, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class VoiceCaptureViewModelTest {
    @JvmField
    @RegisterExtension
    val main = MainDispatcherExtension()

    private val recorder = FakeAudioRecorder()
    private val repo = FakeVoiceRepository()
    private val settings = FakeVoiceSettings()
    private val deleted = mutableListOf<AudioFile>()

    /** The fake clock is the test scheduler's virtual time. */
    private fun TestScope.vm(): VoiceCaptureViewModel {
        val vm = VoiceCaptureViewModel(CANVAS_ID, repo, recorder, settings, clock = { testScheduler.currentTime }, deleteFile = { deleted += it })
        runCurrent()
        return vm
    }

    private fun TestScope.recordingVm(): VoiceCaptureViewModel = vm().apply {
        open(NEW)
        permissionGranted()
    }

    @Test
    fun `open waits for the permission, then granting starts the recorder at the current clock`() = runTest(main.dispatcher) {
        val vm = vm()
        vm.open(NEW)
        assertEquals(RecorderState.Permission(NEW, PermissionStatus.REQUESTING, 1), vm.state.value)
        assertEquals(0, recorder.starts)
        advanceTimeBy(500)
        vm.permissionGranted()
        val rec = vm.state.value as RecorderState.Recording
        assertEquals(1, recorder.starts)
        assertEquals(testScheduler.currentTime, rec.startedAtMs)
        vm.cancel() // stop the polling loop, or the scheduler never goes idle
    }

    @Test
    fun `polling feeds the level and elapsed time`() = runTest(main.dispatcher) {
        val vm = recordingVm()
        recorder.amplitude = 32_767
        advanceTimeBy(1_001)
        val rec = vm.state.value as RecorderState.Recording
        assertEquals(1_000L, rec.elapsedMs)
        assertEquals(1f, rec.level)
        vm.cancel()
    }

    @Test
    fun `taps and a double tap produce the take's breaks relative to the recorder start`() = runTest(main.dispatcher) {
        repo.gate = CompletableDeferred() // hold the upload so the Uploading state can be observed
        val vm = recordingVm()
        advanceTimeBy(1_000)
        vm.tap() // break at 1000
        advanceTimeBy(400)
        assertEquals(2, (vm.state.value as RecorderState.Recording).lineNumber)
        advanceTimeBy(1_600)
        vm.tap() // first of the double tap at 3000
        advanceTimeBy(150)
        vm.tap()
        runCurrent()
        assertEquals(1, recorder.finishes)
        val uploading = vm.state.value as RecorderState.Uploading
        assertEquals(CapturedTake(TAKE_AUDIO, 3_150, listOf(1_000)), uploading.take)
        assertEquals(1, repo.uploads.size)
        assertEquals(listOf(1_000L), repo.uploads[0].meta.breaksMs)
        assertEquals(3_150L, repo.uploads[0].meta.durationMs)
        assertEquals(NEW, repo.uploads[0].meta.target)
        assertEquals(AudioMime.OGG, repo.uploads[0].mime)
        assertEquals(TAKE_AUDIO.path, repo.uploads[0].path)
        repo.gate!!.complete(Unit)
        runCurrent()
        assertEquals(RecorderState.Idle, vm.state.value)
    }

    @Test
    fun `a stored take is handed to the host once and the modal closes`() = runTest(main.dispatcher) {
        val vm = recordingVm()
        vm.saved.test {
            advanceTimeBy(2_000)
            vm.stop()
            runCurrent()
            val saved = awaitItem()
            assertEquals(NEW, saved.target)
            assertEquals(takeResponse(), saved.response)
            assertEquals(RecorderState.Idle, vm.state.value)
            assertEquals(listOf(TAKE_AUDIO), deleted)
            expectNoEvents()
        }
    }

    @Test
    fun `an upload failure keeps the file, retry re-sends it, discard deletes it`() = runTest(main.dispatcher) {
        repo.uploadResult = { FakeVoiceRepository.STT_DOWN }
        val vm = recordingVm()
        advanceTimeBy(2_000)
        vm.stop()
        runCurrent()
        val held = vm.state.value as RecorderState.Held
        assertEquals(HoldReason.UploadFailed(VoiceUploadError.Rejected(VoiceErrorCode.STT_UNAVAILABLE, 503, "STT down")), held.reason)
        assertTrue(deleted.isEmpty())
        vm.retry()
        runCurrent()
        assertEquals(2, repo.uploads.size)
        assertEquals(repo.uploads[0], repo.uploads[1])
        vm.discard()
        assertEquals(RecorderState.Idle, vm.state.value)
        assertEquals(listOf(TAKE_AUDIO), deleted)
    }

    @Test
    fun `cancel while uploading cancels the call and deletes the file`() = runTest(main.dispatcher) {
        repo.gate = CompletableDeferred()
        val vm = recordingVm()
        advanceTimeBy(1_000)
        vm.stop()
        runCurrent()
        assertTrue(vm.state.value is RecorderState.Uploading)
        vm.cancel()
        repo.gate!!.complete(Unit)
        runCurrent()
        assertEquals(RecorderState.Idle, vm.state.value)
        assertEquals(0, repo.completedUploads)
        assertEquals(listOf(TAKE_AUDIO), deleted)
    }

    @Test
    fun `cancel while recording releases the recorder and stops polling`() = runTest(main.dispatcher) {
        val vm = recordingVm()
        advanceTimeBy(500)
        vm.cancel()
        assertEquals(1, recorder.aborts)
        assertEquals(listOf(TAKE_AUDIO), deleted)
        advanceTimeBy(5_000)
        assertEquals(RecorderState.Idle, vm.state.value)
        assertTrue(repo.uploads.isEmpty())
    }

    @Test
    fun `silence auto-stop uses the persisted setting`() = runTest(main.dispatcher) {
        settings.value.value = SilenceTimeout.S2
        val vm = recordingVm()
        assertEquals(SilenceTimeout.S2, (vm.state.value as RecorderState.Recording).silence)
        recorder.amplitude = 100
        advanceTimeBy(1_000)
        recorder.amplitude = 8_000
        advanceTimeBy(1_000)
        recorder.amplitude = 100
        advanceTimeBy(2_100)
        assertEquals(1, recorder.finishes)
        assertEquals(1, repo.uploads.size)
    }

    @Test
    fun `changing the silence setting persists it and applies to the running take`() = runTest(main.dispatcher) {
        val vm = recordingVm()
        vm.setSilence(SilenceTimeout.S5)
        runCurrent()
        assertEquals(SilenceTimeout.S5, settings.value.value)
        assertEquals(SilenceTimeout.S5, vm.silence.value)
        assertEquals(SilenceTimeout.S5, (vm.state.value as RecorderState.Recording).silence)
        vm.cancel()
    }

    @Test
    fun `going to the background holds the take for the user`() = runTest(main.dispatcher) {
        val vm = recordingVm()
        advanceTimeBy(1_500)
        vm.hostStopped()
        val held = vm.state.value as RecorderState.Held
        assertEquals(HoldReason.Interrupted, held.reason)
        assertTrue(repo.uploads.isEmpty())
        assertEquals(1, recorder.finishes)
        vm.retry()
        runCurrent()
        assertEquals(1, repo.uploads.size)
    }

    @Test
    fun `a recorder that cannot start shows a failure and retry asks again`() = runTest(main.dispatcher) {
        recorder.startResult = FakeAudioRecorder.FAILED
        val vm = vm()
        vm.open(NEW)
        vm.permissionGranted()
        assertEquals(RecorderState.RecorderFailed(NEW, RecorderFailure.START_FAILED), vm.state.value)
        vm.retry()
        assertTrue(vm.state.value is RecorderState.Permission)
    }

    @Test
    fun `a take with no audio is reported and its file removed`() = runTest(main.dispatcher) {
        recorder.finishResult = false
        val vm = recordingVm()
        advanceTimeBy(100)
        vm.stop()
        assertEquals(RecorderState.RecorderFailed(NEW, RecorderFailure.NO_AUDIO), vm.state.value)
        assertEquals(listOf(TAKE_AUDIO), deleted)
        assertTrue(repo.uploads.isEmpty())
    }

    @Test
    fun `permission denial paths`() = runTest(main.dispatcher) {
        val vm = vm()
        vm.open(VoiceTakeTarget.Append(SnippetId("s1")))
        vm.permissionRationaleNeeded()
        assertEquals(PermissionStatus.RATIONALE, (vm.state.value as RecorderState.Permission).status)
        vm.requestPermissionAgain()
        vm.permissionDenied(permanently = true)
        assertEquals(PermissionStatus.PERMANENTLY_DENIED, (vm.state.value as RecorderState.Permission).status)
        vm.cancel()
        assertEquals(RecorderState.Idle, vm.state.value)
        assertEquals(0, recorder.starts)
    }
}
