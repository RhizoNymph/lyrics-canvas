package dev.nymph.lyrics.app.rhyme

import dev.nymph.lyrics.app.MainDispatcherExtension
import dev.nymph.lyrics.app.fakes.FakeNlpRepository
import dev.nymph.lyrics.app.fakes.FakeSnippetRepository
import dev.nymph.lyrics.app.fakes.candidate
import dev.nymph.lyrics.app.fakes.catSnippet
import dev.nymph.lyrics.app.fakes.response
import dev.nymph.lyrics.app.fakes.snippet
import dev.nymph.lyrics.app.fakes.unit
import dev.nymph.lyrics.logic.rhyme.ManualSlotError
import dev.nymph.lyrics.logic.rhyme.RhymeErrorKind
import dev.nymph.lyrics.logic.rhyme.RhymeOperation
import dev.nymph.lyrics.logic.rhyme.SlotMode
import dev.nymph.lyrics.model.AnchorRange
import dev.nymph.lyrics.model.Provenance
import dev.nymph.lyrics.model.RhymeK
import dev.nymph.lyrics.model.RhymeLimits
import dev.nymph.lyrics.model.RhymeSuggestResponse
import dev.nymph.lyrics.model.Slot
import dev.nymph.lyrics.model.SnippetAnchorRef
import dev.nymph.lyrics.model.SnippetId
import dev.nymph.lyrics.model.SnippetType
import dev.nymph.lyrics.model.SourceName
import dev.nymph.lyrics.model.TextAnchorRef
import dev.nymph.lyrics.model.error.ApiError
import dev.nymph.lyrics.model.error.ApiResult
import kotlinx.coroutines.CompletableDeferred
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
class RhymeViewModelTest {
    @JvmField
    @RegisterExtension
    val main = MainDispatcherExtension()

    private val cat = catSnippet("s1", x = 100.0, y = 50.0)
    private val other = snippet("s2", rawText = "", x = 0.0, y = 500.0)
    private val nlp = FakeNlpRepository()
    private val snippets = FakeSnippetRepository()

    private fun TestScope.openVm(port: FakeCanvasPort = FakeCanvasPort(listOf(cat, other), setOf(cat.id))): RhymeViewModel {
        val vm = RhymeViewModel(port, nlp, snippets)
        vm.open()
        runCurrent()
        return vm
    }

    @Test
    fun `opening queries at once with the selected snippet as anchor`() = runTest(main.dispatcher) {
        nlp.handler = { ApiResult.Success(response(candidate("hat"), candidate("bat"), total = 2)) }
        val vm = openVm()
        val request = nlp.requests.single()
        assertEquals(listOf(SnippetAnchorRef(cat.id, null)), request.anchors)
        assertEquals(RhymeK.K2, request.k)
        assertEquals(0, request.offset)
        // From-selection slot: the cat snippet's last line "on the mat" (0 0 1).
        assertEquals(Slot(3, "001"), request.slot)
        val state = vm.state.value
        assertEquals(listOf("hat", "bat"), state.rows.map { it.id })
        assertEquals(Slot(3, "001"), state.slot)
        assertFalse(state.loading)
        assertEquals(2, state.total)
    }

    @Test
    fun `nothing is queried before the sheet opens`() = runTest(main.dispatcher) {
        RhymeViewModel(FakeCanvasPort(listOf(cat), setOf(cat.id)), nlp, snippets)
        advanceUntilIdle()
        assertTrue(nlp.requests.isEmpty())
    }

    @Test
    fun `typing re-queries once after a 150 ms pause`() = runTest(main.dispatcher) {
        val vm = openVm()
        vm.setTypedText("d")
        advanceTimeBy(100)
        vm.setTypedText("do")
        advanceTimeBy(100)
        vm.setTypedText("door")
        advanceTimeBy(149)
        runCurrent()
        assertEquals(1, nlp.requests.size)
        advanceTimeBy(2)
        runCurrent()
        assertEquals(2, nlp.requests.size)
        assertEquals(listOf(SnippetAnchorRef(cat.id, null), TextAnchorRef("door")), nlp.requests.last().anchors)
        advanceUntilIdle()
        assertEquals(2, nlp.requests.size)
    }

