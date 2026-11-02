package dev.nymph.lyrics.app.editor

import dev.nymph.lyrics.logic.grid.localSyllabifyLine
import dev.nymph.lyrics.model.GridCell
import dev.nymph.lyrics.model.GridData
import dev.nymph.lyrics.model.GridLine
import dev.nymph.lyrics.model.Snippet
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
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GridEditorSplitJoinTest {
    private val nlp = FakeNlpRepository()
    private val repo = FakeSnippetRepository()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun TestScope.editor(s: Snippet): GridEditorViewModel = GridEditorViewModel(s, nlp, repo).also { runCurrent() }

    private val GridEditorViewModel.now: EditorUiState get() = state.value

    private fun caret(s: EditorUiState) = s.rows.caret?.let { it.row to it.offset }

    private val arrangedHello = GridData(
        listOf(
            GridLine(
                cells = listOf(GridCell(syllable = "hel", wordIndex = 0), GridCell.REST, GridCell(syllable = "lo", wordIndex = 0)),
                syllableCount = 2,
                rawText = "hello",
            ),
        ),
    )

    @Test
    fun `splitRow splits at the caret, focuses the new row at 0 and clears the selection`() = runTest {
        val vm = editor(snippet("hello world"))
        vm.activateRow(0)
        vm.tapCell(0, 0)
        vm.splitRow(0, "hello world", 5)
        assertEquals(listOf("hello", "world"), vm.now.rows.lines)
        assertEquals(1, vm.now.rows.activeRow)
        assertEquals(1 to 0, caret(vm.now))
        assertNull(vm.now.selection)
        assertEquals(localSyllabifyLine("world"), vm.now.rowLines[1])
    }

    @Test
    fun `splitRow applies a text that differs from the row first (a removed selection or newline)`() = runTest {
        val vm = editor(snippet("hello world"))
        vm.splitRow(0, "hellorld", 5)
        assertEquals(listOf("hello", "rld"), vm.now.rows.lines)
    }

    @Test
    fun `splitRow at the end inserts a blank row below and keeps an arranged row arranged`() = runTest {
        val vm = editor(snippet("hello", arrangedHello))
        vm.splitRow(0, "hello", 5)
        assertEquals(listOf("hello", ""), vm.now.rows.lines)
        assertEquals(setOf(0), vm.now.rows.arranged)
        assertEquals(1, vm.now.rows.activeRow)
    }

    @Test
    fun `split rows are syllabified like typed text`() = runTest {
        val vm = editor(snippet("hello", arrangedHello))
        advanceUntilIdle()
        assertTrue(nlp.calls.isEmpty())
        vm.splitRow(0, "hello", 3)
        advanceUntilIdle()
        assertEquals(listOf("hel\nlo"), nlp.calls)
    }

    @Test
    fun `backspaceAtStart joins a non-first row onto the previous one`() = runTest {
        val vm = editor(snippet("hello\nworld"))
        vm.activateRow(1)
        vm.backspaceAtStart(1)
        assertEquals(listOf("hello world"), vm.now.rows.lines)
        assertEquals(0, vm.now.rows.activeRow)
        assertEquals(0 to 6, caret(vm.now))
    }

    @Test
    fun `backspaceAtStart on an empty first row deletes it when other rows exist`() = runTest {
        val vm = editor(snippet("\nworld"))
        vm.activateRow(0)
        vm.backspaceAtStart(0)
        assertEquals(listOf("world"), vm.now.rows.lines)
        assertEquals(0, vm.now.rows.activeRow)
        assertEquals(0 to 5, caret(vm.now))
    }

    @Test
    fun `backspaceAtStart on a non-empty first row or the only row changes nothing`() = runTest {
        val vm = editor(snippet("hello\nworld"))
        vm.activateRow(0)
        val before = vm.now
        vm.backspaceAtStart(0)
        assertSame(before, vm.now)

        val single = editor(snippet(""))
        val s0 = single.now
        single.backspaceAtStart(0)
        assertSame(s0, single.now)
    }

    @Test
    fun `a join clears the cell selection, as on the web`() = runTest {
        val vm = editor(snippet("a\nb\nhello"))
        vm.tapCell(2, 0)
        vm.backspaceAtStart(1)
        assertEquals(listOf("a b", "hello"), vm.now.rows.lines)
        assertNull(vm.now.selection)
    }
}
