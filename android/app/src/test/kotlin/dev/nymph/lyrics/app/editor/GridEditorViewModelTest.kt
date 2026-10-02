package dev.nymph.lyrics.app.editor

import app.cash.turbine.test
import dev.nymph.lyrics.logic.grid.localSyllabifyLine
import dev.nymph.lyrics.model.GridCell
import dev.nymph.lyrics.model.GridData
import dev.nymph.lyrics.model.GridLine
import dev.nymph.lyrics.model.Snippet
import dev.nymph.lyrics.model.SnippetId
import dev.nymph.lyrics.model.SnippetType
import dev.nymph.lyrics.model.SnippetUpdateRequest
import dev.nymph.lyrics.model.error.ApiError
import dev.nymph.lyrics.model.error.ApiResult
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GridEditorViewModelTest {
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

    /** A ViewModel whose syllabify collector is already running. */
    private fun TestScope.editor(s: Snippet): GridEditorViewModel = GridEditorViewModel(s, nlp, repo).also { runCurrent() }

    /** A ViewModel after the first syllabify has settled. */
    private fun TestScope.settled(s: Snippet): GridEditorViewModel = editor(s).also { advanceUntilIdle() }

    private val GridEditorViewModel.now: EditorUiState get() = state.value

    private fun EditorUiState.syllables(row: Int): List<String?> =
        requireNotNull(rowLines[row]).cells.map { if (it.isRest) "·" else it.syllable }

    private fun sel(row: Int, start: Int, end: Int) = requireNotNull(CellSelection.of(row, start, end))

    private val savedGrid = GridData(
        listOf(
            GridLine(
                cells = listOf(
                    GridCell(syllable = "hel", stress = 1, wordIndex = 0),
                    GridCell(syllable = "lo", stress = 0, wordIndex = 0),
                    GridCell.REST,
                    GridCell(syllable = "world", stress = 1, wordIndex = 1),
                ),
                syllableCount = 3,
                rawText = "hello world",
            ),
        ),
    )

    @Nested
    inner class Init {
        @Test
        fun `keeps a matching saved grid, arranged, and does not syllabify it`() = runTest {
            val vm = editor(snippet("hello world", savedGrid))
            val s = vm.now
            assertEquals(listOf("hel", "lo", "·", "world"), s.syllables(0))
            assertEquals("hello", s.rowLines[0]!!.cells[0].word) // attachWords on open
            assertEquals(setOf(0), s.rows.arranged)
            assertEquals(-1, s.rows.activeRow)
            assertEquals(SnippetType.VERSE, s.snippetType)
            assertEquals(SaveStatus.Idle, s.save)
            advanceTimeBy(5_000)
            runCurrent()
            assertTrue(nlp.calls.isEmpty())
        }

        @Test
        fun `a stale saved line gets a local estimate and a syllabify that leaves the saved row alone`() = runTest {
            val vm = editor(snippet("hello world\nnew words", savedGrid))
            assertEquals(localSyllabifyLine("new words"), vm.now.rowLines[1])
            assertEquals(setOf(0), vm.now.rows.arranged)
            advanceUntilIdle()
            assertEquals(listOf("hello world\nnew words"), nlp.calls)
            assertEquals(listOf("hel", "lo", "·", "world"), vm.now.syllables(0))
            assertEquals(listOf("new", "words"), vm.now.syllables(1))
            assertEquals(1, vm.now.rowLines[1]!!.cells[0].stress)
        }

        @Test
        fun `an empty snippet starts with one active blank row and no syllabify`() = runTest {
            val vm = settled(snippet(""))
            assertEquals(listOf(""), vm.now.rows.lines)
            assertEquals(0, vm.now.rows.activeRow)
            assertNull(vm.now.rows.gridData)
            assertTrue(nlp.calls.isEmpty())
        }

        @Test
        fun `keeps the snippet type`() = runTest {
            assertEquals(SnippetType.HOOK, editor(snippet("x", type = SnippetType.HOOK)).now.snippetType)
        }
    }

    @Nested
    inner class Typing {
        @Test
        fun `typing shows the local estimate immediately`() = runTest {
            val vm = editor(snippet(""))
            vm.typeLine(0, "hello there")
            assertEquals(localSyllabifyLine("hello there"), vm.now.rowLines[0])
            assertEquals("hello there", vm.now.rows.lines[0])
            assertTrue(nlp.calls.isEmpty())
        }

        @Test
        fun `syllabify waits 300 ms after the last change`() = runTest {
            val vm = editor(snippet(""))
            vm.typeLine(0, "hello there")
            advanceTimeBy(SYLLABIFY_DEBOUNCE_MS - 1)
            runCurrent()
            assertTrue(nlp.calls.isEmpty())
            advanceTimeBy(2)
            runCurrent()
            assertEquals(listOf("hello there"), nlp.calls)
            assertEquals(listOf("hello", "there"), vm.now.syllables(0))
            assertEquals(1, vm.now.rowLines[0]!!.cells[1].stress)
        }

        @Test
        fun `a burst of keystrokes makes one request with the final text`() = runTest {
            val vm = editor(snippet(""))
            vm.typeLine(0, "a")
            advanceTimeBy(100)
            vm.typeLine(0, "ab")
            advanceTimeBy(100)
            vm.typeLine(0, "abc")
            advanceUntilIdle()
            assertEquals(listOf("abc"), nlp.calls)
        }

        @Test
        fun `a newer text cancels the in-flight request and only the latest result lands`() = runTest {
            nlp.latencyMs = 1_000
            val vm = editor(snippet(""))
            vm.typeLine(0, "one")
            advanceTimeBy(SYLLABIFY_DEBOUNCE_MS + 100) // request for "one" in flight
            runCurrent()
            vm.typeLine(0, "two")
            advanceUntilIdle()
            assertEquals(listOf("one", "two"), nlp.calls)
            assertEquals(listOf("one"), nlp.cancelled)
            assertEquals(listOf("two"), nlp.completed)
            assertEquals(serverLine("two").cells.map { it.syllable }, vm.now.syllables(0))
            assertEquals(1, vm.now.rowLines[0]!!.cells[0].stress)
        }

        @Test
        fun `syllabify failure keeps local estimates and reports the error until a success`() = runTest {
            val failure = ApiError.Network(IOException("offline"))
            nlp.respond = { ApiResult.Failure(failure) }
            val vm = editor(snippet(""))
            vm.typeLine(0, "hello world")
            advanceUntilIdle()
            assertEquals(localSyllabifyLine("hello world"), vm.now.rowLines[0])
            assertEquals(failure, vm.now.syllabifyError)

            nlp.respond = { ApiResult.Success(serverGrid(it)) }
            vm.typeLine(0, "hello worlds")
            advanceUntilIdle()
            assertNull(vm.now.syllabifyError)
            assertEquals(listOf("hello", "worlds"), vm.now.syllables(0))
        }

        @Test
        fun `typing into the selected row clears the selection, another row keeps it`() = runTest {
            val vm = settled(snippet("one two\nthree four"))
            vm.tapCell(0, 1)
            vm.typeLine(1, "three four five")
            assertEquals(sel(0, 1, 2), vm.now.selection)
            vm.typeLine(0, "one two six")
            assertNull(vm.now.selection)
        }

        @Test
        fun `a syllabify result that shrinks the selected row drops the selection`() = runTest {
            val vm = editor(snippet("hello there"))
            assertEquals(listOf("hel", "lo", "there"), vm.now.syllables(0)) // local estimate
            vm.tapCell(0, 2)
            assertEquals(sel(0, 2, 3), vm.now.selection)
            advanceUntilIdle()
            assertEquals(listOf("hello", "there"), vm.now.syllables(0))
            assertNull(vm.now.selection)
        }
    }

    @Nested
    inner class Rows {
        @Test
        fun `insert and delete rows move the active row`() = runTest {
            val vm = settled(snippet("a\nb"))
            vm.insertRowAfter(0)
            assertEquals(listOf("a", "", "b"), vm.now.rows.lines)
            assertEquals(1, vm.now.rows.activeRow)
            vm.deleteRow(1)
            assertEquals(listOf("a", "b"), vm.now.rows.lines)
            assertEquals(0, vm.now.rows.activeRow)
        }

        @Test
        fun `activate clears the selection and deactivate closes the field`() = runTest {
            val vm = settled(snippet("a b\nc"))
            vm.tapCell(0, 0)
            vm.activateRow(1)
            assertNull(vm.now.selection)
            assertEquals(1, vm.now.rows.activeRow)
            vm.deactivateRow()
            assertEquals(-1, vm.now.rows.activeRow)
        }

        @Test
        fun `reorder carries the selection, arranged flag and active row with their lines`() = runTest {
            val vm = settled(snippet("a\nb\nc"))
            vm.activateRow(2)
            vm.tapCell(0, 0)
            vm.insertRestBeforeSelection() // row 0 ("a") arranged, selection (0, 1, 2)
            assertEquals(setOf(0), vm.now.rows.arranged)

            vm.reorderRow(0, 2)
            assertEquals(listOf("b", "c", "a"), vm.now.rows.lines)
            assertEquals(sel(2, 1, 2), vm.now.selection)
            assertEquals(setOf(2), vm.now.rows.arranged)
            assertEquals("c", vm.now.rows.lines[vm.now.rows.activeRow])
            assertEquals(listOf("·", "a"), vm.now.syllables(2))

            vm.moveRow(2, -1)
            assertEquals(listOf("b", "a", "c"), vm.now.rows.lines)
            assertEquals(sel(1, 1, 2), vm.now.selection)
            assertEquals(setOf(1), vm.now.rows.arranged)
            assertEquals("c", vm.now.rows.lines[vm.now.rows.activeRow])
        }

        @Test
        fun `moving a row past either end is a no-op`() = runTest {
            val vm = settled(snippet("a\nb"))
            val before = vm.now
            vm.moveRow(0, -1)
            vm.moveRow(1, 1)
            assertSame(before, vm.now)
        }
    }

    @Nested
    inner class Selection {
        @Test
        fun `tap selects one cell, extend mode grows it, handles resize it`() = runTest {
            val vm = settled(snippet("one two three four"))
            vm.tapCell(0, 1)
            assertEquals(sel(0, 1, 2), vm.now.selection)
            vm.setExtendMode(true)
            vm.tapCell(0, 3)
            assertEquals(sel(0, 1, 4), vm.now.selection)
            vm.resizeSelection(SelectionEdge.START, 2)
            assertEquals(sel(0, 2, 4), vm.now.selection)
            vm.resizeSelection(SelectionEdge.END, 9)
            assertEquals(sel(0, 2, 4), vm.now.selection) // clamped to the last cell
            vm.setExtendMode(false)
            vm.tapCell(0, 0)
            assertEquals(sel(0, 0, 1), vm.now.selection)
            vm.clearSelection()
            assertNull(vm.now.selection)
        }

        @Test
        fun `taps outside a row's cells or on a blank row are ignored`() = runTest {
            val vm = settled(snippet("one\n\ntwo"))
            vm.tapCell(0, 5)
            vm.tapCell(1, 0)
            assertNull(vm.now.selection)
        }

        @Test
        fun `long-press on an unselected cell selects it, on a selected one keeps the chunk`() = runTest {
            val vm = settled(snippet("one two three four"))
            vm.setExtendMode(true)
            vm.tapCell(0, 1)
            vm.tapCell(0, 2)
            vm.beginChunkDrag(0, 2)
            assertEquals(sel(0, 1, 3), vm.now.selection)
            vm.beginChunkDrag(0, 3)
            assertEquals(sel(0, 3, 4), vm.now.selection)
        }
    }

    @Nested
    inner class Chunks {
        @Test
        fun `dropping a chunk rebuilds the text, marks the row arranged and moves the selection`() = runTest {
            val vm = settled(snippet("one two three"))
            vm.tapCell(0, 0)
            vm.dropSelectionAt(3)
            val s = vm.now
            assertEquals(listOf("two", "three", "one"), s.syllables(0))
            assertEquals("two three one", s.rows.lines[0])
            assertEquals("two three one", s.rowLines[0]!!.rawText)
            assertEquals(listOf(0, 1, 2), s.rowLines[0]!!.cells.map { it.wordIndex })
            assertEquals(setOf(0), s.rows.arranged)
            assertEquals(sel(0, 2, 3), s.selection)
        }

        @Test
        fun `dropping inside or at the edges of the chunk changes nothing`() = runTest {
            val vm = settled(snippet("one two three"))
            vm.setExtendMode(true)
            vm.tapCell(0, 0)
            vm.tapCell(0, 1)
            val before = vm.now
            vm.dropSelectionAt(0)
            vm.dropSelectionAt(1)
            vm.dropSelectionAt(2)
            assertSame(before, vm.now)
        }

        @Test
        fun `nudge moves the chunk one cell and stops at the row edges`() = runTest {
            val vm = settled(snippet("one two three"))
            vm.tapCell(0, 1)
            vm.nudgeSelection(-1)
            assertEquals(listOf("two", "one", "three"), vm.now.syllables(0))
            assertEquals(sel(0, 0, 1), vm.now.selection)
            val before = vm.now
            vm.nudgeSelection(-1)
            assertSame(before, vm.now)
            vm.tapCell(0, 2)
            val atEnd = vm.now
            vm.nudgeSelection(1)
            assertSame(atEnd, vm.now)
        }

        @Test
        fun `a nudge that cannot move does not mark the row arranged`() = runTest {
            val vm = settled(snippet("one two"))
            vm.tapCell(0, 0)
            vm.nudgeSelection(-1)
            assertTrue(vm.now.rows.arranged.isEmpty())
        }

        @Test
        fun `insert rest before the selection shifts it right and keeps the text`() = runTest {
            val vm = settled(snippet("one two three"))
            vm.tapCell(0, 1)
            vm.insertRestBeforeSelection()
            val s = vm.now
            assertEquals(listOf("one", "·", "two", "three"), s.syllables(0))
            assertEquals(sel(0, 2, 3), s.selection)
            assertEquals("one two three", s.rows.lines[0])
            assertEquals(3, s.rowLines[0]!!.syllableCount)
            assertEquals(setOf(0), s.rows.arranged)
        }

        @Test
        fun `remove deletes selected rests only`() = runTest {
            val vm = settled(snippet("one two"))
            vm.tapCell(0, 1)
            vm.insertRestBeforeSelection()
            vm.insertRestBeforeSelection() // one · · two, selection on "two"
            val withRests = vm.now
            vm.removeSelectedRests()
            assertSame(withRests, vm.now) // a syllable is selected

            vm.clearSelection()
            vm.setExtendMode(true)
            vm.tapCell(0, 1)
            vm.tapCell(0, 2)
            assertTrue(selectionIsAllRests(vm.now))
            vm.removeSelectedRests()
            assertEquals(listOf("one", "two"), vm.now.syllables(0))
            assertNull(vm.now.selection)

            vm.tapCell(0, 0)
            vm.tapCell(0, 1)
            assertTrue(!selectionIsAllRests(vm.now))
        }

        @Test
        fun `arranged rows survive a later syllabify of other rows`() = runTest {
            val vm = settled(snippet("alpha beta\ngamma delta"))
            vm.tapCell(0, 0)
            vm.insertRestBeforeSelection() // row 0: · alpha beta, text unchanged
            val arrangedLine = vm.now.rowLines[0]
            vm.typeLine(1, "gamma delta epsilon")
            advanceUntilIdle()
            assertEquals("alpha beta\ngamma delta epsilon", nlp.calls.last())
            assertEquals(arrangedLine, vm.now.rowLines[0])
            assertEquals(listOf("gamma", "delta", "epsilon"), vm.now.syllables(1))
            assertEquals(1, vm.now.rowLines[1]!!.cells[2].stress)
        }

        @Test
        fun `typing into an arranged row discards its arrangement`() = runTest {
            val vm = settled(snippet("alpha beta"))
            vm.tapCell(0, 0)
            vm.insertRestBeforeSelection()
            vm.typeLine(0, "alpha beta gamma")
            assertTrue(vm.now.rows.arranged.isEmpty())
            advanceUntilIdle()
            assertEquals(listOf("alpha", "beta", "gamma"), vm.now.syllables(0))
        }
    }

    @Nested
    inner class Save {
        @Test
        fun `save sends raw_text from the grid, grid_data and snippet_type, then reports Saved`() = runTest {
            val vm = settled(snippet("one two three\nfour five"))
            vm.tapCell(0, 0)
            vm.dropSelectionAt(3) // row 0 arranged: "two three one"
            vm.typeLine(1, "four  five ") // unarranged rows are saved as typed
            vm.setSnippetType(SnippetType.CHORUS)
            advanceUntilIdle() // let the debounced syllabify land first
            val expectedGrid = vm.now.rows.gridData

            vm.state.test {
                assertEquals(SaveStatus.Idle, awaitItem().save)
                vm.save()
                assertEquals(SaveStatus.Saving, awaitItem().save)
                val saved = assertInstanceOf(SaveStatus.Saved::class.java, awaitItem().save)
                assertEquals("two three one\nfour  five ", saved.snippet.rawText)
                cancelAndIgnoreRemainingEvents()
            }
            val (id, body) = repo.updates.single()
            assertEquals(SnippetId("s1"), id)
            assertEquals(
                SnippetUpdateRequest(snippetType = SnippetType.CHORUS, gridData = expectedGrid, rawText = "two three one\nfour  five "),
                body,
            )
            assertEquals(saveRequest(vm.now), body)
        }

        @Test
        fun `a second save while saving or after saving is ignored`() = runTest {
            repo.latencyMs = 500
            val vm = settled(snippet("one"))
            vm.save()
            vm.save()
            advanceUntilIdle()
            vm.save()
            advanceUntilIdle()
            assertEquals(1, repo.updates.size)
            assertInstanceOf(SaveStatus.Saved::class.java, vm.now.save)
        }

        @Test
        fun `save failure keeps the edits, reports the error and allows a retry`() = runTest {
            val error = ApiError.Server(500, "boom")
            repo.respond = { _, _ -> ApiResult.Failure(error) }
            val vm = settled(snippet("one two"))
            vm.tapCell(0, 0)
            vm.nudgeSelection(1)
            val rows = vm.now.rows
            vm.save()
            advanceUntilIdle()
            assertEquals(SaveStatus.Failed(error), vm.now.save)
            assertSame(rows, vm.now.rows)

            vm.acknowledgeSaveError()
            assertEquals(SaveStatus.Idle, vm.now.save)
            repo.respond = { _, body -> ApiResult.Success(snippet(body.rawText!!, body.gridData)) }
            vm.save()
            advanceUntilIdle()
            val saved = assertInstanceOf(SaveStatus.Saved::class.java, vm.now.save)
            assertEquals("two one", saved.snippet.rawText)
            assertEquals(2, repo.updates.size)
        }

        @Test
        fun `saving an all-blank editor omits grid_data`() = runTest {
            val vm = settled(snippet(""))
            assertEquals(SnippetUpdateRequest(snippetType = SnippetType.VERSE, gridData = null, rawText = ""), saveRequest(vm.now))
        }
    }
}
