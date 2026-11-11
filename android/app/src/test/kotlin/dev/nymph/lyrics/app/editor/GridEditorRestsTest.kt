package dev.nymph.lyrics.app.editor

import dev.nymph.lyrics.logic.grid.localSyllabifyLine
import dev.nymph.lyrics.model.GridCell
import dev.nymph.lyrics.model.GridData
import dev.nymph.lyrics.model.GridLine
import dev.nymph.lyrics.model.Snippet
import dev.nymph.lyrics.model.error.ApiResult
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
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** Rest marks in the editor and the nudge-right rest (docs/features/android_rests_and_search.md). */
@OptIn(ExperimentalCoroutinesApi::class)
class GridEditorRestsTest {
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

    private fun EditorUiState.syllables(row: Int): List<String?> =
        requireNotNull(rowLines[row]).cells.map { if (it.isRest) "·" else it.syllable }

    private fun sel(row: Int, start: Int, end: Int) = requireNotNull(CellSelection.of(row, start, end))

    @Test
    fun `nudge right at the end of the line inserts a rest before the selection`() = runTest {
        val vm = settled(snippet("one two"))
        vm.tapCell(0, 1)
        vm.nudgeSelection(1)
        assertEquals(listOf("one", "·", "two"), vm.now.syllables(0))
        assertEquals(sel(0, 2, 3), vm.now.selection)
        assertEquals("one _ two", vm.now.rows.lines[0])
        assertEquals(setOf(0), vm.now.rows.arranged)
        // Again: another rest, the chunk keeps walking right.
        vm.nudgeSelection(1)
        assertEquals(listOf("one", "·", "·", "two"), vm.now.syllables(0))
        assertEquals(sel(0, 3, 4), vm.now.selection)
        assertEquals("one _ _ two", saveRequest(vm.now).rawText)
    }

    @Test
    fun `nudge right of a multi-cell chunk ending the line inserts the rest before its start`() = runTest {
        val vm = settled(snippet("one two three"))
        vm.tapCell(0, 1)
        vm.setExtendMode(true)
        vm.tapCell(0, 2)
        vm.nudgeSelection(1)
        assertEquals(listOf("one", "·", "two", "three"), vm.now.syllables(0))
        assertEquals(sel(0, 2, 4), vm.now.selection)
    }

    @Test
    fun `nudge right inside the line still swaps`() = runTest {
        val vm = settled(snippet("one two"))
        vm.tapCell(0, 0)
        vm.nudgeSelection(1)
        assertEquals(listOf("two", "one"), vm.now.syllables(0))
        assertEquals(sel(0, 1, 2), vm.now.selection)
    }

    @Test
    fun `nudge left at the start of the line is a no-op`() = runTest {
        val vm = settled(snippet("one two"))
        vm.tapCell(0, 0)
        val before = vm.now
        vm.nudgeSelection(-1)
        assertSame(before, vm.now)
        assertTrue(vm.now.rows.arranged.isEmpty())
    }

    @Test
    fun `nudging a whole line right inserts a rest at its start`() = runTest {
        val vm = settled(snippet("go"))
        vm.tapCell(0, 0)
        vm.nudgeSelection(1)
        assertEquals(listOf("·", "go"), vm.now.syllables(0))
        assertEquals("_ go", vm.now.rows.lines[0])
    }

    @Test
    fun `typed rest marks become rest cells and are saved as typed`() = runTest {
        // The server syllabifies rest marks into rest cells (the contract); the local estimate does too.
        nlp.respond = { text -> ApiResult.Success(GridData(text.split("\n").filter { it.isNotBlank() }.map(::localSyllabifyLine))) }
        val vm = settled(snippet(""))
        vm.typeLine(0, "I _ go")
        assertEquals(listOf("I", "·", "go"), vm.now.syllables(0))
        advanceUntilIdle()
        assertEquals(listOf("I _ go"), nlp.completed)
        assertEquals(listOf("I", "·", "go"), vm.now.syllables(0))
        assertEquals("I _ go", saveRequest(vm.now).rawText)
    }

    @Test
    fun `a legacy snippet opens arranged with its rests and saves them as marks`() = runTest {
        val grid = GridData(
            listOf(
                GridLine(
                    listOf(GridCell(syllable = "I", wordIndex = 0), GridCell.REST, GridCell(syllable = "go", wordIndex = 1)),
                    2,
                    "I go",
                ),
            ),
        )
        val vm = settled(snippet("I go", grid))
        assertEquals(setOf(0), vm.now.rows.arranged)
        assertEquals(listOf("I", "·", "go"), vm.now.syllables(0))
        assertEquals("I _ go", saveRequest(vm.now).rawText)
    }
}
