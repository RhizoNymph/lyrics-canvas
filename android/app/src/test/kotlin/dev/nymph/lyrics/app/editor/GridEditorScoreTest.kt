package dev.nymph.lyrics.app.editor

import dev.nymph.lyrics.app.editor.score.DraftProblem
import dev.nymph.lyrics.logic.score.edit.SyllableRef
import dev.nymph.lyrics.logic.score.validateScore
import dev.nymph.lyrics.model.Snippet
import dev.nymph.lyrics.model.score.Score
import dev.nymph.lyrics.model.score.ScoreChange
import dev.nymph.lyrics.model.score.ScoreLine
import dev.nymph.lyrics.model.score.ScoreMeter
import dev.nymph.lyrics.model.score.ScoreNote
import dev.nymph.lyrics.model.score.ScoreSnap
import dev.nymph.lyrics.model.score.ScoreTempo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** The editor's score layer: Grid / Staff, Make score, staff edits linked to the grid's rests, save. */
@OptIn(ExperimentalCoroutinesApi::class)
class GridEditorScoreTest {
    private val nlp = FakeNlpRepository()
    private val repo = FakeSnippetRepository()

    @BeforeEach
    fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.settled(s: Snippet): GridEditorViewModel = GridEditorViewModel(s, nlp, repo).also {
        runCurrent()
        advanceUntilIdle()
    }

    private val GridEditorViewModel.now: EditorUiState get() = state.value

    private fun score(vararg lines: List<ScoreNote>) = Score(
        meters = listOf(ScoreMeter(0, 4, 4)),
        tempos = listOf(ScoreTempo(0, 90.0)),
        snap = ScoreSnap.EIGHTH,
        lines = lines.map { ScoreLine(it) },
    )

    private fun n(s: Int, t: Int, d: Int = 24) = ScoreNote(s, t, d, 0)

    /** "one two" (server grid, one cell per word) with notes at 0 and 24 in the staff view. */
    private fun scored(view: Boolean = true) =
        snippet("one two", serverGrid("one two")).copy(score = score(listOf(n(0, 0), n(1, 24))), scoreView = view)

    private fun EditorUiState.syllables(row: Int): List<String?> =
        requireNotNull(rowLines[row]).cells.map { if (it.isRest) "·" else it.syllable }

    @Test
    fun `a snippet with a score opens in its saved view`() = runTest {
        val vm = settled(scored())
        assertTrue(vm.now.staffShown)
        assertEquals(listOf(n(0, 0), n(1, 24)), vm.now.staffScore!!.lines.single().notes)
        vm.showGrid()
        assertFalse(vm.now.staffShown)
        vm.showStaff()
        assertTrue(vm.now.staffShown)
    }

    @Test
    fun `Make score drafts from the grid offline and shows the staff`() = runTest {
        val vm = settled(snippet("one _ two", serverGrid("one two").let { g ->
            g.copy(lines = listOf(g.lines[0].copy(cells = listOf(g.lines[0].cells[0], dev.nymph.lyrics.model.GridCell.REST, g.lines[0].cells[1]), rawText = "one _ two")))
        }))
        vm.draftFromGrid(4, 4, 100.0, ScoreSnap.EIGHTH)
        assertTrue(vm.now.staffShown)
        val notes = vm.now.staffScore!!.lines.single().notes
        assertEquals(listOf(0 to 24, 72 to 24), notes.map { it.t to it.d })
        assertEquals(listOf("one", "·", "two"), vm.now.syllables(0), "the draft round-trips through the rest link")
        assertEquals(100.0, vm.now.staffScore!!.tempos.single().bpm)
    }

    @Test
    fun `Make score without lyrics says so`() = runTest {
        val vm = settled(snippet(""))
        vm.draftFromGrid(4, 4, 90.0, ScoreSnap.EIGHTH)
        assertEquals(DraftProblem.NO_LYRICS, vm.now.draftProblem)
        assertNull(vm.now.staffScore)
        vm.acknowledgeDraftProblem()
        assertNull(vm.now.draftProblem)
    }

    @Test
    fun `a rest added in the grid pushes the later notes right`() = runTest {
        val vm = settled(scored(view = false))
        vm.tapCell(0, 1)
        vm.insertRestBeforeSelection()
        assertEquals(listOf("one", "·", "two"), vm.now.syllables(0))
        assertEquals(listOf(n(0, 0), n(1, 72)), vm.now.staffScore!!.lines.single().notes)
    }

