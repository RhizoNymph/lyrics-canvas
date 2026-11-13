package dev.nymph.lyrics.app.rhyme.pattern

import dev.nymph.lyrics.app.MainDispatcherExtension
import dev.nymph.lyrics.app.fakes.FakeSnippetRepository
import dev.nymph.lyrics.app.fakes.catSnippet
import dev.nymph.lyrics.app.rhyme.FakeCanvasPort
import dev.nymph.lyrics.logic.rhyme.RhymeSources
import dev.nymph.lyrics.logic.rhyme.pattern.LinePick
import dev.nymph.lyrics.logic.rhyme.pattern.PatternError
import dev.nymph.lyrics.logic.rhyme.pattern.PatternOperation
import dev.nymph.lyrics.logic.rhyme.pattern.RangeProblem
import dev.nymph.lyrics.logic.rhyme.pattern.SlotEdit
import dev.nymph.lyrics.logic.rhyme.pattern.SlotId
import dev.nymph.lyrics.logic.tags.ExpressionField
import dev.nymph.lyrics.model.DerivedFrom
import dev.nymph.lyrics.model.PatternErrorCode
import dev.nymph.lyrics.model.Provenance
import dev.nymph.lyrics.model.SourceName
import dev.nymph.lyrics.model.error.ApiError
import dev.nymph.lyrics.model.error.ApiResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

@OptIn(ExperimentalCoroutinesApi::class)
class PatternViewModelTest {
    @JvmField
    @RegisterExtension
    val main = MainDispatcherExtension()

    private val song = PatternFx.song()
    private val a = song.id
    private val repo = FakeRhymePatternRepository()
    private val snippets = FakeSnippetRepository()
    private val seed = PatternSeed(0.6, RhymeSources(), includeRare = true, tagFilter = ExpressionField("love night", 0))

    private fun TestScope.entered(port: FakeCanvasPort = FakeCanvasPort(listOf(song), setOf(a))): PatternViewModel {
        val vm = PatternViewModel(port, repo, snippets)
        vm.toggleLinePick(a)
        vm.onLineTap(a, 1, seed)
        vm.onLineTap(a, 3, seed)
        advanceUntilIdle()
        return vm
    }

    private val PatternViewModel.session: PatternSession get() = state.value.session!!

    @Test
    fun `picking a line range enters pattern mode, detects at once and suggests the on slots`() = runTest(main.dispatcher) {
        val vm = PatternViewModel(FakeCanvasPort(listOf(song), setOf(a)), repo, snippets)
        vm.toggleLinePick(a)
        vm.onLineTap(a, 3, seed)
        assertEquals(LinePick.AwaitingLast(a, 3), vm.state.value.linePick)
        vm.onLineTap(a, 1, seed)
        runCurrent()
        val detect = repo.detects.single()
        assertEquals(1 to 4, detect.lineStart to detect.lineEnd)
        assertEquals(0.75, detect.minScore)
        assertTrue(repo.suggests.isEmpty())
        advanceTimeBy(151)
        runCurrent()
        val suggest = repo.suggests.single()
        // s2 is off by default.
        assertEquals(listOf("s0", "s1"), suggest.slots.map { it.id })
        assertEquals(0.6, suggest.minScore)
        assertTrue(suggest.includeRare)
        assertEquals("love & night", suggest.tagFilter)
        val session = vm.session
        assertEquals(listOf("s0", "s1"), session.shownColumns.map { it.id.value })
        assertEquals(listOf("note:n0:0:0"), session.lineMatches.matches.map { it.unit.id })
        assertEquals(LinePick.Off, vm.state.value.linePick)
        assertEquals("I keep it steady when the p…", session.label)
    }

    @Test
    fun `a range with one line of words is refused and keeps the first line`() = runTest(main.dispatcher) {
        val solo = dev.nymph.lyrics.app.fakes.snippet("solo", "x", listOf(PatternFx.gridLine("x"), PatternFx.gridLine("y"), dev.nymph.lyrics.model.GridLine(emptyList())))
        val vm = PatternViewModel(FakeCanvasPort(listOf(solo), setOf(solo.id)), repo, snippets)
        vm.toggleLinePick(solo.id)
        vm.onLineTap(solo.id, 1, seed)
        vm.onLineTap(solo.id, 2, seed)
        assertEquals(LinePick.AwaitingLast(solo.id, 1), vm.state.value.linePick)
        assertEquals(RangeProblem.TOO_FEW_LINES, vm.state.value.linePickProblem)
        assertNull(vm.state.value.session)
        // Tapping the first line again clears it.
        vm.onLineTap(solo.id, 1, seed)
        assertEquals(LinePick.AwaitingFirst(solo.id), vm.state.value.linePick)
        assertNull(vm.state.value.linePickProblem)
        assertTrue(repo.detects.isEmpty())
    }

    @Test
    fun `with several selected snippets the picked one is used and the rest are counted as ignored`() = runTest(main.dispatcher) {
        val other = catSnippet("b")
        val vm = entered(FakeCanvasPort(listOf(song, other), setOf(a, other.id)))
        assertEquals(1, vm.session.ignoredSnippets)
        assertEquals(a, vm.session.target.snippetId)
    }

