package dev.nymph.lyrics.app.score.tools

import dev.nymph.lyrics.app.MainDispatcherExtension
import dev.nymph.lyrics.data.DownloadedFile
import dev.nymph.lyrics.logic.score.tools.ScoreToolErrorKind
import dev.nymph.lyrics.model.SnippetId
import dev.nymph.lyrics.model.error.ApiError
import dev.nymph.lyrics.model.error.ApiResult
import dev.nymph.lyrics.model.score.ScoreExportFormat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.io.File
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class ScoreExportViewModelTest {
    @JvmField
    @RegisterExtension
    val main = MainDispatcherExtension()

    private lateinit var dir: File
    private val tools = FakeScoreToolsRepository()
    private val s1 = SnippetId("s1234abcd-ffff")

    @BeforeEach
    fun makeDir() {
        dir = File("build/tmp/score-export-test/${UUID.randomUUID()}").apply { mkdirs() }
    }

    @AfterEach
    fun removeDir() {
        dir.deleteRecursively()
    }

    private fun TestScope.vm() = ScoreExportViewModel(s1, tools, dir, io = main.dispatcher).also { runCurrent() }

    @Test
    fun `share exports into the cache under the server's name`() = runTest(main.dispatcher) {
        val vm = vm()
        vm.export(ScoreExportFormat.MIDI, ExportAction.SHARE)
        runCurrent()
        val ready = vm.ready.first()
        assertEquals(ExportAction.SHARE, ready.action)
        assertEquals(ScoreExportFormat.MIDI, ready.format)
        assertEquals("Night drive.mid", ready.name)
        assertEquals("Night drive.mid", ready.file.name)
        assertTrue(ready.file.exists())
        assertEquals("score", ready.file.readText())
        assertEquals(ExportPhase.Idle, vm.state.value)
        assertEquals(s1 to ScoreExportFormat.MIDI, tools.exports.single().let { it.first to it.second })
    }

    @Test
    fun `save hands over a MusicXML file with its mime`() = runTest(main.dispatcher) {
        val vm = vm()
        vm.export(ScoreExportFormat.MUSICXML, ExportAction.SAVE)
        runCurrent()
        val ready = vm.ready.first()
        assertEquals(ExportAction.SAVE, ready.action)
        assertEquals("Night drive.musicxml", ready.name)
        assertEquals("application/vnd.recordare.musicxml+xml", ready.format.mime)
    }

    @Test
    fun `without a suggested name the snippet id names the file`() = runTest(main.dispatcher) {
        tools.exportResult = { _, f ->
            f.parentFile?.mkdirs()
            f.writeText("x")
            ApiResult.Success(DownloadedFile(f, null))
        }
        val vm = vm()
        vm.export(ScoreExportFormat.MIDI, ExportAction.SHARE)
        runCurrent()
        assertEquals("snippet-s1234abc.mid", vm.ready.first().name)
    }

    @Test
    fun `a new export replaces the files of the last one`() = runTest(main.dispatcher) {
        val vm = vm()
        vm.export(ScoreExportFormat.MIDI, ExportAction.SHARE)
        runCurrent()
        val first = vm.ready.first().file
        vm.export(ScoreExportFormat.MUSICXML, ExportAction.SHARE)
        runCurrent()
        vm.ready.first()
        assertFalse(first.exists())
    }

    @Test
    fun `score_not_found is a typed failure and can be dismissed`() = runTest(main.dispatcher) {
        tools.exportResult = { _, _ -> ApiResult.Failure(ApiError.NotFound("none", "score_not_found")) }
        val vm = vm()
        vm.export(ScoreExportFormat.MIDI, ExportAction.SAVE)
        runCurrent()
        assertEquals(ScoreToolErrorKind.SCORE_NOT_FOUND, (vm.state.value as ExportPhase.Failed).error.kind)
        vm.dismissError()
        assertEquals(ExportPhase.Idle, vm.state.value)
    }

    @Test
    fun `offline export says it needs a connection`() = runTest(main.dispatcher) {
        tools.exportResult = { _, _ -> ApiResult.Failure(ApiError.NeedsConnection) }
        val vm = vm()
        vm.export(ScoreExportFormat.MIDI, ExportAction.SHARE)
        runCurrent()
        assertEquals(ScoreToolErrorKind.NEEDS_CONNECTION, (vm.state.value as ExportPhase.Failed).error.kind)
    }

    @Test
    fun `only one export runs at a time`() = runTest(main.dispatcher) {
        tools.exportGate = CompletableDeferred()
        val vm = vm()
        vm.export(ScoreExportFormat.MIDI, ExportAction.SHARE)
        runCurrent()
        assertEquals(ExportPhase.Exporting(ScoreExportFormat.MIDI, ExportAction.SHARE), vm.state.value)
        vm.export(ScoreExportFormat.MUSICXML, ExportAction.SAVE)
        runCurrent()
        assertEquals(1, tools.exports.size)
        tools.exportGate!!.complete(Unit)
        runCurrent()
        assertEquals(ScoreExportFormat.MIDI, vm.ready.first().format)
    }
}
