package dev.nymph.lyrics.app.rhyme

import dev.nymph.lyrics.app.MainDispatcherExtension
import dev.nymph.lyrics.app.fakes.FakeNlpRepository
import dev.nymph.lyrics.app.fakes.FakeSnippetRepository
import dev.nymph.lyrics.app.fakes.candidate
import dev.nymph.lyrics.app.fakes.catSnippet
import dev.nymph.lyrics.app.fakes.response
import dev.nymph.lyrics.app.rhyme.order.InMemoryRhymeOrderStore
import dev.nymph.lyrics.app.rhyme.rhythm.InMemoryRhythmFitStore
import dev.nymph.lyrics.logic.rhyme.BandedItem
import dev.nymph.lyrics.logic.rhyme.OrderPresentation
import dev.nymph.lyrics.logic.rhyme.RhythmOutcome
import dev.nymph.lyrics.model.RhymeBand
import dev.nymph.lyrics.model.RhymeCandidate
import dev.nymph.lyrics.model.RhymeOrder
import dev.nymph.lyrics.model.RhymeSuggestRequest
import dev.nymph.lyrics.model.RhymeSuggestResponse
import dev.nymph.lyrics.model.RhythmUnavailableReason
import dev.nymph.lyrics.model.error.ApiResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

/** The rhyme sheet's "Fit rhythm" switch: request field, per-device memory, outcome, banding, paging. */
@OptIn(ExperimentalCoroutinesApi::class)
class RhymeViewModelRhythmTest {
    @JvmField
    @RegisterExtension
    val main = MainDispatcherExtension()

    private val cat = catSnippet("s1")

    private fun c(id: String, score: Double, band: RhymeBand, fit: Double?): RhymeCandidate =
        candidate(id).copy(score = score, band = band, rhythmFit = fit)

    /** Rhythm asked: applied (banded, by fit) unless [reason]; otherwise plain rows. */
    private fun answer(request: RhymeSuggestRequest, reason: RhythmUnavailableReason? = null): ApiResult<RhymeSuggestResponse> {
        val applied = request.rhythmFit == true && reason == null
        val rows = if (applied) {
            listOf(c("p2", 0.96, RhymeBand.PERFECT, 1.0), c("p1", 0.97, RhymeBand.PERFECT, 0.5), c("n1", 0.8, RhymeBand.NEAR, 1.0))
        } else {
            listOf(c("p1", 0.97, RhymeBand.PERFECT, null), c("p2", 0.96, RhymeBand.PERFECT, null), c("n1", 0.8, RhymeBand.NEAR, null))
        }
        val body = response(*rows.toTypedArray()).copy(
            rhythmApplied = applied,
            rhythmUnavailableReason = if (request.rhythmFit == true) reason else null,
        )
        return ApiResult.Success(body)
    }

    private fun TestScope.openVm(
        nlp: FakeNlpRepository,
        rhythm: InMemoryRhythmFitStore = InMemoryRhythmFitStore(),
        orders: InMemoryRhymeOrderStore = InMemoryRhymeOrderStore(),
        port: FakeCanvasPort = FakeCanvasPort(listOf(cat), setOf(cat.id)),
    ): RhymeViewModel {
        val vm = RhymeViewModel(port, nlp, FakeSnippetRepository(), orders = orders, rhythm = rhythm)
        runCurrent()
        vm.open()
        runCurrent()
        return vm
    }

    private fun TestScope.settle() {
        advanceTimeBy(151)
        runCurrent()
    }

    private fun RhymeViewModel.rendered(): List<String> = state.value.items.map {
        when (it) {
            is BandedItem.Header -> "[${it.band}]"
            is BandedItem.Row -> it.value.id
        }
    }

    @Test
    fun `off by default sends no rhythm_fit and shows plain rows`() = runTest(main.dispatcher) {
        val nlp = FakeNlpRepository({ answer(it) })
        val vm = openVm(nlp)
        assertNull(nlp.requests.single().rhythmFit)
        assertFalse(vm.state.value.params.rhythmFit)
        assertEquals(RhythmOutcome.NotAsked, vm.state.value.ordering.rhythm)
        assertEquals(listOf("p1", "p2", "n1"), vm.rendered())
        assertNull(vm.state.value.rhythmNote)
    }