    @Test
    fun `moving a note on the staff writes the gap into the grid as rests`() = runTest {
        val vm = settled(scored())
        vm.moveNote(SyllableRef(0, 1), 120)
        assertEquals(listOf(n(0, 0), n(1, 120)), vm.now.staffScore!!.lines.single().notes)
        assertEquals(listOf("one", "·", "·", "two"), vm.now.syllables(0))
        assertEquals("one _ _ two", saveRequest(vm.now).rawText)
        assertEquals(SyllableRef(0, 1), vm.now.score.selected)
    }

    @Test
    fun `tray syllables are placed and selected notes edited`() = runTest {
        val vm = settled(snippet("one two", serverGrid("one two")).copy(score = score(listOf(n(0, 0))), scoreView = true))
        vm.placeSyllable(SyllableRef(0, 1), 48)
        assertEquals(listOf(n(0, 0), ScoreNote(1, 48, 24, 1)), vm.now.staffScore!!.lines.single().notes)
        vm.cycleAccent()
        assertEquals(2, vm.now.staffScore!!.lines.single().notes[1].a)
        vm.stepLength(1)
        assertEquals(48, vm.now.staffScore!!.lines.single().notes[1].d)
        vm.nudgeNote(1)
        assertEquals(72, vm.now.staffScore!!.lines.single().notes[1].t)
        vm.unplaceSelected()
        assertEquals(listOf(n(0, 0)), vm.now.staffScore!!.lines.single().notes)
    }

    @Test
    fun `meter, tempo, pickup and snap edits keep the score valid`() = runTest {
        val vm = settled(scored())
        vm.setMeter(1, 3, 4)
        vm.setTempo(1, 120.0)
        vm.setPickup(48)
        vm.setSnap(ScoreSnap.SIXTEENTH_TRIPLET)
        val s = vm.now.staffScore!!
        assertEquals(listOf(ScoreMeter(0, 4, 4), ScoreMeter(1, 3, 4)), s.meters)
        assertEquals(listOf(ScoreTempo(0, 90.0), ScoreTempo(1, 120.0)), s.tempos)
        assertEquals(48, s.pickup)
        assertEquals(ScoreSnap.SIXTEENTH_TRIPLET, s.snap)
        assertNull(validateScore(s, vm.now.rows.gridData))
        vm.removeMeter(1)
        vm.removeTempo(1)
        assertEquals(1, vm.now.staffScore!!.meters.size)
        assertEquals(1, vm.now.staffScore!!.tempos.size)
    }

    @Test
    fun `typing reconciles the notes by word`() = runTest {
        val vm = settled(scored())
        vm.typeLine(0, "one big two")
        val notes = vm.now.staffScore!!.lines.single().notes
        assertEquals(listOf(0, 2), notes.map { it.s })
    }

    @Test
    fun `save sends the score and the view, Remove score sends an explicit removal`() = runTest {
        val vm = settled(scored())
        val body = saveRequest(vm.now)
        assertEquals(ScoreChange.set(score(listOf(n(0, 0), n(1, 24)))), body.score)
        assertEquals(true, body.scoreView)
        vm.removeScore()
        assertFalse(vm.now.staffShown)
        assertEquals(ScoreChange.REMOVE, saveRequest(vm.now).score)
        assertNull(saveRequest(vm.now).scoreView)
    }

    @Test
    fun `a draft from the sheet is used only when it fits the grid`() = runTest {
        val vm = settled(snippet("one two", serverGrid("one two")))
        vm.useDraft(score(listOf(n(0, 0)), listOf(n(0, 192))))
        assertNull(vm.now.staffScore, "two lines for a one-line grid")
        vm.useDraft(score(listOf(n(0, 0), n(1, 168))))
        assertTrue(vm.now.staffShown)
        assertEquals(listOf("one", "·", "·", "·", "two"), vm.now.syllables(0))
    }

    @Test
    fun `a snippet without a score saves no score fields`() = runTest {
        val vm = settled(snippet("one two", serverGrid("one two")))
        assertNull(saveRequest(vm.now).score)
        assertNull(saveRequest(vm.now).scoreView)
    }
}