    @Test
    fun `a newer query wins over a slower older one`() = runTest(main.dispatcher) {
        val slow = CompletableDeferred<ApiResult<RhymeSuggestResponse>>()
        nlp.handler = { request ->
            if (request.anchors.any { it is TextAnchorRef && it.text == "slow" }) slow.await() else ApiResult.Success(response(candidate("fast")))
        }
        val vm = openVm()
        vm.setTypedText("slow")
        advanceTimeBy(151)
        runCurrent()
        assertTrue(vm.state.value.loading)
        vm.setTypedText("quick")
        advanceTimeBy(151)
        runCurrent()
        slow.complete(ApiResult.Success(response(candidate("stale"))))
        advanceUntilIdle()
        assertEquals(listOf("fast"), vm.state.value.rows.map { it.id })
        assertFalse(vm.state.value.loading)
        // The slow request was cancelled before it completed.
        assertFalse(nlp.completed.any { r -> r.anchors.any { it is TextAnchorRef && it.text == "slow" } })
    }

    @Test
    fun `selection changes re-query and an empty selection clears without a request`() = runTest(main.dispatcher) {
        nlp.handler = { ApiResult.Success(response(candidate("hat"))) }
        val port = FakeCanvasPort(listOf(cat, other), setOf(cat.id))
        val vm = openVm(port)
        assertEquals(1, vm.state.value.rows.size)
        port.select()
        advanceTimeBy(151)
        runCurrent()
        assertEquals(1, nlp.requests.size)
        assertTrue(vm.state.value.rows.isEmpty())
        assertTrue(vm.state.value.anchors.isEmpty())
    }

    @Test
    fun `viewport-only canvas changes do not re-query`() = runTest(main.dispatcher) {
        val port = FakeCanvasPort(listOf(cat, other), setOf(cat.id))
        openVm(port)
        port.view.value = port.view.value!!.copy(viewport = dev.nymph.lyrics.logic.canvas.Viewport(5.0, 5.0, 2.0))
        advanceUntilIdle()
        assertEquals(1, nlp.requests.size)
    }

    @Test
    fun `search now skips the debounce`() = runTest(main.dispatcher) {
        val vm = openVm()
        vm.setTypedText("door")
        vm.searchNow()
        runCurrent()
        assertEquals(2, nlp.requests.size)
        assertEquals(TextAnchorRef("door"), nlp.requests.last().anchors.last())
    }

    @Test
    fun `query failures become a typed query error`() = runTest(main.dispatcher) {
        nlp.handler = { ApiResult.Failure(ApiError.Validation(422, "bad stress")) }
        val vm = openVm()
        val error = vm.state.value.error!!
        assertEquals(RhymeErrorKind.INVALID_REQUEST, error.kind)
        assertEquals(RhymeOperation.QUERY, error.operation)
        assertEquals("bad stress", error.message)
        assertFalse(vm.state.value.loading)
    }

    @Test
    fun `picking a word range anchors on it and tapping the start again clears it`() = runTest(main.dispatcher) {
        val vm = openVm()
        vm.onWordTap(cat.id, lineIndex = 0, wordIndex = 1)
        assertEquals(AnchorRange(0, 1, 2), vm.state.value.ranges[cat.id])
        assertTrue(cat.id in vm.state.value.awaitingEnd)
        vm.onWordTap(cat.id, lineIndex = 0, wordIndex = 2)
        assertEquals(AnchorRange(0, 1, 3), vm.state.value.ranges[cat.id])
        assertFalse(cat.id in vm.state.value.awaitingEnd)
        advanceTimeBy(151)
        runCurrent()
        assertEquals(2, nlp.requests.size)
        assertEquals(listOf(SnippetAnchorRef(cat.id, 0, 1, 3)), nlp.requests.last().anchors)
        // From-selection slot follows the range: "cat sat" = 1 1.
        assertEquals(Slot(2, "11"), nlp.requests.last().slot)
        vm.onWordTap(cat.id, lineIndex = 0, wordIndex = 1)
        assertNull(vm.state.value.ranges[cat.id])
    }

    @Test
    fun `reset range returns to the whole last line`() = runTest(main.dispatcher) {
        val vm = openVm()
        vm.onWordTap(cat.id, 1, 2)
        vm.resetRange(cat.id)
        assertNull(vm.state.value.ranges[cat.id])
        assertFalse(cat.id in vm.state.value.awaitingEnd)
    }

    @Test
    fun `an excluded chip drops its anchor and restore brings it back`() = runTest(main.dispatcher) {
        val vm = openVm()
        vm.setTypedText("door")
        vm.excludeAnchor(cat.id)
        advanceTimeBy(151)
        runCurrent()
        assertEquals(listOf(TextAnchorRef("door")), nlp.requests.last().anchors)
        assertTrue(vm.state.value.chips.single().excluded)
        vm.restoreAnchor(cat.id)
        advanceTimeBy(151)
        runCurrent()
        assertEquals(listOf(SnippetAnchorRef(cat.id, null), TextAnchorRef("door")), nlp.requests.last().anchors)
    }