    @Test
    fun `turning it on refetches from the first page, bands the rows and is remembered`() = runTest(main.dispatcher) {
        val store = InMemoryRhythmFitStore()
        val nlp = FakeNlpRepository({ answer(it) })
        val vm = openVm(nlp, store)
        vm.setRhythmFit(true)
        settle()
        assertEquals(2, nlp.requests.size)
        assertEquals(true, nlp.requests.last().rhythmFit)
        assertEquals(0, nlp.requests.last().offset)
        assertEquals(RhythmOutcome.Applied, vm.state.value.ordering.rhythm)
        assertEquals(OrderPresentation.BANDED, vm.state.value.presentation)
        assertEquals(listOf("[PERFECT]", "p2", "p1", "[NEAR]", "n1"), vm.rendered())
        assertTrue(store.enabled.first())
    }

    @Test
    fun `a stored choice applies on open`() = runTest(main.dispatcher) {
        val nlp = FakeNlpRepository({ answer(it) })
        val vm = openVm(nlp, InMemoryRhythmFitStore(true))
        assertEquals(true, nlp.requests.single().rhythmFit)
        assertTrue(vm.state.value.params.rhythmFit)
    }

    @Test
    fun `an unavailable reason is shown and the rows stay plain`() = runTest(main.dispatcher) {
        val nlp = FakeNlpRepository({ answer(it, RhythmUnavailableReason.NO_SCORE) })
        val vm = openVm(nlp, InMemoryRhythmFitStore(true))
        assertEquals(RhythmOutcome.Unavailable(RhythmUnavailableReason.NO_SCORE), vm.state.value.ordering.rhythm)
        assertEquals("Fit rhythm unavailable: this snippet has no score", vm.state.value.rhythmNote)
        assertEquals(OrderPresentation.PLAIN, vm.state.value.presentation)
    }

    @Test
    fun `works with meaning order and both fields go out`() = runTest(main.dispatcher) {
        val nlp = FakeNlpRepository({ answer(it) })
        val vm = openVm(nlp, InMemoryRhythmFitStore(true), InMemoryRhymeOrderStore(RhymeOrder.MEANING))
        val request = nlp.requests.single()
        assertEquals(true, request.rhythmFit)
        assertEquals(RhymeOrder.MEANING, request.order)
        // Server answered rhythm-applied, rhyme order (meaning model down): bands plus the meaning note.
        assertEquals(OrderPresentation.BANDED, vm.state.value.presentation)
        assertTrue(vm.state.value.ordering.meaningUnavailable)
    }

    @Test
    fun `more pages keep rhythm_fit and the outcome of the first page`() = runTest(main.dispatcher) {
        val page1 = (1..40).map { c("p$it", 0.97, RhymeBand.PERFECT, 0.5) }
        val page2 = listOf(c("p41", 0.96, RhymeBand.PERFECT, 0.25), c("s1", 0.9, RhymeBand.STRONG, 1.0))
        val nlp = FakeNlpRepository({ request ->
            val rows = if (request.offset == 0) page1 else page2
            ApiResult.Success(response(*rows.toTypedArray(), total = 42).copy(rhythmApplied = request.rhythmFit == true))
        })
        val vm = openVm(nlp, InMemoryRhythmFitStore(true))
        vm.loadMore()
        runCurrent()
        assertEquals(true, nlp.requests.last().rhythmFit)
        assertEquals(40, nlp.requests.last().offset)
        assertEquals(RhythmOutcome.Applied, vm.state.value.ordering.rhythm)
        val headers = vm.state.value.items.filterIsInstance<BandedItem.Header>().map { it.band }
        assertEquals(listOf(RhymeBand.PERFECT, RhymeBand.STRONG), headers)
        assertEquals(42, vm.state.value.rows.size)
    }

    @Test
    fun `the switch shows only with a snippet anchor and a typed-only query never asks`() = runTest(main.dispatcher) {
        val port = FakeCanvasPort(listOf(cat), setOf(cat.id))
        val nlp = FakeNlpRepository({ answer(it) })
        val vm = openVm(nlp, InMemoryRhythmFitStore(true), port = port)
        assertTrue(vm.state.value.rhythmToggleShown)
        port.select(emptySet())
        vm.setTypedText("door")
        settle()
        assertFalse(vm.state.value.rhythmToggleShown)
        assertNull(nlp.requests.last().rhythmFit)
        assertEquals(RhythmOutcome.NotAsked, vm.state.value.ordering.rhythm)
    }

    @Test
    fun `turning it off goes back to plain rows`() = runTest(main.dispatcher) {
        val nlp = FakeNlpRepository({ answer(it) })
        val vm = openVm(nlp, InMemoryRhythmFitStore(true))
        vm.setRhythmFit(false)
        settle()
        assertNull(nlp.requests.last().rhythmFit)
        assertEquals(OrderPresentation.PLAIN, vm.state.value.presentation)
        assertEquals(listOf("p1", "p2", "n1"), vm.rendered())
    }
}
