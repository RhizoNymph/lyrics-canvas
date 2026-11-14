package dev.nymph.lyrics.app.rhyme.pattern

import dev.nymph.lyrics.app.MainDispatcherExtension
import dev.nymph.lyrics.app.fakes.FakeSnippetRepository
import dev.nymph.lyrics.app.rhyme.FakeCanvasPort
import dev.nymph.lyrics.app.rhyme.order.InMemoryRhymeOrderStore
import dev.nymph.lyrics.logic.rhyme.OrderPresentation
import dev.nymph.lyrics.logic.rhyme.RhymeSources
import dev.nymph.lyrics.logic.rhyme.pattern.SlotId
import dev.nymph.lyrics.logic.tags.ExpressionField
import dev.nymph.lyrics.model.PatternSuggestRequest
import dev.nymph.lyrics.model.RhymeBand
import dev.nymph.lyrics.model.RhymeOrder
import dev.nymph.lyrics.model.error.ApiResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

/** The pattern columns' order toggle: request field per column, refetch, applied order kept per session. */
@OptIn(ExperimentalCoroutinesApi::class)
class PatternViewModelOrderTest {
    @JvmField
    @RegisterExtension
    val main = MainDispatcherExtension()

    private val song = PatternFx.song()
    private val a = song.id
    private val seed = PatternSeed(0.6, RhymeSources(), includeRare = false, tagFilter = ExpressionField())

    /** Echoes the fixture answer; meaning is applied unless [available] is false. */
    private fun answering(available: Boolean = true) = FakeRhymePatternRepository(
        onSuggest = { request: PatternSuggestRequest ->
            val body = PatternFx.suggest(request)
            val meaning = request.order == RhymeOrder.MEANING && available
            ApiResult.Success(
                body.copy(
                    orderApplied = if (meaning) RhymeOrder.MEANING else RhymeOrder.RHYME,
                    slots = body.slots.map { s -> s.copy(candidates = s.candidates.map { it.copy(band = RhymeBand.NEAR, similarity = if (meaning) 0.4 else null) }) },
                ),
            )
        },
    )

    private fun TestScope.entered(repo: FakeRhymePatternRepository, store: InMemoryRhymeOrderStore = InMemoryRhymeOrderStore()): PatternViewModel {
        val vm = PatternViewModel(FakeCanvasPort(listOf(song), setOf(a)), repo, FakeSnippetRepository(), orders = store)
        vm.toggleLinePick(a)
        vm.onLineTap(a, 1, seed)
        vm.onLineTap(a, 3, seed)
        advanceUntilIdle()
        return vm
    }

    private val PatternViewModel.session: PatternSession get() = state.value.session!!

    @Test
    fun `rhyme order sends no order field`() = runTest(main.dispatcher) {
        val repo = answering()
        val vm = entered(repo)
        assertNull(repo.suggests.single().order)
        assertEquals(OrderPresentation.PLAIN, vm.session.ordering.presentation)
    }

    @Test
    fun `switching to meaning refetches every column from the first page`() = runTest(main.dispatcher) {
        val repo = answering()
        val vm = entered(repo)
        vm.setOrder(RhymeOrder.MEANING)
        advanceUntilIdle()
        val last = repo.suggests.last()
        assertEquals(2, repo.suggests.size)
        assertEquals(RhymeOrder.MEANING, last.order)
        assertEquals(emptyMap<String, Int>(), last.offsets)
        assertEquals(OrderPresentation.BANDED, vm.session.ordering.presentation)
        assertEquals(RhymeOrder.MEANING, vm.state.value.params.order)
    }

    @Test
    fun `a column's next page keeps the meaning order`() = runTest(main.dispatcher) {
        val repo = answering()
        val vm = entered(repo, InMemoryRhymeOrderStore(RhymeOrder.MEANING))
        vm.loadMoreSlot(SlotId("s0"))
        advanceUntilIdle()
        assertEquals(RhymeOrder.MEANING, repo.suggests.last().order)
        assertEquals(mapOf("s0" to 30), repo.suggests.last().offsets)
    }

    @Test
    fun `meaning unavailable is noted`() = runTest(main.dispatcher) {
        val vm = entered(answering(available = false), InMemoryRhymeOrderStore(RhymeOrder.MEANING))
        assertEquals(OrderPresentation.MEANING_UNAVAILABLE, vm.session.ordering.presentation)
    }

    @Test
    fun `the choice is remembered on the device`() = runTest(main.dispatcher) {
        val store = InMemoryRhymeOrderStore()
        val vm = entered(answering(), store)
        vm.setOrder(RhymeOrder.MEANING)
        advanceUntilIdle()
        assertEquals(RhymeOrder.MEANING, store.order.first())
    }
}
