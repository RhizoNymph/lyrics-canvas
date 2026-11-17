package dev.nymph.lyrics.app.rhyme

import dev.nymph.lyrics.app.MainDispatcherExtension
import dev.nymph.lyrics.app.fakes.FakeNlpRepository
import dev.nymph.lyrics.app.fakes.FakeSnippetRepository
import dev.nymph.lyrics.app.fakes.candidate
import dev.nymph.lyrics.app.fakes.catSnippet
import dev.nymph.lyrics.app.fakes.response
import dev.nymph.lyrics.app.rhyme.order.InMemoryRhymeOrderStore
import dev.nymph.lyrics.logic.rhyme.BandedItem
import dev.nymph.lyrics.logic.rhyme.OrderPresentation
import dev.nymph.lyrics.model.RhymeBand
import dev.nymph.lyrics.model.RhymeCandidate
import dev.nymph.lyrics.model.RhymeOrder
import dev.nymph.lyrics.model.RhymeSuggestRequest
import dev.nymph.lyrics.model.error.ApiResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

/** The rhyme sheet's "Rhyme" / "Rhyme, then meaning" toggle: request field, refetch, headers, note. */
@OptIn(ExperimentalCoroutinesApi::class)
class RhymeViewModelOrderTest {
    @JvmField
    @RegisterExtension
    val main = MainDispatcherExtension()

    private val cat = catSnippet("s1")

    private fun c(id: String, score: Double, band: RhymeBand?, similarity: Double? = null): RhymeCandidate =
        candidate(id).copy(score = score, band = band, similarity = similarity)

    /** Meaning answers banded rows (two pages' worth); rhyme answers plain rows. */
    private fun answer(request: RhymeSuggestRequest, meaningAvailable: Boolean = true): ApiResult.Success<dev.nymph.lyrics.model.RhymeSuggestResponse> {
        val meaning = request.order == RhymeOrder.MEANING && meaningAvailable
        val rows = if (meaning) {
            listOf(c("p1", 0.97, RhymeBand.PERFECT, 0.8), c("p2", 0.96, RhymeBand.PERFECT, 0.1), c("n1", 0.8, RhymeBand.NEAR, 0.5))
        } else {
            listOf(c("p1", 0.97, RhymeBand.PERFECT), c("p2", 0.96, RhymeBand.PERFECT), c("n1", 0.8, RhymeBand.NEAR))
        }
        val body = response(*rows.toTypedArray())
        return ApiResult.Success(body.copy(orderApplied = if (meaning) RhymeOrder.MEANING else RhymeOrder.RHYME))
    }

    private fun TestScope.openVm(nlp: FakeNlpRepository, store: InMemoryRhymeOrderStore = InMemoryRhymeOrderStore()): RhymeViewModel {
        val vm = RhymeViewModel(FakeCanvasPort(listOf(cat), setOf(cat.id)), nlp, FakeSnippetRepository(), orders = store)
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
    fun `rhyme order sends no order and shows plain rows`() = runTest(main.dispatcher) {
        val nlp = FakeNlpRepository({ answer(it) })
        val vm = openVm(nlp)
        assertNull(nlp.requests.single().order)
        assertEquals(OrderPresentation.PLAIN, vm.state.value.presentation)
        assertEquals(listOf("p1", "p2", "n1"), vm.rendered())
    }

    @Test
    fun `switching to meaning refetches from the first page and shows band headers`() = runTest(main.dispatcher) {
        val nlp = FakeNlpRepository({ answer(it) })
        val vm = openVm(nlp)
        vm.setOrder(RhymeOrder.MEANING)
        settle()
        val last = nlp.requests.last()
        assertEquals(RhymeOrder.MEANING, last.order)
        assertEquals(0, last.offset)
        assertEquals(2, nlp.requests.size)
        assertEquals(OrderPresentation.BANDED, vm.state.value.presentation)
        assertEquals(listOf("[PERFECT]", "p1", "p2", "[NEAR]", "n1"), vm.rendered())
    }

    @Test
    fun `the choice is saved on the device and a stored meaning applies on open`() = runTest(main.dispatcher) {
        val store = InMemoryRhymeOrderStore()
        val nlp = FakeNlpRepository({ answer(it) })
        val vm = openVm(nlp, store)
        vm.setOrder(RhymeOrder.MEANING)
        settle()
        assertEquals(RhymeOrder.MEANING, store.order.first())

        val nlp2 = FakeNlpRepository({ answer(it) })
        openVm(nlp2, InMemoryRhymeOrderStore(RhymeOrder.MEANING))
        assertEquals(RhymeOrder.MEANING, nlp2.requests.single().order)
    }

    @Test
    fun `meaning asked but rhyme applied shows the note and no headers`() = runTest(main.dispatcher) {
        val nlp = FakeNlpRepository({ answer(it, meaningAvailable = false) })
        val vm = openVm(nlp, InMemoryRhymeOrderStore(RhymeOrder.MEANING))
        assertEquals(OrderPresentation.MEANING_UNAVAILABLE, vm.state.value.presentation)
        assertEquals(listOf("p1", "p2", "n1"), vm.rendered())
    }

    @Test
    fun `back to rhyme drops the headers`() = runTest(main.dispatcher) {
        val nlp = FakeNlpRepository({ answer(it) })
        val vm = openVm(nlp, InMemoryRhymeOrderStore(RhymeOrder.MEANING))
        vm.setOrder(RhymeOrder.RHYME)
        settle()
        assertNull(nlp.requests.last().order)
        assertEquals(OrderPresentation.PLAIN, vm.state.value.presentation)
    }

    @Test
    fun `more pages keep the meaning order and continue the band`() = runTest(main.dispatcher) {
        val page1 = (1..40).map { c("p$it", 0.97, RhymeBand.PERFECT, 0.5) }
        val page2 = listOf(c("p41", 0.96, RhymeBand.PERFECT, 0.4), c("s1", 0.9, RhymeBand.STRONG, 0.9))
        val nlp = FakeNlpRepository({ request ->
            val rows = if (request.offset == 0) page1 else page2
            ApiResult.Success(response(*rows.toTypedArray(), total = 42).copy(orderApplied = RhymeOrder.MEANING))
        })
        val vm = openVm(nlp, InMemoryRhymeOrderStore(RhymeOrder.MEANING))
        vm.loadMore()
        runCurrent()
        assertEquals(RhymeOrder.MEANING, nlp.requests.last().order)
        assertEquals(40, nlp.requests.last().offset)
        val headers = vm.state.value.items.filterIsInstance<BandedItem.Header>().map { it.band }
        assertEquals(listOf(RhymeBand.PERFECT, RhymeBand.STRONG), headers)
    }

    @Test
    fun `a candidate without a band is banded by its score`() = runTest(main.dispatcher) {
        val nlp = FakeNlpRepository({
            ApiResult.Success(response(c("x", 0.86, band = null)).copy(orderApplied = RhymeOrder.MEANING))
        })
        val vm = openVm(nlp, InMemoryRhymeOrderStore(RhymeOrder.MEANING))
        assertEquals(listOf("[STRONG]", "x"), vm.rendered())
    }
}