    @Test
    fun `slot edits re-query after the debounce with ids kept and new u ids`() = runTest(main.dispatcher) {
        val vm = entered()
        vm.tapWord(1, 1)
        assertEquals(WordRef(1, 1), vm.session.picker)
        vm.edit(SlotEdit.NewSlot(1, 1))
        assertNull(vm.session.picker)
        advanceTimeBy(100)
        runCurrent()
        assertEquals(1, repo.suggests.size)
        advanceTimeBy(60)
        runCurrent()
        assertEquals(listOf("u0", "s0", "s1"), repo.suggests.last().slots.map { it.id })
        assertEquals(listOf("u0", "s0", "s1"), vm.session.shownColumns.map { it.id.value })
    }

    @Test
    fun `a refused edit leaves the board and says why without a request`() = runTest(main.dispatcher) {
        val vm = entered()
        val before = vm.session.board
        // s1 to "it" (line 1 word 2) would put it before s0's "steady" there.
        vm.edit(SlotEdit.AddWord(SlotId("s1"), 1, 2))
        advanceUntilIdle()
        assertEquals(before, vm.session.board)
        assertEquals("That would put B out of order with A on line 2.", vm.session.notice)
        assertEquals(1, repo.suggests.size)
    }

    @Test
    fun `toggling and dropping slots changes what is sent and prunes the tray`() = runTest(main.dispatcher) {
        val vm = entered()
        vm.togglePick(SlotId("s1"), PatternFx.candidate("dict:x"))
        vm.toggleSlot(SlotId("s2"))
        vm.dropSlot(SlotId("s1"))
        advanceUntilIdle()
        assertEquals(listOf("s0", "s2"), repo.suggests.last().slots.map { it.id })
        assertTrue(vm.session.tray.isEmpty)
    }

    @Test
    fun `More on a column pages that slot only`() = runTest(main.dispatcher) {
        val vm = entered()
        vm.loadMoreSlot(SlotId("s1"))
        advanceUntilIdle()
        val page = repo.suggests.last()
        assertEquals(mapOf("s1" to 30), page.offsets)
        assertFalse(page.lineMatches)
        val columns = vm.session.shownColumns
        assertEquals(listOf("dict:s0-0-0", "dict:s0-0-1"), columns[0].candidates.map { it.id })
        assertEquals(listOf("dict:s1-0-0", "dict:s1-0-1", "dict:s1-30-0", "dict:s1-30-1"), columns[1].candidates.map { it.id })
        assertEquals(30, columns[1].offset)
    }

    @Test
    fun `More under the line matches pages them`() = runTest(main.dispatcher) {
        val vm = entered()
        vm.loadMoreLines()
        advanceUntilIdle()
        assertEquals(30, repo.suggests.last().lineMatchOffset)
        assertEquals(listOf("note:n0:0:0", "note:n30:0:0"), vm.session.lineMatches.matches.map { it.unit.id })
    }

    @Test
    fun `dismiss hides a row without re-querying and is sent from then on`() = runTest(main.dispatcher) {
        val vm = entered()
        vm.dismiss("dict:s0-0-0")
        advanceUntilIdle()
        assertEquals(1, repo.suggests.size)
        assertEquals(listOf("dict:s0-0-1"), vm.session.shownColumns[0].candidates.map { it.id })
        vm.setMinScore(0.7)
        advanceUntilIdle()
        assertEquals(listOf("dict:s0-0-0"), repo.suggests.last().excludeIds)
    }

    @Test
    fun `picks fill the tray and Make snippet creates the line next to the anchor`() = runTest(main.dispatcher) {
        val port = FakeCanvasPort(listOf(song), setOf(a))
        val vm = entered(port)
        vm.togglePick(SlotId("s1"), PatternFx.candidate("dict:better", "better"))
        vm.togglePick(SlotId("s0"), PatternFx.candidate("dict:ready", "ready"))
        assertEquals("ready · better", vm.session.tray.display(vm.session.order))
        vm.makeSnippet()
        advanceUntilIdle()
        val body = snippets.created.single()
        assertEquals("ready better", body.rawText)
        assertNull(body.derivedFrom)
        // Right of the anchor (x 100 + width 300 + gap 40), same row.
        assertEquals(440.0 to 50.0, body.positionX to body.positionY)
        assertEquals(1, port.inserted.size)
        vm.makeSnippet()
        advanceUntilIdle()
        assertEquals(110.0, snippets.created.last().positionY)
    }

    @Test
    fun `a line match materializes with the pattern as its anchor and leaves the list`() = runTest(main.dispatcher) {
        val vm = entered()
        vm.materializeLine("note:n0:0:0")
        advanceUntilIdle()
        val body = snippets.created.single()
        assertEquals("ready for the weather", body.rawText)
        assertEquals(
            DerivedFrom(Provenance.NOTE, "note:n0:0:0", 0.8, 2, 2, "pattern:a:1-4", PatternFx.TEXTS.drop(1).joinToString(" / ")),
            body.derivedFrom,
        )
        assertTrue(vm.session.lineMatches.matches.isEmpty())
        assertTrue("note:n0:0:0" in vm.session.dismissed)
    }

