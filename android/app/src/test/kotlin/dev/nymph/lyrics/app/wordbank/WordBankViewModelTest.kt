package dev.nymph.lyrics.app.wordbank

import app.cash.turbine.test
import dev.nymph.lyrics.app.MainDispatcherExtension
import dev.nymph.lyrics.logic.tags.TagExprParse
import dev.nymph.lyrics.logic.tags.TagFilter
import dev.nymph.lyrics.logic.tags.TagToggle
import dev.nymph.lyrics.logic.tags.parseTagExpression
import dev.nymph.lyrics.logic.wordbank.MAX_RELATED_RETRIES
import dev.nymph.lyrics.logic.wordbank.WordBankError
import dev.nymph.lyrics.model.BankFamiliesResponse
import dev.nymph.lyrics.model.BankFamily
import dev.nymph.lyrics.model.BankRhymeAnchor
import dev.nymph.lyrics.model.BankRhymesRequest
import dev.nymph.lyrics.model.BankWordId
import dev.nymph.lyrics.model.RelatedWord
import dev.nymph.lyrics.model.RelatedWordsResponse
import dev.nymph.lyrics.model.SnippetType
import dev.nymph.lyrics.model.Tag
import dev.nymph.lyrics.model.TagId
import dev.nymph.lyrics.model.TagLinkOp
import dev.nymph.lyrics.model.UserTag
import dev.nymph.lyrics.model.error.ApiError
import dev.nymph.lyrics.model.error.ApiResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class WordBankViewModelTest {
    @JvmField
    @RegisterExtension
    val main = MainDispatcherExtension()

    private val dark = Tag(TagId("t-dark"), "dark", "#000000")
    private val love = Tag(TagId("t-love"), "love", "#ff0000")
    private val night = bankWord("night", dark)
    private val heart = bankWord("heart", love)
    private val light = bankWord("light", love, dark)
    private val rain = bankWord("rain")

    private val repo = FakeWordBankRepository(listOf(night, heart, light, rain)).apply { knownTags = mapOf(dark.id to dark, love.id to love) }
    private val canvas = FakeWordBankCanvasPort()

    private fun TestScope.vm(): WordBankViewModel {
        val vm = WordBankViewModel(repo, canvas)
        runCurrent()
        return vm
    }

    private val WordBankViewModel.s get() = state.value

    private fun filter(text: String) = (parseTagExpression(text) as TagExprParse.Valid).filter

    private fun userTag(tag: Tag) = UserTag(tag.id, tag.name, tag.color, 0, "")

    // ---- Loading, filter, count ----

    @Test
    fun `loads the whole bank`() = runTest(main.dispatcher) {
        val vm = vm()
        assertEquals(listOf(night, heart, light, rain), vm.s.allWords)
        assertEquals("4 words (4 shown)", vm.s.countLabel)
    }

    @Test
    fun `a failed first load is a typed error`() = runTest(main.dispatcher) {
        repo.listResult = { ApiResult.Failure(ApiError.Network(IOException("x"))) }
        val vm = vm()
        assertEquals(Remote.Failed(WordBankError.Network("x")), vm.s.words)
        repo.listResult = { ApiResult.Success(repo.words) }
        vm.reload()
        runCurrent()
        assertEquals(4, vm.s.allWords.size)
    }

    @Test
    fun `the filter narrows the list locally and prunes the selection`() = runTest(main.dispatcher) {
        val vm = vm()
        vm.toggleSelected(night.id)
        vm.toggleSelected(heart.id)
        vm.setTagFilter(filter("love"))
        assertEquals(listOf(heart, light), vm.s.visible)
        assertEquals("4 words (2 shown)", vm.s.countLabel)
        assertEquals(setOf(heart.id), vm.s.selection)
        vm.setTagFilter(filter("!type:verse"))
        assertEquals(4, vm.s.visible.size)
    }

    @Test
    fun `hidden words cannot be selected`() = runTest(main.dispatcher) {
        val vm = vm()
        vm.setTagFilter(filter("love"))
        vm.toggleSelected(rain.id)
        vm.select(night.id)
        vm.selectAll(listOf(night.id, light.id))
        assertEquals(setOf(light.id), vm.s.selection)
        vm.toggleSelected(light.id)
        assertTrue(vm.s.selection.isEmpty())
    }

    // ---- Add box ----

    @Test
    fun `submit splits, sends tags, reports and clears`() = runTest(main.dispatcher) {
        val vm = vm()
        vm.toggleAddTag(dark.id)
        vm.setAddText("snow, Night\nneon lights")
        vm.messages.test {
            vm.submitAdd()
            runCurrent()
            assertEquals("Added 2, 1 already in bank", awaitItem())
        }
        assertEquals(listOf("snow", "Night", "neon lights") to listOf(dark.id), repo.adds.single())
        assertEquals("", vm.s.add.text)
        assertEquals(listOf("snow", "neon lights"), vm.s.allWords.take(2).map { it.text })
        assertEquals(6, vm.s.allWords.size)
        assertEquals(listOf(dark.id), vm.s.add.tagIds)
    }

    @Test
    fun `a locally invalid word is shown and nothing is sent`() = runTest(main.dispatcher) {
        val vm = vm()
        vm.setAddText("snow, rain2")
        vm.submitAdd()
        runCurrent()
        assertTrue(repo.adds.isEmpty())
        assertEquals("Word 2 (\"rain2\"): \"2\" is not allowed: use letters, apostrophes, hyphens and spaces.", vm.s.add.error)
        vm.setAddText("snow")
        assertNull(vm.s.add.error)
    }

    @Test
    fun `an empty submit does nothing`() = runTest(main.dispatcher) {
        val vm = vm()
        vm.setAddText(" ,\n ")
        vm.submitAdd()
        runCurrent()
        assertTrue(repo.adds.isEmpty())
        assertNull(vm.s.add.error)
    }

    @Test
    fun `a server invalid_word names the word by index`() = runTest(main.dispatcher) {
        repo.addResult = { _, _ -> ApiResult.Failure(ApiError.Validation(422, "bad", "invalid_word", index = 1)) }
        val vm = vm()
        vm.setAddText("snow, sleet")
        vm.submitAdd()
        runCurrent()
        assertEquals("The server rejected word 2 (\"sleet\").", vm.s.add.error)
        assertFalse(vm.s.add.busy)
        assertEquals("snow, sleet", vm.s.add.text)
    }

    @Test
    fun `a deleted tag leaves the add box chips`() = runTest(main.dispatcher) {
        val vm = vm()
        vm.toggleAddTag(dark.id)
        vm.toggleAddTag(love.id)
        vm.toggleAddTag(love.id)
        assertEquals(listOf(dark.id), vm.s.add.tagIds)
        vm.onTagDeleted(dark.id)
        assertTrue(vm.s.add.tagIds.isEmpty())
        assertTrue(vm.s.allWords.none { w -> w.tags.any { it.id == dark.id } })
    }

    // ---- Rename ----

    @Test
    fun `rename validates, allows a case change and stores the result`() = runTest(main.dispatcher) {
        val vm = vm()
        vm.startRename(night.id)
        assertEquals("night", vm.s.rename?.text)
        vm.setRenameText("night 9")
        vm.submitRename()
        assertEquals("\"9\" is not allowed: use letters, apostrophes, hyphens and spaces.", vm.s.rename?.error)
        vm.setRenameText("  Night ")
        vm.submitRename()
        runCurrent()
        assertEquals(night.id to "Night", repo.renames.single())
        assertNull(vm.s.rename)
        assertEquals("Night", vm.s.allWords.first().text)
    }

    @Test
    fun `an unchanged rename sends nothing`() = runTest(main.dispatcher) {
        val vm = vm()
        vm.startRename(night.id)
        vm.submitRename()
        runCurrent()
        assertTrue(repo.renames.isEmpty())
        assertNull(vm.s.rename)
    }

    @Test
    fun `a taken name stays inline and a vanished word leaves the list`() = runTest(main.dispatcher) {
        repo.renameResult = { _, _ -> ApiResult.Failure(ApiError.Server(409, "dup", "word_exists")) }
        val vm = vm()
        vm.startRename(night.id)
        vm.setRenameText("rain")
        vm.submitRename()
        runCurrent()
        assertEquals("You already have that word.", vm.s.rename?.error)
        repo.renameResult = { _, _ -> ApiResult.Failure(ApiError.NotFound("gone", "word_not_found")) }
        vm.setRenameText("drizzle")
        vm.messages.test {
            vm.submitRename()
            runCurrent()
            assertEquals("That word is no longer in your bank.", awaitItem())
        }
        assertNull(vm.s.rename)
        assertTrue(vm.s.allWords.none { it.id == night.id })
    }

    // ---- Delete ----

    @Test
    fun `delete removes the selection`() = runTest(main.dispatcher) {
        val vm = vm()
        vm.toggleSelected(heart.id)
        vm.toggleSelected(rain.id)
        vm.deleteSelected()
        runCurrent()
        assertEquals(listOf(heart.id, rain.id), repo.deletes.single())
        assertEquals(listOf(night, light), vm.s.allWords)
        assertTrue(vm.s.selection.isEmpty())
        assertFalse(vm.s.deleting)
    }

    @Test
    fun `a failed delete keeps the words and says why`() = runTest(main.dispatcher) {
        repo.deleteResult = { ApiResult.Failure(ApiError.Network(IOException("x"))) }
        val vm = vm()
        vm.toggleSelected(heart.id)
        vm.messages.test {
            vm.deleteSelected()
            runCurrent()
            assertEquals("Can't reach the server. Check your connection.", awaitItem())
        }
        assertEquals(4, vm.s.allWords.size)
        assertEquals(setOf(heart.id), vm.s.selection)
    }

    @Test
    fun `large deletes are chunked at 500`() = runTest(main.dispatcher) {
        repo.words = (0 until 1200).map { bankWord("w" + it.toString().map { c -> 'a' + (c - '0') }.joinToString(""), id = "w$it") }
        val vm = vm()
        vm.selectAll(repo.words.map { it.id })
        vm.deleteSelected()
        runCurrent()
        assertEquals(listOf(500, 500, 200), repo.deletes.map { it.size })
        assertTrue(vm.s.allWords.isEmpty())
    }

    // ---- Tagging (TagTarget) ----

    @Test
    fun `picker links come from the words`() = runTest(main.dispatcher) {
        val vm = vm()
        assertEquals(mapOf(night.id to setOf(dark.id), rain.id to emptySet()), vm.currentLinks(setOf(night.id, rain.id)))
    }

    @Test
    fun `a toggle uses the bulk route and stores the updated words`() = runTest(main.dispatcher) {
        val vm = vm()
        assertNull(vm.applyToggle(userTag(love), TagToggle.Link(listOf(night.id, rain.id))))
        assertEquals(Triple(listOf(night.id, rain.id), love.id, TagLinkOp.LINK), repo.tagCalls.single())
        assertEquals(listOf(dark, love), vm.s.allWords.first { it.id == night.id }.tags)
        assertNull(vm.applyToggle(userTag(love), TagToggle.Unlink(listOf(night.id))))
        assertEquals(listOf(dark), vm.s.allWords.first { it.id == night.id }.tags)
    }

    @Test
    fun `a toggle on a vanished word reports and reloads`() = runTest(main.dispatcher) {
        repo.setTagResult = { _, _, _ -> ApiResult.Failure(ApiError.NotFound("x", "word_not_found")) }
        val vm = vm()
        repo.words = listOf(night)
        assertEquals("That word is no longer in your bank.", vm.applyToggle(userTag(love), TagToggle.Link(listOf(heart.id))))
        runCurrent()
        assertEquals(listOf(night), vm.s.allWords)
    }

    @Test
    fun `a tag rename refreshes pills`() = runTest(main.dispatcher) {
        val vm = vm()
        vm.onTagEdited(Tag(love.id, "amor", "#00ff00"))
        assertEquals(listOf("amor"), vm.s.allWords.first { it.id == heart.id }.tags.map { it.name })
        assertEquals(listOf("amor", "dark"), vm.s.allWords.first { it.id == light.id }.tags.map { it.name })
    }

    // ---- Rhymes ----

    @Test
    fun `the rhymes tab loads families of the filtered bank`() = runTest(main.dispatcher) {
        val family = BankFamily("night", listOf("AY", "T"), listOf(night, light), 0.9)
        repo.familiesResult = { ApiResult.Success(BankFamiliesResponse(listOf(family), listOf(rain))) }
        val vm = vm()
        vm.setTagFilter(filter("dark"))
        vm.setTab(WordBankTab.RHYMES)
        runCurrent()
        assertEquals("dark", repo.familyRequests.single().tagFilter)
        assertEquals(0.8, repo.familyRequests.single().minScore)
        assertEquals(Remote.Loaded(BankFamiliesResponse(listOf(family), listOf(rain))), vm.s.families.result)
        vm.selectAll(family.words.map { it.id })
        assertEquals(setOf(night.id, light.id), vm.s.selection)
    }

    @Test
    fun `the min score slider queries on release, and a filter change reloads`() = runTest(main.dispatcher) {
        val vm = vm()
        vm.setTab(WordBankTab.RHYMES)
        runCurrent()
        vm.setFamiliesMinScore(0.65)
        assertEquals(1, repo.familyRequests.size)
        vm.loadFamilies()
        runCurrent()
        assertEquals(0.65, repo.familyRequests.last().minScore)
        assertNull(repo.familyRequests.last().tagFilter)
        vm.setTagFilter(filter("love"))
        runCurrent()
        assertEquals("love", repo.familyRequests.last().tagFilter)
        assertEquals(3, repo.familyRequests.size)
    }

    @Test
    fun `too many words is a typed failure`() = runTest(main.dispatcher) {
        repo.familiesResult = { ApiResult.Failure(ApiError.Validation(422, "x", "too_many_words")) }
        val vm = vm()
        vm.setTab(WordBankTab.RHYMES)
        runCurrent()
        assertEquals(Remote.Failed(WordBankError.TooManyWords), vm.s.families.result)
    }

    @Test
    fun `rhymes with one selected word or typed text`() = runTest(main.dispatcher) {
        val vm = vm()
        vm.setRhymeView(RhymeView.RHYMES_WITH)
        runCurrent()
        assertTrue(vm.s.rhymesWith.needsAnchor)
        assertTrue(repo.rhymeRequests.isEmpty())
        vm.toggleSelected(night.id)
        vm.loadRhymesWith()
        runCurrent()
        assertEquals(BankRhymesRequest.of(BankRhymeAnchor.Word(night.id)), repo.rhymeRequests.last())
        vm.toggleSelected(heart.id)
        vm.setRhymesTyped("moon")
        vm.setRhymesMinScore(0.3)
        vm.setTagFilter(filter("love | dark"))
        vm.loadRhymesWith()
        runCurrent()
        assertEquals(BankRhymesRequest.of(BankRhymeAnchor.Text("moon"), tagFilter = "love | dark", minScore = 0.3), repo.rhymeRequests.last())
        assertFalse(vm.s.rhymesWith.needsAnchor)
    }

    // ---- Related ----

    @Test
    fun `related results, plus and in bank`() = runTest(main.dispatcher) {
        repo.relatedResults += ApiResult.Success(RelatedWordsResponse("sea", listOf(RelatedWord("ocean", 0.8), RelatedWord("rain", 0.6, rain.id))))
        val vm = vm()
        vm.toggleAddTag(love.id)
        vm.setRelatedQuery("  sea ")
        vm.searchRelated()
        runCurrent()
        assertEquals("sea", repo.relatedQueries.single())
        vm.addRelated("ocean")
        runCurrent()
        assertEquals(listOf("ocean") to listOf(love.id), repo.adds.single())
        val status = vm.s.related.status as RelatedStatus.Loaded
        assertEquals(listOf(BankWordId("w-ocean"), rain.id), status.response.results.map { it.bankWordId })
        assertTrue(vm.s.related.adding.isEmpty())
    }

    @Test
    fun `warming retries after Retry-After, at most ten times`() = runTest(main.dispatcher) {
        repeat(MAX_RELATED_RETRIES + 1) {
            repo.relatedResults += ApiResult.Failure(ApiError.Server(503, "w", "related_words_warming", retryAfterSeconds = 2))
        }
        val vm = vm()
        vm.setRelatedQuery("sea")
        vm.searchRelated()
        runCurrent()
        assertEquals(RelatedStatus.Warming(0), vm.s.related.status)
        advanceTimeBy(1_999)
        runCurrent()
        assertEquals(1, repo.relatedQueries.size)
        advanceTimeBy(2)
        runCurrent()
        assertEquals(2, repo.relatedQueries.size)
        assertEquals(RelatedStatus.Warming(1), vm.s.related.status)
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(MAX_RELATED_RETRIES + 1, repo.relatedQueries.size)
        assertEquals(RelatedStatus.Failed(WordBankError.RelatedWarming(2)), vm.s.related.status)
    }

    @Test
    fun `warming then ready shows results`() = runTest(main.dispatcher) {
        repo.relatedResults += ApiResult.Failure(ApiError.Server(503, "w", "related_words_warming", retryAfterSeconds = 10))
        repo.relatedResults += ApiResult.Success(RelatedWordsResponse("sea", listOf(RelatedWord("ocean", 0.8))))
        val vm = vm()
        vm.setRelatedQuery("sea")
        vm.searchRelated()
        advanceTimeBy(10_001)
        runCurrent()
        assertEquals(listOf("ocean"), (vm.s.related.status as RelatedStatus.Loaded).response.results.map { it.text })
    }

    @Test
    fun `unavailable and invalid queries`() = runTest(main.dispatcher) {
        repo.relatedResults += ApiResult.Failure(ApiError.Server(503, "x", "related_words_unavailable"))
        val vm = vm()
        vm.setRelatedQuery("sea")
        vm.searchRelated()
        runCurrent()
        assertEquals(RelatedStatus.Failed(WordBankError.RelatedUnavailable), vm.s.related.status)
        assertEquals("Related words are unavailable on this server", WordBankError.RelatedUnavailable.userMessage())
        vm.setRelatedQuery("   ")
        vm.searchRelated()
        assertEquals(RelatedStatus.Invalid("Type a word, phrase or theme."), vm.s.related.status)
        assertEquals(1, repo.relatedQueries.size)
    }

    @Test
    fun `deleting a word clears its in bank mark`() = runTest(main.dispatcher) {
        repo.relatedResults += ApiResult.Success(RelatedWordsResponse("sea", listOf(RelatedWord("rain", 0.6, rain.id))))
        val vm = vm()
        vm.setRelatedQuery("sea")
        vm.searchRelated()
        runCurrent()
        vm.toggleSelected(rain.id)
        vm.deleteSelected()
        runCurrent()
        assertNull((vm.s.related.status as RelatedStatus.Loaded).response.results.single().bankWordId)
    }

    // ---- Draft ----

    @Test
    fun `make snippet drafts the selection in list order and creates it on the canvas`() = runTest(main.dispatcher) {
        val vm = vm()
        assertFalse(vm.s.canMakeSnippet)
        vm.toggleSelected(rain.id)
        vm.toggleSelected(night.id)
        vm.toggleSelected(light.id)
        assertTrue(vm.s.canMakeSnippet)
        vm.openDraft()
        assertEquals(DraftState("night\nlight\nrain", SnippetType.FREESTYLE), vm.s.draft)
        vm.setDraftText("night light\nrain")
        vm.setDraftType(SnippetType.HOOK)
        vm.messages.test {
            vm.createDraft()
            runCurrent()
            assertEquals("Snippet created on the canvas", awaitItem())
        }
        assertEquals(listOf("night light\nrain" to SnippetType.HOOK), canvas.created)
        assertNull(vm.s.draft)
        assertEquals(4, vm.s.allWords.size)
    }

    @Test
    fun `a failed create keeps the draft and cancel discards it`() = runTest(main.dispatcher) {
        canvas.result = { _, _ -> DraftCreateResult.Failed(ApiError.Network(IOException("x"))) }
        val vm = vm()
        vm.toggleSelected(rain.id)
        vm.openDraft()
        vm.createDraft()
        runCurrent()
        assertEquals("Can't reach the server. Check your connection.", vm.s.draft?.error)
        assertFalse(vm.s.draft!!.busy)
        canvas.result = { _, _ -> DraftCreateResult.CanvasNotLoaded }
        vm.createDraft()
        runCurrent()
        assertEquals("The canvas has not loaded yet. Go back and try again.", vm.s.draft?.error)
        vm.cancelDraft()
        assertNull(vm.s.draft)
    }

    @Test
    fun `an empty draft is not created`() = runTest(main.dispatcher) {
        val vm = vm()
        vm.toggleSelected(rain.id)
        vm.openDraft()
        vm.setDraftText("  \n ")
        vm.createDraft()
        runCurrent()
        assertTrue(canvas.created.isEmpty())
        assertEquals("The draft is empty.", vm.s.draft?.error)
    }

    @Test
    fun `everything filter sends no tag_filter`() = runTest(main.dispatcher) {
        val vm = vm()
        vm.setTagFilter(TagFilter.Everything)
        vm.setTab(WordBankTab.RHYMES)
        runCurrent()
        assertNull(repo.familyRequests.single().tagFilter)
    }
}
