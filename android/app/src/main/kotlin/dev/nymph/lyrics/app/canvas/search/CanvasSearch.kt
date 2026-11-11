package dev.nymph.lyrics.app.canvas.search

import dev.nymph.lyrics.logic.search.SearchQuery
import dev.nymph.lyrics.logic.tags.TagFilter
import dev.nymph.lyrics.logic.tags.matches
import dev.nymph.lyrics.model.CanvasId
import dev.nymph.lyrics.model.Snippet
import dev.nymph.lyrics.model.SnippetId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** Quiet period after the last keystroke before the in-canvas search applies (a clear applies at once). */
const val CANVAS_SEARCH_DEBOUNCE_MS: Long = 150

/** The snackbar when a snippet opened from search stays hidden by the canvas's tag filter. */
const val HIDDEN_BY_TAG_FILTER: String = "Hidden by the tag filter"

/** The snackbar when the snippet opened from search is no longer on the canvas. */
const val FOCUS_SNIPPET_MISSING: String = "That snippet is no longer on this canvas"

/**
 * The in-canvas search text per canvas for this app session (process memory only, never
 * persisted): leaving a canvas and coming back keeps its query.
 */
class CanvasSearchMemory {
    private val texts = MutableStateFlow<Map<CanvasId, String>>(emptyMap())

    fun get(canvas: CanvasId): String = texts.value[canvas] ?: ""

    fun set(canvas: CanvasId, text: String) = texts.update { if (text.isEmpty()) it - canvas else it + (canvas to text) }
}

/** "N of M": the snippets shown (search and tag filter) out of every loaded snippet. */
data class SearchCount(val shown: Int, val total: Int) {
    val label: String get() = "$shown of $total"
}

/** What opening a canvas focused on [SnippetId] has to do (see [planFocus]). */
sealed interface FocusPlan {
    /** The snippet is not on this canvas (any more). */
    data object Missing : FocusPlan

    /**
     * Select and centre (scroll to) [id]. [clearSearch]: the in-canvas search hides it and is
     * cleared. [hiddenByTags]: the tag filter still hides it (not selected; "Hidden by the tag filter").
     */
    data class Reveal(val id: SnippetId, val clearSearch: Boolean, val hiddenByTags: Boolean) : FocusPlan
}

fun planFocus(snippets: List<Snippet>, id: SnippetId, filter: TagFilter, search: SearchQuery): FocusPlan {
    val snippet = snippets.firstOrNull { it.id == id } ?: return FocusPlan.Missing
    return FocusPlan.Reveal(id, clearSearch = !search.matches(snippet.rawText), hiddenByTags = !filter.matches(snippet))
}
