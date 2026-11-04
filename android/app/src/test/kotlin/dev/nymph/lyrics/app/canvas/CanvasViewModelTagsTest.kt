package dev.nymph.lyrics.app.canvas

import dev.nymph.lyrics.app.MainDispatcherExtension
import dev.nymph.lyrics.app.fakes.CANVAS_ID
import dev.nymph.lyrics.app.fakes.FakeCanvasRepository
import dev.nymph.lyrics.app.fakes.FakeSnippetRepository
import dev.nymph.lyrics.app.fakes.canvasDetail
import dev.nymph.lyrics.app.fakes.snippet
import dev.nymph.lyrics.app.fakes.tag
import dev.nymph.lyrics.logic.canvas.Point
import dev.nymph.lyrics.logic.tags.TagExprParse
import dev.nymph.lyrics.logic.tags.TagFilter
import dev.nymph.lyrics.logic.tags.parseTagExpression
import dev.nymph.lyrics.model.SnippetId
import dev.nymph.lyrics.model.SnippetType
import dev.nymph.lyrics.model.error.ApiResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

/** The tag filter's visibility rule and the tag changes the picker reports ([TagCanvasPort]). */
@OptIn(ExperimentalCoroutinesApi::class)
class CanvasViewModelTagsTest {
    @JvmField
    @RegisterExtension
    val main = MainDispatcherExtension()

    private val love = tag("love")
    private val night = tag("night", "#00ff00")
    private val a = snippet("a", rawText = "one", tags = listOf(love), contentUpdatedAt = "2026-09-02")
    private val b = snippet("b", rawText = "two", x = 400.0, tags = listOf(night), type = SnippetType.HOOK, contentUpdatedAt = "2026-09-01")
    private val c = snippet("c", rawText = "three", x = 800.0, contentUpdatedAt = "2026-09-03")

    private fun filter(text: String): TagFilter = (parseTagExpression(text) as TagExprParse.Valid).filter

    private fun TestScope.vm(): CanvasViewModel {
        val vm = CanvasViewModel(CANVAS_ID, FakeCanvasRepository(ApiResult.Success(canvasDetail(listOf(a, b, c)))), FakeSnippetRepository(), backgroundScope)
        runCurrent()
        return vm
    }

    private fun CanvasViewModel.ready(): CanvasUiState.Ready = state.value as CanvasUiState.Ready

    private fun CanvasViewModel.visible(): List<String> = ready().visibleSnippets.map { it.id.value }

    @Test
    fun `everything shows without a filter`() = runTest(main.dispatcher) {
        val vm = vm()
        assertEquals(listOf("a", "b", "c"), vm.visible())
        assertEquals(TagFilter.Everything, vm.ready().filter)
    }

    @Test
    fun `non-matching snippets are hidden from the canvas and the list`() = runTest(main.dispatcher) {
        val vm = vm()
        vm.setTagFilter(filter("love | type:hook"))
        assertEquals(listOf("a", "b"), vm.visible())
        vm.setMode(CanvasModeKind.LIST)
        assertEquals(listOf("a", "b"), vm.ready().listRows.map { it.id.value })
        vm.setTagFilter(filter("!love"))
        assertEquals(listOf("c", "b"), vm.ready().listRows.map { it.id.value })
        // The snippets themselves are all kept.
        assertEquals(3, vm.ready().snippets.size)
    }

    @Test
    fun `hidden cards cannot be hit`() = runTest(main.dispatcher) {
        val vm = vm()
        vm.setViewSize(dev.nymph.lyrics.logic.canvas.ViewSize(2000.0, 1000.0))
        assertEquals(SnippetId("b"), vm.hitTest(Point(410.0, 10.0)))
        vm.setTagFilter(filter("love"))
        assertNull(vm.hitTest(Point(410.0, 10.0)))
    }

    @Test
    fun `a filter set before the canvas loads applies once it does`() = runTest(main.dispatcher) {
        val vm = CanvasViewModel(CANVAS_ID, FakeCanvasRepository(ApiResult.Success(canvasDetail(listOf(a, b, c)))), FakeSnippetRepository(), backgroundScope)
        vm.setTagFilter(filter("night"))
        runCurrent()
        assertEquals(listOf("b"), vm.visible())
    }

    @Test
    fun `hiding a selected snippet deselects it`() = runTest(main.dispatcher) {
        val vm = vm()
        vm.onListLongPress(a.id)
        vm.onListLongPress(b.id)
        vm.setTagFilter(filter("night"))
        assertEquals(setOf(b.id), vm.ready().selection.ids)
        vm.setTagFilter(filter("nothing"))
        assertEquals(Selection.Idle, vm.ready().selection)
    }

    @Test
    fun `linked and unlinked tags update snippets and visibility`() = runTest(main.dispatcher) {
        val vm = vm()
        vm.setTagFilter(filter("love"))
        vm.onTagLinked(c.id, love)
        assertEquals(listOf("a", "c"), vm.visible())
        assertEquals(listOf(love), vm.snippet(c.id)?.tags)
        vm.onTagUnlinked(a.id, love.id)
        assertEquals(listOf("c"), vm.visible())
        assertEquals(mapOf(a.id to a.copy(tags = emptyList())), vm.currentSnippets().filterKeys { it == a.id })
    }

    @Test
    fun `edited and deleted tags reach every snippet`() = runTest(main.dispatcher) {
        val vm = vm()
        vm.onTagLinked(b.id, love)
        val renamed = love.copy(name = "romance", color = "#123456")
        vm.onTagEdited(renamed)
        assertEquals(listOf(renamed), vm.snippet(a.id)?.tags)
        assertEquals(listOf(night, renamed), vm.snippet(b.id)?.tags)
        vm.onTagDeleted(night.id)
        assertEquals(listOf(renamed), vm.snippet(b.id)?.tags)
    }
}