    @Test
    fun `controls go into the request`() = runTest(main.dispatcher) {
        val vm = openVm()
        vm.setK(RhymeK.K3)
        vm.setMinScore(1.7)
        vm.toggleSource(SourceName.NOTES)
        vm.setIncludeRare(true)
        advanceTimeBy(151)
        runCurrent()
        val request = nlp.requests.last()
        assertEquals(RhymeK.K3, request.k)
        assertEquals(1.0, request.minScore)
        assertEquals(listOf(SourceName.DICTIONARY), request.sources)
        assertTrue(request.includeRare)
    }

    @Test
    fun `manual slot is validated and only a valid one is sent`() = runTest(main.dispatcher) {
        val vm = openVm()
        vm.setSlotMode(SlotMode.MANUAL)
        vm.setManualSlot("3", "10x")
        assertEquals(ManualSlotError.STRESS, vm.state.value.manualError)
        vm.setManualSlot("2", "101")
        assertEquals(ManualSlotError.COUNT, vm.state.value.manualError)
        advanceTimeBy(151)
        runCurrent()
        assertNull(nlp.requests.last().slot)
        vm.setManualSlot("3", "101")
        assertNull(vm.state.value.manualError)
        advanceTimeBy(151)
        runCurrent()
        assertEquals(Slot(3, "101"), nlp.requests.last().slot)
    }

    @Test
    fun `dismiss hides a row, does not re-query, and later queries exclude it`() = runTest(main.dispatcher) {
        nlp.handler = { ApiResult.Success(response(candidate("hat"), candidate("bat"))) }
        val vm = openVm()
        vm.dismiss("hat")
        advanceUntilIdle()
        assertEquals(1, nlp.requests.size)
        assertEquals(listOf("bat"), vm.state.value.rows.map { it.id })
        vm.setTypedText("x")
        advanceTimeBy(151)
        runCurrent()
        assertEquals(listOf("hat"), nlp.requests.last().excludeIds)
        // A server answer that still has the dismissed id is filtered.
        assertEquals(listOf("bat"), vm.state.value.rows.map { it.id })
    }

    @Test
    fun `more loads the next page and appends unseen rows`() = runTest(main.dispatcher) {
        nlp.handler = { request ->
            if (request.offset == 0) {
                ApiResult.Success(response(candidate("a"), candidate("b"), total = 60))
            } else {
                ApiResult.Success(response(candidate("b"), candidate("c"), total = 60))
            }
        }
        val vm = openVm()
        assertTrue(vm.state.value.canLoadMore)
        vm.loadMore()
        runCurrent()
        assertEquals(RhymeLimits.PAGE_SIZE, nlp.requests.last().offset)
        assertEquals(listOf("a", "b", "c"), vm.state.value.rows.map { it.id })
        assertEquals(RhymeLimits.PAGE_SIZE, vm.state.value.offset)
        assertFalse(vm.state.value.canLoadMore)
        vm.loadMore()
        runCurrent()
        assertEquals(2, nlp.requests.size)
    }

    @Test
    fun `materialize places right of the single anchor, steps down, inserts and dismisses`() = runTest(main.dispatcher) {
        nlp.handler = {
            ApiResult.Success(response(candidate("hat", bestAnchor = "a1"), candidate("bat", bestAnchor = "a1"), anchors = listOf(unit("a1", "on the mat", cat.id))))
        }
        val port = FakeCanvasPort(listOf(cat, other), setOf(cat.id))
        val vm = openVm(port)
        vm.materialize("hat")
        runCurrent()
        vm.materialize("bat")
        runCurrent()
        val (first, second) = snippets.created
        // cat: x 100, width 300 => 100 + 300 + 40; second one row (60) lower.
        assertEquals(440.0 to 50.0, first.positionX to first.positionY)
        assertEquals(440.0 to 110.0, second.positionX to second.positionY)
        assertEquals(SnippetType.FREESTYLE, first.snippetType)
        assertEquals("hat", first.rawText)
        val derived = first.derivedFrom!!
        assertEquals(Provenance.DICTIONARY, derived.provenance)
        assertEquals("hat", derived.sourceUnitId)
        assertEquals("a1", derived.anchorUnitId)
        assertEquals("on the mat", derived.anchorText)
        assertEquals(2, derived.k)
        assertEquals(2, port.inserted.size)
        assertTrue(vm.state.value.rows.isEmpty())
        assertEquals(setOf("hat", "bat"), vm.state.value.dismissed)
        assertEquals(mapOf(cat.id to 2), vm.state.value.materializeCounts)
        // Materializing does not change the selection, so no re-query.
        advanceUntilIdle()
        assertEquals(1, nlp.requests.size)
    }

