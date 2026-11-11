package dev.nymph.lyrics.app.canvaslist.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.nymph.lyrics.data.repository.SearchRepository
import dev.nymph.lyrics.logic.search.GlobalSearchInput
import dev.nymph.lyrics.logic.search.SearchError
import dev.nymph.lyrics.logic.search.SearchQuery
import dev.nymph.lyrics.logic.search.checkGlobalSearch
import dev.nymph.lyrics.logic.search.toSearchError
import dev.nymph.lyrics.model.CanvasSearchHit
import dev.nymph.lyrics.model.SnippetSearchHit
import dev.nymph.lyrics.model.error.ApiResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Quiet period after the last keystroke before a search across canvases is sent. */
const val GLOBAL_SEARCH_DEBOUNCE_MS: Long = 250

/** What the canvas list shows under its search field. */
sealed interface GlobalSearchResults {
    /** No query: the normal canvas list. */
    data object Off : GlobalSearchResults

    data object Loading : GlobalSearchResults

    /** Hits for [query] (its terms highlight the excerpts); both empty is "No matches". */
    data class Loaded(
        val query: SearchQuery,
        val canvases: List<CanvasSearchHit>,
        val snippets: List<SnippetSearchHit>,
    ) : GlobalSearchResults {
        val isEmpty: Boolean get() = canvases.isEmpty() && snippets.isEmpty()
    }

    data class Failed(val error: SearchError) : GlobalSearchResults
}

data class GlobalSearchUiState(val text: String = "", val results: GlobalSearchResults = GlobalSearchResults.Off)

/** One debounced request: the checked input plus a retry generation (a retry re-sends the same input). */
private data class Request(val input: GlobalSearchInput, val attempt: Int)

/**
 * The canvas list's search across canvases (`GET /api/search`): debounced [GLOBAL_SEARCH_DEBOUNCE_MS]
 * (a blank field returns to the list at once), a newer query cancels an in-flight one, an over-long
 * query is refused locally, and failures are typed ([SearchError]).
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class GlobalSearchViewModel(
    private val repo: SearchRepository,
    private val debounceMs: Long = GLOBAL_SEARCH_DEBOUNCE_MS,
) : ViewModel() {
    private val _state = MutableStateFlow(GlobalSearchUiState())
    val state: StateFlow<GlobalSearchUiState> = _state.asStateFlow()

    private val text = MutableStateFlow("")
    private val attempts = MutableStateFlow(0)

    init {
        viewModelScope.launch {
            combine(text, attempts) { t, a -> Request(checkGlobalSearch(t), a) }
                .distinctUntilChanged()
                .debounce { if (it.input is GlobalSearchInput.Blank) 0L else debounceMs }
                .collectLatest(::run)
        }
    }

    fun edit(value: String) {
        text.value = value
        _state.update { it.copy(text = value) }
    }

    fun clear() = edit("")

    /** Send the current query again (after a failure). */
    fun retry() = attempts.update { it + 1 }

    private suspend fun run(request: Request) {
        when (val input = request.input) {
            GlobalSearchInput.Blank -> setResults(GlobalSearchResults.Off)
            is GlobalSearchInput.TooLong -> setResults(GlobalSearchResults.Failed(SearchError.InvalidQuery(null)))
            is GlobalSearchInput.Valid -> {
                setResults(GlobalSearchResults.Loading)
                val results = when (val result = repo.search(input.query)) {
                    is ApiResult.Success -> GlobalSearchResults.Loaded(SearchQuery.parse(input.query), result.value.canvases, result.value.snippets)
                    is ApiResult.Failure -> GlobalSearchResults.Failed(toSearchError(result.error))
                }
                setResults(results)
            }
        }
    }

    private fun setResults(results: GlobalSearchResults) = _state.update { it.copy(results = results) }
}
