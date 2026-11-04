package dev.nymph.lyrics.app.rhyme

import dev.nymph.lyrics.app.MainDispatcherExtension
import dev.nymph.lyrics.app.fakes.FakeNlpRepository
import dev.nymph.lyrics.app.fakes.FakeSnippetRepository
import dev.nymph.lyrics.app.fakes.candidate
import dev.nymph.lyrics.app.fakes.catSnippet
import dev.nymph.lyrics.app.fakes.response
import dev.nymph.lyrics.logic.tags.TagExprErrorCode
import dev.nymph.lyrics.model.SourceName
import dev.nymph.lyrics.model.error.ApiResult
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

/** "Only my lines matching": sent as `tag_filter`; a known-invalid expression never reaches the server. */
@OptIn(ExperimentalCoroutinesApi::class)
class RhymeViewModelTagFilterTest {
    @JvmField
    @RegisterExtension
    val main = MainDispatcherExtension()

    private val cat = catSnippet("s1")
    private val nlp = FakeNlpRepository({ ApiResult.Success(response(candidate("hat"))) })

    private fun TestScope.openVm(): RhymeViewModel {
        val vm = RhymeViewModel(FakeCanvasPort(listOf(cat), setOf(cat.id)), nlp, FakeSnippetRepository())
        vm.open()
        runCurrent()
        return vm
    }

    private fun TestScope.settle() {
        advanceTimeBy(151)
        runCurrent()
    }

    @Test
    fun `no filter sends no tag_filter`() = runTest(main.dispatcher) {
        openVm()
        assertNull(nlp.requests.single().tagFilter)
    }

    @Test
    fun `a valid expression is sent normalized`() = runTest(main.dispatcher) {
        val vm = openVm()
        vm.editTagFilter("love night", 10)
        settle()
        assertEquals("love & night", nlp.requests.last().tagFilter)
        assertEquals(10, vm.state.value.tagFilter.caret)
    }

    @Test
    fun `an invalid expression blocks the query and clears the results`() = runTest(main.dispatcher) {
        val vm = openVm()
        assertEquals(1, vm.state.value.rows.size)
        vm.editTagFilter("love &", 6)
        settle()
        assertEquals(1, nlp.requests.size)
        assertTrue(vm.state.value.rows.isEmpty())
        assertTrue(vm.state.value.tagFilterBlocked)
        assertEquals(TagExprErrorCode.UNEXPECTED_END, vm.state.value.tagFilter.error?.code)
        vm.editTagFilter("love & night", 12)
        settle()
        assertEquals(2, nlp.requests.size)
        assertFalse(vm.state.value.tagFilterBlocked)
    }

    @Test
    fun `caret moves alone do not re-query`() = runTest(main.dispatcher) {
        val vm = openVm()
        vm.editTagFilter("love", 4)
        settle()
        vm.editTagFilter("love", 1)
        settle()
        advanceUntilIdle()
        assertEquals(2, nlp.requests.size)
    }

    @Test
    fun `presets and the canvas filter replace the text, and insertion and completion edit it`() = runTest(main.dispatcher) {
        val vm = openVm()
        vm.setTagFilterText("night | dark")
        assertEquals(12, vm.state.value.tagFilter.caret)
        vm.insertTagFilterTag("love")
        assertEquals("night | dark & love", vm.state.value.tagFilter.text)
        vm.editTagFilter("ni", 2)
        val completion = vm.state.value.tagFilter.completion(listOf("night"))
        vm.acceptTagFilterSuggestion(completion, completion.suggestions.first())
        assertEquals("night ", vm.state.value.tagFilter.text)
        vm.clearTagFilter()
        assertEquals("", vm.state.value.tagFilter.text)
    }

    @Test
    fun `closing keeps the filter`() = runTest(main.dispatcher) {
        val vm = openVm()
        vm.setTagFilterText("love")
        vm.close()
        assertEquals("love", vm.state.value.tagFilter.text)
    }

    @Test
    fun `with notes off an invalid filter does not block`() = runTest(main.dispatcher) {
        val vm = openVm()
        vm.toggleSource(SourceName.NOTES)
        vm.editTagFilter("love &", 6)
        settle()
        assertNull(nlp.requests.last().tagFilter)
        assertFalse(vm.state.value.tagFilterBlocked)
        assertEquals(2, nlp.requests.size)
    }
}
