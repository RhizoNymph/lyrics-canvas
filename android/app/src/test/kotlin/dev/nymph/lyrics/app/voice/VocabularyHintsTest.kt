package dev.nymph.lyrics.app.voice

import dev.nymph.lyrics.app.MainDispatcherExtension
import dev.nymph.lyrics.app.fakes.CANVAS_ID
import dev.nymph.lyrics.model.voice.VoiceTakeTarget
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
@Timeout(value = 30, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class VocabularyHintsTest {
    @JvmField
    @RegisterExtension
    val main = MainDispatcherExtension()

    private val recorder = FakeAudioRecorder()
    private val repo = FakeVoiceRepository()

    private fun TestScope.vm(settings: FakeVoiceSettings): VoiceCaptureViewModel {
        val vm = VoiceCaptureViewModel(CANVAS_ID, repo, recorder, settings, clock = { testScheduler.currentTime }, deleteFile = {})
        runCurrent()
        return vm
    }

    private fun TestScope.recordAndStop(vm: VoiceCaptureViewModel) {
        vm.open(VoiceTakeTarget.New(0.0, 0.0))
        vm.permissionGranted()
        advanceTimeBy(1_000)
        vm.stop()
        runCurrent()
    }

    @Test
    fun `hints are on by default and sent with the take`() = runTest(main.dispatcher) {
        val vm = vm(FakeVoiceSettings())
        assertTrue(vm.vocabularyHints.value)
        recordAndStop(vm)
        assertEquals(1, repo.uploads.size)
        assertTrue(repo.uploads[0].meta.vocabularyHints)
    }

    @Test
    fun `a stored off is read back and sent`() = runTest(main.dispatcher) {
        val vm = vm(FakeVoiceSettings(initialHints = false))
        assertFalse(vm.vocabularyHints.value)
        recordAndStop(vm)
        assertFalse(repo.uploads[0].meta.vocabularyHints)
    }

    @Test
    fun `turning hints off persists and applies to the next upload`() = runTest(main.dispatcher) {
        val settings = FakeVoiceSettings()
        val vm = vm(settings)
        vm.setVocabularyHints(false)
        runCurrent()
        assertFalse(settings.hints.value)
        assertFalse(vm.vocabularyHints.value)
        recordAndStop(vm)
        assertFalse(repo.uploads[0].meta.vocabularyHints)
    }
}
