package dev.nymph.lyrics.app.canvas

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import dev.nymph.lyrics.app.MainDispatcherExtension
import dev.nymph.lyrics.app.canvas.search.CANVAS_SEARCH_DEBOUNCE_MS
import dev.nymph.lyrics.app.canvas.search.CanvasSearchMemory
import dev.nymph.lyrics.app.canvas.search.FOCUS_SNIPPET_MISSING
import dev.nymph.lyrics.app.canvas.search.HIDDEN_BY_TAG_FILTER
import dev.nymph.lyrics.app.canvas.search.SearchCount
import dev.nymph.lyrics.app.fakes.CANVAS_ID
import dev.nymph.lyrics.app.fakes.FakeCanvasRepository
import dev.nymph.lyrics.app.fakes.FakeSnippetRepository
import dev.nymph.lyrics.app.fakes.canvasDetail
import dev.nymph.lyrics.app.fakes.snippet
import dev.nymph.lyrics.app.fakes.tag
import dev.nymph.lyrics.logic.canvas.Point
import dev.nymph.lyrics.logic.canvas.ViewSize
import dev.nymph.lyrics.logic.canvas.viewportCenter
import dev.nymph.lyrics.logic.search.SearchQuery
import dev.nymph.lyrics.logic.tags.TagExprParse
import dev.nymph.lyrics.logic.tags.TagFilter
import dev.nymph.lyrics.logic.tags.parseTagExpression
import dev.nymph.lyrics.model.CanvasId
import dev.nymph.lyrics.model.SnippetId
import dev.nymph.lyrics.model.error.ApiResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

/** The in-canvas search filter and opening a canvas focused on a snippet. */
@OptIn(ExperimentalCoroutinesApi::class)
class CanvasViewModelSearchTest {
    @JvmField
    @RegisterExtension
    val main = MainDispatcherExtension()

    private val love = tag("love")
    private val a = snippet("a", rawText = "black cat\nat_night", tags = listOf(love), contentUpdatedAt = "2026-09-02")
    private val b = snippet("b", rawText = "white cat", x = 400.0, contentUpdatedAt = "2026-09-01")
    private val c = snippet("c", rawText = "a dog", x = 800.0, y = 600.0, contentUpdatedAt = "2026-09-03")

    private fun filter(text: String): TagFilter = (parseTagExpression(text) as TagExprParse.Valid).filter

    private fun TestScope.vm(
        memory: CanvasSearchMemory = CanvasSearchMemory(),
        savedState: SavedStateHandle = SavedStateHandle(),
        load: Boolean = true,
    ): CanvasViewModel {
        val vm = CanvasViewModel(
            CANVAS_ID,
            FakeCanvasRepository(ApiResult.Success(canvasDetail(listOf(a, b, c)))),
            FakeSnippetRepository(),
            backgroundScope,
            savedState = savedState,
            searchMemory = memory,
        )
        if (load) runCurrent()
        return vm
    }

    private fun CanvasViewModel.ready(): CanvasUiState.Ready = state.value as CanvasUiState.Ready

    private fun CanvasViewModel.visible(): List<String> = ready().visibleSnippets.map { it.id.value }

    private fun TestScope.settle() = advanceTimeBy(CANVAS_SEARCH_DEBOUNCE_MS + 1)

    @Nested
    inner class Filter {
        @Test
        fun `typing filters after the debounce, on display text`() = runTest(main.dispatcher) {
            val vm = vm()
            vm.editSearch("cat")
            assertEquals("cat", vm.searchInput.value)
            advanceTimeBy(CANVAS_SEARCH_DEBOUNCE_MS - 10)
            assertEquals(listOf("a", "b", "c"), vm.visible())
            settle()
            assertEquals(listOf("a", "b"), vm.visible())
            vm.editSearch("at night")
            settle()
            assertEquals(listOf("a"), vm.visible())
            vm.editSearch("_")
            settle()
            assertEquals(emptyList<String>(), vm.visible())
        }

        @Test
        fun `search and the tag filter combine by AND, in the list too`() = runTest(main.dispatcher) {
            val vm = vm()
            vm.setTagFilter(filter("!love"))
            vm.editSearch("cat")
            settle()
            assertEquals(listOf("b"), vm.visible())
            vm.setMode(CanvasModeKind.LIST)
            assertEquals(listOf("b"), vm.ready().listRows.map { it.id.value })
            assertTrue(vm.ready().isFiltered)
        }

        @Test
        fun `the count is shown only while a search applies`() = runTest(main.dispatcher) {
            val vm = vm()
            assertNull(vm.ready().searchCount)
            vm.editSearch("cat")
            settle()
            assertEquals(SearchCount(2, 3), vm.ready().searchCount)
            assertEquals("2 of 3", vm.ready().searchCount!!.label)
            vm.setTagFilter(filter("love"))
            assertEquals(SearchCount(1, 3), vm.ready().searchCount)
        }

        @Test
        fun `clear applies at once`() = runTest(main.dispatcher) {
            val vm = vm()
            vm.editSearch("dog")
            settle()
            assertEquals(listOf("c"), vm.visible())
            vm.clearSearch()
            assertEquals("", vm.searchInput.value)
            assertEquals(listOf("a", "b", "c"), vm.visible())
            assertEquals(SearchQuery.EVERYTHING, vm.ready().search)
        }

        @Test
        fun `deleting the text by hand also applies without the debounce`() = runTest(main.dispatcher) {
            val vm = vm()
            vm.editSearch("dog")
            settle()
            vm.editSearch("")
            runCurrent()
            assertEquals(listOf("a", "b", "c"), vm.visible())
        }

        @Test
        fun `hidden snippets leave the selection and cannot be hit`() = runTest(main.dispatcher) {
            val vm = vm()
            vm.setViewSize(ViewSize(2000.0, 1000.0))
            vm.onLongPress(SnippetId("a"))
            vm.onTap(SnippetId("b"))
            vm.editSearch("white")
            settle()
            assertEquals(setOf(SnippetId("b")), vm.ready().selection.ids)
            assertNull(vm.hitTest(Point(10.0, 10.0)))
        }

        @Test
        fun `the query lasts per canvas for the session`() = runTest(main.dispatcher) {
            val memory = CanvasSearchMemory()
            vm(memory).editSearch("dog")
            assertEquals("dog", memory.get(CANVAS_ID))
            assertEquals("", memory.get(CanvasId("other")))
            val again = vm(memory)
            assertEquals("dog", again.searchInput.value)
            assertEquals(listOf("c"), again.visible())
            again.clearSearch()
            assertEquals("", memory.get(CANVAS_ID))
        }
    }