    @Test
    fun `materialize with only a typed anchor lands at the viewport centre`() = runTest(main.dispatcher) {
        nlp.handler = { ApiResult.Success(response(candidate("hat"))) }
        val port = FakeCanvasPort(listOf(cat), emptySet())
        val vm = openVm(port)
        vm.setTypedText("door")
        vm.searchNow()
        runCurrent()
        vm.materialize("hat")
        runCurrent()
        val body = snippets.created.single()
        assertEquals(200.0 to 400.0, body.positionX to body.positionY)
        assertTrue(vm.state.value.materializeCounts.isEmpty())
    }

    @Test
    fun `materialize uses the camera at the time it runs`() = runTest(main.dispatcher) {
        nlp.handler = { ApiResult.Success(response(candidate("hat"))) }
        val port = FakeCanvasPort(listOf(cat), emptySet())
        val vm = openVm(port)
        vm.setTypedText("door")
        vm.searchNow()
        runCurrent()
        port.view.value = port.view.value!!.copy(viewport = dev.nymph.lyrics.logic.canvas.Viewport(-100.0, 0.0, 2.0))
        runCurrent()
        vm.materialize("hat")
        runCurrent()
        // Centre (200, 400) screen -> world (200 / 2 + 100, 400 / 2).
        val body = snippets.created.single()
        assertEquals(200.0 to 200.0, body.positionX to body.positionY)
    }

    @Test
    fun `materialize failure keeps the row and reports a materialize error`() = runTest(main.dispatcher) {
        nlp.handler = { ApiResult.Success(response(candidate("hat"))) }
        snippets.createResult = { ApiResult.Failure(ApiError.Server(500, "db down")) }
        val port = FakeCanvasPort(listOf(cat), setOf(cat.id))
        val vm = openVm(port)
        vm.materialize("hat")
        runCurrent()
        assertEquals(listOf("hat"), vm.state.value.rows.map { it.id })
        assertEquals(RhymeOperation.MATERIALIZE, vm.state.value.error!!.operation)
        assertEquals(RhymeErrorKind.SERVER, vm.state.value.error!!.kind)
        assertTrue(port.inserted.isEmpty())
        assertTrue(vm.state.value.materializing.isEmpty())
    }

    @Test
    fun `chips show the selected non-blank snippets with their echoed key and lines`() = runTest(main.dispatcher) {
        nlp.handler = { ApiResult.Success(response(anchors = listOf(unit("a1", "on the mat", cat.id)))) }
        val port = FakeCanvasPort(listOf(cat, other), setOf(cat.id, other.id))
        val vm = openVm(port)
        val chip = vm.state.value.chips.single()
        assertEquals(cat.id, chip.snippetId)
        assertEquals("on the mat", chip.label)
        assertEquals("AE1 T", chip.rhymeKey)
        assertEquals(2, chip.lines.size)
        assertFalse(chip.expanded)
        vm.toggleChipExpanded(cat.id)
        assertTrue(vm.state.value.chips.single().expanded)
    }

    @Test
    fun `tapping a row expands it and tapping again collapses`() = runTest(main.dispatcher) {
        nlp.handler = { ApiResult.Success(response(candidate("hat"))) }
        val vm = openVm()
        vm.toggleRow("hat")
        assertEquals("hat", vm.state.value.expandedRow)
        vm.toggleRow("hat")
        assertNull(vm.state.value.expandedRow)
    }

    @Test
    fun `close ends the session but keeps the query controls`() = runTest(main.dispatcher) {
        nlp.handler = { ApiResult.Success(response(candidate("hat"))) }
        val vm = openVm()
        vm.setK(RhymeK.K4)
        vm.setTypedText("door")
        vm.dismiss("hat")
        vm.close()
        advanceUntilIdle()
        val state = vm.state.value
        assertFalse(state.open)
        assertEquals("", state.typedText)
        assertTrue(state.rows.isEmpty() && state.dismissed.isEmpty())
        assertEquals(RhymeK.K4, state.params.k)
        // The pending debounced query was cancelled with the session.
        assertEquals(1, nlp.requests.size)
        vm.open()
        runCurrent()
        assertEquals(RhymeK.K4, nlp.requests.last().k)
    }

    @Test
    fun `a selected snippet removed from the canvas prunes its exclusion and range`() = runTest(main.dispatcher) {
        val port = FakeCanvasPort(listOf(cat, other), setOf(cat.id))
        val vm = openVm(port)
        vm.onWordTap(cat.id, 0, 1)
        vm.excludeAnchor(cat.id)
        port.select(setOf(SnippetId("s2")))
        advanceTimeBy(151)
        runCurrent()
        assertTrue(vm.state.value.excluded.isEmpty())
        assertTrue(vm.state.value.ranges.isEmpty())
    }
}
