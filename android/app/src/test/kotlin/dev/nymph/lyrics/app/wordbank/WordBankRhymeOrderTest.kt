package dev.nymph.lyrics.app.wordbank

import dev.nymph.lyrics.app.MainDispatcherExtension
import dev.nymph.lyrics.app.rhyme.order.InMemoryRhymeOrderStore
import dev.nymph.lyrics.logic.rhyme.BandedItem
import dev.nymph.lyrics.logic.rhyme.OrderPresentation
import dev.nymph.lyrics.model.BankRhymeAnchor
import dev.nymph.lyrics.model.BankRhymeAnchorInfo
import dev.nymph.lyrics.model.BankRhymeMatch
import dev.nymph.lyrics.model.BankRhymesRequest
import dev.nymph.lyrics.model.BankRhymesResponse
import dev.nymph.lyrics.model.RhymeBand
import dev.nymph.lyrics.model.RhymeOrder
import dev.nymph.lyrics.model.error.ApiResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

/** The word bank's rhymes-with order toggle. */
@OptIn(ExperimentalCoroutinesApi::class)
class WordBankRhymeOrderTest {
    @JvmField
    @RegisterExtension
    val main = MainDispatcherExtension()

    private val spoon = bankWord("spoon")
    private val june = bankWord("june")
    private val tune = bankWord("tune")

    private fun answer(request: BankRhymesRequest, available: Boolean = true): ApiResult<BankRhymesResponse> {
        val meaning = request.order == RhymeOrder.MEANING && available
        val matches = listOf(
            BankRhymeMatch(spoon, 1.0, 2, RhymeBand.PERFECT, if (meaning) 0.6 else null),
            BankRhymeMatch(june, 0.96, 2, RhymeBand.PERFECT, if (meaning) 0.2 else null),
            BankRhymeMatch(tune, 0.8, 1, RhymeBand.NEAR, if (meaning) 0.9 else null),
        )
        return ApiResult.Success(BankRhymesResponse(BankRhymeAnchorInfo("moon"), matches, if (meaning) RhymeOrder.MEANING else RhymeOrder.RHYME))
    }

    private fun TestScope.vm(repo: FakeWordBankRepository, store: InMemoryRhymeOrderStore = InMemoryRhymeOrderStore()): WordBankViewModel {
        val vm = WordBankViewModel(repo, FakeWordBankCanvasPort(), store)
        runCurrent()
        vm.setTab(WordBankTab.RHYMES)
        vm.setRhymeView(RhymeView.RHYMES_WITH)
        vm.setRhymesTyped("moon")
        vm.loadRhymesWith()
        runCurrent()
        return vm
    }

    private fun WordBankViewModel.rendered() = state.value.rhymesWith.items.map {
        when (it) {
            is BandedItem.Header -> "[${it.band}]"
            is BandedItem.Row -> it.value.word.text
        }
    }

    @Test
    fun `rhyme order sends no order and shows plain rows`() = runTest(main.dispatcher) {
        val repo = FakeWordBankRepository(listOf(spoon, june, tune)).apply { rhymesResult = { answer(it) } }
        val vm = vm(repo)
        assertEquals(BankRhymesRequest.of(BankRhymeAnchor.Text("moon")), repo.rhymeRequests.last())
        assertNull(repo.rhymeRequests.last().order)
        assertEquals(listOf("spoon", "june", "tune"), vm.rendered())
    }

    @Test
    fun `the toggle refetches in meaning order with band headers and is remembered`() = runTest(main.dispatcher) {
        val store = InMemoryRhymeOrderStore()
        val repo = FakeWordBankRepository(listOf(spoon, june, tune)).apply { rhymesResult = { answer(it) } }
        val vm = vm(repo, store)
        vm.setRhymesOrder(RhymeOrder.MEANING)
        runCurrent()
        assertEquals(RhymeOrder.MEANING, repo.rhymeRequests.last().order)
        assertEquals(OrderPresentation.BANDED, vm.state.value.rhymesWith.presentation)
        assertEquals(listOf("[PERFECT]", "spoon", "june", "[NEAR]", "tune"), vm.rendered())
        assertEquals(RhymeOrder.MEANING, store.order.first())
    }

    @Test
    fun `a change from another screen refetches a shown list`() = runTest(main.dispatcher) {
        val store = InMemoryRhymeOrderStore()
        val repo = FakeWordBankRepository(listOf(spoon, june, tune)).apply { rhymesResult = { answer(it) } }
        vm(repo, store)
        store.save(RhymeOrder.MEANING)
        runCurrent()
        assertEquals(2, repo.rhymeRequests.size)
        assertEquals(RhymeOrder.MEANING, repo.rhymeRequests.last().order)
    }

    @Test
    fun `meaning unavailable shows the note and no headers`() = runTest(main.dispatcher) {
        val repo = FakeWordBankRepository(listOf(spoon, june, tune)).apply { rhymesResult = { answer(it, available = false) } }
        val vm = vm(repo, InMemoryRhymeOrderStore(RhymeOrder.MEANING))
        assertEquals(OrderPresentation.MEANING_UNAVAILABLE, vm.state.value.rhymesWith.presentation)
        assertEquals(listOf("spoon", "june", "tune"), vm.rendered())
    }

    @Test
    fun `nothing is fetched for an order change before the list was asked for`() = runTest(main.dispatcher) {
        val store = InMemoryRhymeOrderStore()
        val repo = FakeWordBankRepository(listOf(spoon))
        val vm = WordBankViewModel(repo, FakeWordBankCanvasPort(), store)
        runCurrent()
        vm.setRhymesOrder(RhymeOrder.MEANING)
        runCurrent()
        assertEquals(0, repo.rhymeRequests.size)
        assertEquals(RhymeOrder.MEANING, vm.state.value.rhymesWith.order)
    }
}