    @Nested
    inner class Focus {
        private fun focusOn(id: String) = SavedStateHandle(mapOf("focusSnippetId" to id))

        @Test
        fun `opening focused on a snippet selects it and centres the canvas once laid out`() = runTest(main.dispatcher) {
            val vm = vm(savedState = focusOn("c"))
            assertEquals(setOf(SnippetId("c")), vm.ready().selection.ids)
            val size = ViewSize(1000.0, 800.0)
            vm.setViewSize(size)
            // c is 300 x 150 at (800, 600).
            assertEquals(Point(950.0, 675.0), viewportCenter(vm.ready().viewport, size))
        }

        @Test
        fun `in list mode the screen is asked to scroll to it`() = runTest(main.dispatcher) {
            val saved = focusOn("b").apply { set(CANVAS_MODE_KEY, CanvasModeKind.LIST.name) }
            val vm = vm(savedState = saved, load = false)
            vm.events.test {
                runCurrent()
                assertEquals(CanvasEvent.RevealSnippet(SnippetId("b")), awaitItem())
            }
            assertEquals(setOf(SnippetId("b")), vm.ready().selection.ids)
        }

        @Test
        fun `a search that hides it is cleared`() = runTest(main.dispatcher) {
            val memory = CanvasSearchMemory().apply { set(CANVAS_ID, "dog") }
            val vm = vm(memory, focusOn("a"))
            assertEquals("", vm.searchInput.value)
            assertEquals(listOf("a", "b", "c"), vm.visible())
            assertEquals(setOf(SnippetId("a")), vm.ready().selection.ids)
        }

        @Test
        fun `a search that shows it is kept`() = runTest(main.dispatcher) {
            val memory = CanvasSearchMemory().apply { set(CANVAS_ID, "cat") }
            val vm = vm(memory, focusOn("a"))
            assertEquals("cat", vm.searchInput.value)
            assertEquals(listOf("a", "b"), vm.visible())
        }

        @Test
        fun `a tag filter restored later that hides it says so`() = runTest(main.dispatcher) {
            val vm = vm(savedState = focusOn("a"), load = false)
            vm.events.test {
                runCurrent()
                vm.setTagFilter(filter("!love"))
                assertEquals(CanvasEvent.Message(HIDDEN_BY_TAG_FILTER), awaitItem())
                assertTrue(vm.ready().selection.ids.isEmpty())
                // Only once: later filter edits say nothing.
                vm.setTagFilter(filter("love"))
                vm.setTagFilter(filter("!love"))
                expectNoEvents()
            }
        }

        @Test
        fun `a tag filter in force at load that hides it says so and selects nothing`() = runTest(main.dispatcher) {
            val vm = vm(savedState = focusOn("a"), load = false)
            vm.setTagFilter(filter("!love"))
            vm.events.test {
                runCurrent()
                assertEquals(CanvasEvent.Message(HIDDEN_BY_TAG_FILTER), awaitItem())
            }
            assertTrue(vm.ready().selection.ids.isEmpty())
        }

        @Test
        fun `a missing snippet is reported and the focus is applied once`() = runTest(main.dispatcher) {
            val saved = focusOn("gone")
            val vm = vm(savedState = saved, load = false)
            vm.events.test {
                runCurrent()
                assertEquals(CanvasEvent.Message(FOCUS_SNIPPET_MISSING), awaitItem())
                vm.reload()
                runCurrent()
                expectNoEvents()
            }
        }

        @Test
        fun `a reload does not focus again`() = runTest(main.dispatcher) {
            val vm = vm(savedState = focusOn("c"))
            vm.exitSelection()
            vm.reload()
            runCurrent()
            assertTrue(vm.ready().selection.ids.isEmpty())
        }
    }
}
