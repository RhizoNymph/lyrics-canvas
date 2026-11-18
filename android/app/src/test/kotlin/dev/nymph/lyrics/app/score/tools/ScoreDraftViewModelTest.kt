package dev.nymph.lyrics.app.score.tools

import dev.nymph.lyrics.app.MainDispatcherExtension
import dev.nymph.lyrics.app.voice.FakeVoiceRepository
import dev.nymph.lyrics.app.voice.recording
import dev.nymph.lyrics.logic.score.tools.DraftFormProblem
import dev.nymph.lyrics.logic.score.tools.DraftSource
import dev.nymph.lyrics.logic.score.tools.ScoreToolErrorKind
import dev.nymph.lyrics.model.SnippetId
import dev.nymph.lyrics.model.error.ApiError
import dev.nymph.lyrics.model.error.ApiResult
import dev.nymph.lyrics.model.score.DraftMeter
import dev.nymph.lyrics.model.score.ScoreDraftRequest
import dev.nymph.lyrics.model.score.ScoreDraftResponse
import dev.nymph.lyrics.model.score.ScoreSnap
import dev.nymph.lyrics.model.voice.RecordingId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

@OptIn(ExperimentalCoroutinesApi::class)
class ScoreDraftViewModelTest {
    @JvmField
    @RegisterExtension
    val main = MainDispatcherExtension()

    private val s1 = SnippetId("s1")
    private val tools = FakeScoreToolsRepository()
    private val voice = FakeVoiceRepository().apply {
        list = ApiResult.Success(
            listOf(
                recording("r-new", createdAt = "2026-11-03T10:00:00Z"),
                recording("r-mid", createdAt = "2026-11-02T10:00:00Z").copy(hasTimings = true),
                recording("r-old", createdAt = "2026-11-01T10:00:00Z").copy(hasTimings = true),
            ),
        )
    }

    private fun TestScope.vm(): ScoreDraftViewModel = ScoreDraftViewModel(s1, tools, voice).also { runCurrent() }

    @Test
    fun `opens on a grid form and loads no takes`() = runTest(main.dispatcher) {
        val vm = vm()
        assertEquals(DraftSource.GRID, vm.state.value.form.source)
        assertEquals(DraftTakes.NotLoaded, vm.state.value.takes)
        assertEquals(0, voice.listCalls)
        assertTrue(vm.state.value.canDraft)
    }

    @Test
    fun `a grid draft sends the form and shows the result`() = runTest(main.dispatcher) {
        val vm = vm()
        vm.setNote(ScoreSnap.SIXTEENTH)
        vm.setMeter(DraftMeter(3, 4))
        vm.setBpm("100")
        vm.draft()
        runCurrent()
        assertEquals(s1 to ScoreDraftRequest.FromGrid(ScoreSnap.SIXTEENTH, DraftMeter(3, 4), 100.0), tools.drafts.single())
        val ready = vm.state.value.phase as DraftPhase.Ready
        assertEquals(DRAFT_SCORE, ready.draft.score)
        assertEquals("Every syllable placed", ready.summary)
    }

    @Test
    fun `an invalid form does not draft and names the problem`() = runTest(main.dispatcher) {
        val vm = vm()
        vm.setBpm("5")
        assertFalse(vm.state.value.canDraft)
        assertEquals(DraftFormProblem.BPM_RANGE, vm.state.value.problem)
        vm.draft()
        runCurrent()
        assertTrue(tools.drafts.isEmpty())
        vm.setMeterNum("40")
        assertEquals(DraftFormProblem.METER_NUM, vm.state.value.problem)
    }

    @Test
    fun `switching to a recording loads the takes and picks the newest with timings`() = runTest(main.dispatcher) {
        val vm = vm()
        vm.setSource(DraftSource.RECORDING)
        runCurrent()
        assertEquals(1, voice.listCalls)
        assertEquals(3, (vm.state.value.takes as DraftTakes.Loaded).items.size)
        assertEquals(RecordingId("r-mid"), vm.state.value.form.recordingId)
        assertEquals("", vm.state.value.form.bpm)
        vm.setSource(DraftSource.GRID)
        vm.setSource(DraftSource.RECORDING)
        runCurrent()
        assertEquals(1, voice.listCalls)
    }