    @Test
    fun `offline detect fails with needs connection and Try again retries`() = runTest(main.dispatcher) {
        repo.onDetect = { ApiResult.Failure(ApiError.NeedsConnection) }
        val vm = entered()
        assertEquals(Detection.Failed, vm.session.detection)
        assertEquals(PatternOperation.DETECT, vm.session.failure?.operation)
        assertEquals(PatternError.NeedsConnection, vm.session.failure?.error)
        assertTrue(repo.suggests.isEmpty())
        repo.onDetect = { ApiResult.Success(PatternFx.detect()) }
        vm.reset()
        advanceUntilIdle()
        assertTrue(vm.session.detection is Detection.Ready)
        assertNull(vm.session.failure)
        assertEquals(1, repo.suggests.size)
    }

    @Test
    fun `a typed suggest rejection is kept as the failure`() = runTest(main.dispatcher) {
        repo.onSuggest = { ApiResult.Failure(ApiError.Validation(422, "slots cross", "pattern_slot_order")) }
        val vm = entered()
        assertEquals(PatternError.Rejected(PatternErrorCode.SLOT_ORDER, "slots cross"), vm.session.failure?.error)
        assertFalse(vm.session.loading)
    }

    @Test
    fun `no slots detected shows an empty board and nothing is suggested`() = runTest(main.dispatcher) {
        repo.onDetect = { ApiResult.Success(PatternFx.detect(emptyList())) }
        val vm = entered()
        assertTrue(vm.session.board!!.slots.isEmpty())
        assertEquals(dev.nymph.lyrics.logic.rhyme.pattern.PatternSkip.NO_SLOTS_ON, vm.state.value.skip)
        assertTrue(repo.suggests.isEmpty())
        vm.edit(SlotEdit.NewSlot(1, 3))
        advanceUntilIdle()
        assertEquals(listOf("u0"), repo.suggests.single().slots.map { it.id })
    }

    @Test
    fun `the detection threshold re-detects after the debounce`() = runTest(main.dispatcher) {
        val vm = entered()
        vm.setDetectMinScore(0.6)
        vm.setDetectMinScore(0.55)
        advanceTimeBy(100)
        runCurrent()
        assertEquals(1, repo.detects.size)
        advanceUntilIdle()
        assertEquals(listOf(0.75, 0.55), repo.detects.map { it.minScore })
    }

    @Test
    fun `reset re-detects and clears edits and picks`() = runTest(main.dispatcher) {
        val vm = entered()
        vm.edit(SlotEdit.Toggle(SlotId("s2")))
        vm.togglePick(SlotId("s0"), PatternFx.candidate("dict:x"))
        vm.reset()
        advanceUntilIdle()
        assertEquals(2, repo.detects.size)
        assertTrue(vm.session.tray.isEmpty)
        assertFalse(vm.session.board!!.slot(SlotId("s2"))!!.on)
    }

    @Test
    fun `back to single ends the session and drops late answers`() = runTest(main.dispatcher) {
        val vm = entered()
        vm.edit(SlotEdit.Toggle(SlotId("s2")))
        vm.exit()
        advanceUntilIdle()
        assertNull(vm.state.value.session)
        assertEquals(1, repo.suggests.size)
    }

    @Test
    fun `Pattern last N lines uses the stepper and the last lines with words`() = runTest(main.dispatcher) {
        val vm = PatternViewModel(FakeCanvasPort(listOf(song), setOf(a)), repo, snippets)
        assertEquals(4, vm.lastLinesFor(song))
        vm.setLastLines(2)
        vm.enterLastLines(a, seed)
        advanceUntilIdle()
        assertEquals(2 to 4, repo.detects.single().lineStart to repo.detects.single().lineEnd)
        vm.setLastLines(99)
        assertEquals(16, vm.state.value.lastLines)
    }

    @Test
    fun `last N lines on a snippet without two lines of words says why`() = runTest(main.dispatcher) {
        val solo = dev.nymph.lyrics.app.fakes.snippet("solo", "x", listOf(PatternFx.gridLine("x"), PatternFx.gridLine("")))
        val vm = PatternViewModel(FakeCanvasPort(listOf(solo), setOf(solo.id)), repo, snippets)
        vm.enterLastLines(solo.id, seed)
        assertEquals(RangeProblem.TOO_FEW_LINES, vm.state.value.linePickProblem)
        assertNull(vm.state.value.session)
    }

    @Test
    fun `notes off sends no line matches`() = runTest(main.dispatcher) {
        val vm = entered()
        vm.toggleSource(SourceName.NOTES)
        advanceUntilIdle()
        val last = repo.suggests.last()
        assertFalse(last.lineMatches)
        assertEquals(listOf(SourceName.DICTIONARY), last.sources)
        assertTrue(vm.session.lineMatches.matches.isEmpty())
    }
}
