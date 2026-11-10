package dev.nymph.lyrics.app.voice

import dev.nymph.lyrics.app.MainDispatcherExtension
import dev.nymph.lyrics.app.voice.dataset.DatasetStatsState
import dev.nymph.lyrics.app.voice.dataset.VoiceDatasetViewModel
import dev.nymph.lyrics.app.voice.dataset.datasetSummary
import dev.nymph.lyrics.app.voice.dataset.formatAudioLength
import dev.nymph.lyrics.logic.voice.VoiceUploadError
import dev.nymph.lyrics.model.error.ApiError
import dev.nymph.lyrics.model.error.ApiResult
import dev.nymph.lyrics.model.voice.RecordingStats
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.io.IOException

class DatasetFormatTest {
    @Test
    fun `audio length reads as minutes and seconds`() {
        assertEquals("0s", formatAudioLength(0))
        assertEquals("0s", formatAudioLength(-10))
        assertEquals("42s", formatAudioLength(42_999))
        assertEquals("4m 10s", formatAudioLength(250_000))
        assertEquals("5m 0s", formatAudioLength(300_000))
        assertEquals("1h 2m", formatAudioLength(3_725_000))
    }

    @Test
    fun `the summary follows the contract wording`() {
        assertEquals(
            "12 of 15 takes checked · 4m 10s checked audio · 9 with word timings",
            datasetSummary(RecordingStats(15, 12, 400_000, 250_000, 9)),
        )
        assertEquals(
            "0 of 1 take checked · 0s checked audio · 0 with word timings",
            datasetSummary(RecordingStats(1, 0, 3_000, 0, 0)),
        )
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceDatasetViewModelTest {
    @JvmField
    @RegisterExtension
    val main = MainDispatcherExtension()

    private val repo = FakeVoiceRepository()

    @Test
    fun `closed until opened, then loads the stats each time`() = runTest(main.dispatcher) {
        val vm = VoiceDatasetViewModel(repo)
        runCurrent()
        assertEquals(DatasetStatsState.Closed, vm.state.value)
        assertEquals(0, repo.statsCalls)
        repo.statsGate = CompletableDeferred()
        vm.open()
        assertEquals(DatasetStatsState.Loading, vm.state.value)
        repo.statsResult = ApiResult.Success(RecordingStats(3, 1, 9_000, 3_000, 2))
        repo.statsGate!!.complete(Unit)
        runCurrent()
        assertEquals(DatasetStatsState.Loaded(RecordingStats(3, 1, 9_000, 3_000, 2)), vm.state.value)
        vm.close()
        assertEquals(DatasetStatsState.Closed, vm.state.value)
        repo.statsGate = null
        vm.open()
        runCurrent()
        assertEquals(2, repo.statsCalls)
    }

    @Test
    fun `a failure is typed and can be retried`() = runTest(main.dispatcher) {
        val vm = VoiceDatasetViewModel(repo)
        repo.statsResult = ApiResult.Failure(ApiError.Network(IOException("off")))
        vm.open()
        runCurrent()
        assertEquals(DatasetStatsState.Failed(VoiceUploadError.Network("off")), vm.state.value)
        repo.statsResult = ApiResult.Success(RecordingStats(0, 0, 0, 0, 0))
        vm.open()
        runCurrent()
        assertEquals(DatasetStatsState.Loaded(RecordingStats(0, 0, 0, 0, 0)), vm.state.value)
    }

    @Test
    fun `a result arriving after close is dropped`() = runTest(main.dispatcher) {
        val vm = VoiceDatasetViewModel(repo)
        repo.statsGate = CompletableDeferred()
        vm.open()
        runCurrent()
        vm.close()
        repo.statsGate!!.complete(Unit)
        runCurrent()
        assertEquals(DatasetStatsState.Closed, vm.state.value)
    }
}