    @Test
    fun `a recording draft shows the estimate and counts`() = runTest(main.dispatcher) {
        tools.draftResult = { ApiResult.Success(ScoreDraftResponse(DRAFT_SCORE, bpmEstimated = 101.5, unplaced = 2, unmatchedWords = 1)) }
        val vm = vm()
        vm.setSource(DraftSource.RECORDING)
        runCurrent()
        vm.pickRecording(RecordingId("r-old"))
        vm.draft()
        runCurrent()
        assertEquals(ScoreDraftRequest.FromRecording(RecordingId("r-old"), DraftMeter(4, 4), null), tools.drafts.single().second)
        val ready = vm.state.value.phase as DraftPhase.Ready
        assertEquals(101.5, ready.draft.bpmEstimated)
        assertEquals("Estimated tempo 101.5 bpm · 2 syllables unplaced · 1 word not matched", ready.summary)
    }

    @Test
    fun `a take without timings cannot be drafted`() = runTest(main.dispatcher) {
        val vm = vm()
        vm.setSource(DraftSource.RECORDING)
        runCurrent()
        vm.pickRecording(RecordingId("r-new"))
        assertEquals(DraftFormProblem.RECORDING_NO_TIMINGS, vm.state.value.problem)
        assertFalse(vm.state.value.canDraft)
    }

    @Test
    fun `failed takes list can be retried`() = runTest(main.dispatcher) {
        voice.list = ApiResult.Failure(ApiError.NeedsConnection)
        val vm = vm()
        vm.setSource(DraftSource.RECORDING)
        runCurrent()
        assertEquals(DraftTakes.Failed("Needs connection"), vm.state.value.takes)
        assertFalse(vm.state.value.canDraft)
        voice.list = ApiResult.Success(listOf(recording("r1").copy(hasTimings = true)))
        vm.retryTakes()
        runCurrent()
        assertEquals(RecordingId("r1"), vm.state.value.form.recordingId)
    }

    @Test
    fun `a typed draft error is kept and editing clears it`() = runTest(main.dispatcher) {
        tools.draftResult = { ApiResult.Failure(ApiError.Validation(422, "x", "draft_empty")) }
        val vm = vm()
        vm.draft()
        runCurrent()
        val failed = vm.state.value.phase as DraftPhase.Failed
        assertEquals(ScoreToolErrorKind.DRAFT_EMPTY, failed.error.kind)
        vm.setBpm("91")
        assertEquals(DraftPhase.Editing, vm.state.value.phase)
    }

    @Test
    fun `offline the draft answers needs connection`() = runTest(main.dispatcher) {
        tools.draftResult = { ApiResult.Failure(ApiError.NeedsConnection) }
        val vm = vm()
        vm.draft()
        runCurrent()
        assertEquals(ScoreToolErrorKind.NEEDS_CONNECTION, (vm.state.value.phase as DraftPhase.Failed).error.kind)
    }

    @Test
    fun `editing while drafting cancels the draft`() = runTest(main.dispatcher) {
        tools.draftGate = CompletableDeferred()
        val vm = vm()
        vm.draft()
        runCurrent()
        assertEquals(DraftPhase.Drafting, vm.state.value.phase)
        vm.draft()
        runCurrent()
        assertEquals(1, tools.drafts.size)
        vm.setNote(ScoreSnap.SIXTEENTH)
        tools.draftGate!!.complete(Unit)
        runCurrent()
        assertEquals(DraftPhase.Editing, vm.state.value.phase)
    }

    @Test
    fun `accept gives the ready score once, then nothing`() = runTest(main.dispatcher) {
        val vm = vm()
        assertNull(vm.accept())
        vm.draft()
        runCurrent()
        assertEquals(DRAFT_SCORE, vm.accept())
        assertEquals(DraftPhase.Editing, vm.state.value.phase)
        assertNull(vm.accept())
    }
}
