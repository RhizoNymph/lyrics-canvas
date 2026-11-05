package dev.nymph.lyrics.app.wordbank

import dev.nymph.lyrics.logic.tags.TagFilter
import dev.nymph.lyrics.logic.wordbank.WordBankError
import dev.nymph.lyrics.logic.wordbank.countLabel
import dev.nymph.lyrics.logic.wordbank.selectedInOrder
import dev.nymph.lyrics.logic.wordbank.visibleWords
import dev.nymph.lyrics.model.BankFamiliesRequest
import dev.nymph.lyrics.model.BankFamiliesResponse
import dev.nymph.lyrics.model.BankRhymesRequest
import dev.nymph.lyrics.model.BankRhymesResponse
import dev.nymph.lyrics.model.BankWord
import dev.nymph.lyrics.model.BankWordId
import dev.nymph.lyrics.model.RelatedWordsResponse
import dev.nymph.lyrics.model.SnippetType
import dev.nymph.lyrics.model.TagId

/** A server-backed result. */
sealed interface Remote<out T> {
    data object Idle : Remote<Nothing>

    data object Loading : Remote<Nothing>

    data class Loaded<out T>(val value: T) : Remote<T>

    data class Failed(val error: WordBankError) : Remote<Nothing>
}

/** The screen's tabs; the selection is shared by all of them. */
enum class WordBankTab { WORDS, RHYMES, RELATED }

/** The two rhyme views. */
enum class RhymeView { FAMILIES, RHYMES_WITH }

data class AddBoxState(
    val text: String = "",
    /** Tags linked to every word added from the box (and from related "+"). */
    val tagIds: List<TagId> = emptyList(),
    val busy: Boolean = false,
    /** A local rule failure or the server's rejection, shown under the box. */
    val error: String? = null,
)

data class RenameSession(
    val id: BankWordId,
    val text: String,
    val busy: Boolean = false,
    val error: String? = null,
)

data class FamiliesState(
    val minScore: Double = BankFamiliesRequest.DEFAULT_MIN_SCORE,
    val result: Remote<BankFamiliesResponse> = Remote.Idle,
)

data class RhymesWithState(
    /** A typed anchor; when blank, the one selected word is the anchor. */
    val typed: String = "",
    val minScore: Double = BankRhymesRequest.DEFAULT_MIN_SCORE,
    val result: Remote<BankRhymesResponse> = Remote.Idle,
    /** Shown when there is no anchor to query. */
    val needsAnchor: Boolean = false,
)

/** The related search's progress. */
sealed interface RelatedStatus {
    data object Idle : RelatedStatus

    data object Loading : RelatedStatus

    /** The index is being built; [retries] retries were made so far. */
    data class Warming(val retries: Int) : RelatedStatus

    data class Loaded(val response: RelatedWordsResponse) : RelatedStatus

    /** A local refusal of the query text (empty, too long). */
    data class Invalid(val message: String) : RelatedStatus

    data class Failed(val error: WordBankError) : RelatedStatus
}

data class RelatedState(
    val query: String = "",
    val status: RelatedStatus = RelatedStatus.Idle,
    /** Result texts whose "+" request is in flight. */
    val adding: Set<String> = emptySet(),
)

data class DraftState(
    val text: String,
    val type: SnippetType = SnippetType.FREESTYLE,
    val busy: Boolean = false,
    val error: String? = null,
)

/** The whole bank is loaded once; filtering is local with the shared evaluator. */
data class WordBankUiState(
    val words: Remote<List<BankWord>> = Remote.Loading,
    val filter: TagFilter = TagFilter.Everything,
    /** Always a subset of the visible words. */
    val selection: Set<BankWordId> = emptySet(),
    val tab: WordBankTab = WordBankTab.WORDS,
    val rhymeView: RhymeView = RhymeView.FAMILIES,
    val add: AddBoxState = AddBoxState(),
    val rename: RenameSession? = null,
    val deleting: Boolean = false,
    val families: FamiliesState = FamiliesState(),
    val rhymesWith: RhymesWithState = RhymesWithState(),
    val related: RelatedState = RelatedState(),
    val draft: DraftState? = null,
) {
    val allWords: List<BankWord> get() = (words as? Remote.Loaded)?.value ?: emptyList()

    val visible: List<BankWord> get() = visibleWords(allWords, filter)

    val countLabel: String get() = countLabel(allWords.size, visible.size)

    val selectedWords: List<BankWord> get() = selectedInOrder(allWords, selection)

    val canMakeSnippet: Boolean get() = selection.isNotEmpty() && draft == null
}
