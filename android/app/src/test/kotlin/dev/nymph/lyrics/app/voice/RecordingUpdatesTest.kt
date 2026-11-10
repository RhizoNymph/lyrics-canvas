package dev.nymph.lyrics.app.voice

import dev.nymph.lyrics.app.MainDispatcherExtension
import dev.nymph.lyrics.app.voice.recordings.RecordingUpdates
import dev.nymph.lyrics.app.voice.recordings.RecordingsList
import dev.nymph.lyrics.app.voice.recordings.RecordingsViewModel
import dev.nymph.lyrics.model.SnippetId
import dev.nymph.lyrics.model.error.ApiResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class RecordingUpdatesTest {
    @JvmField
    @RegisterExtension
    val main = MainDispatcherExtension()

    @Test
    fun `a checked recording replaces its row in the editor list`() = runTest(main.dispatcher) {
        val repo = FakeVoiceRepository()
        val r1 = recording("r1")
        val r2 = recording("r2")
        repo.list = ApiResult.Success(listOf(r1, r2))
        val updates = RecordingUpdates()
        val vm = RecordingsViewModel(SnippetId("s1"), repo, File("build/tmp/unused"), SilentPlayer(), updates.updates)
        runCurrent()
        val checked = r2.copy(verifiedText = "said", verifiedAt = "2026-11-06T10:00:00Z")
        updates.publish(checked)
        runCurrent()
        assertEquals(RecordingsList.Loaded(listOf(r1, checked)), vm.state.value.list)
    }

    @Test
    fun `an update for another snippet's recording is ignored`() = runTest(main.dispatcher) {
        val repo = FakeVoiceRepository()
        val r1 = recording("r1")
        repo.list = ApiResult.Success(listOf(r1))
        val updates = RecordingUpdates()
        val vm = RecordingsViewModel(SnippetId("s1"), repo, File("build/tmp/unused"), SilentPlayer(), updates.updates)
        runCurrent()
        updates.publish(recording("r9", snippetId = "s2"))
        runCurrent()
        assertEquals(RecordingsList.Loaded(listOf(r1)), vm.state.value.list)
    }
}
