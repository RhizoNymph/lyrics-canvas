package dev.nymph.lyrics.app.wordbank

import dev.nymph.lyrics.model.Snippet
import dev.nymph.lyrics.model.SnippetType
import dev.nymph.lyrics.model.error.ApiError

/** The outcome of creating a snippet from a word bank draft. */
sealed interface DraftCreateResult {
    data class Created(val snippet: Snippet) : DraftCreateResult

    data class Failed(val error: ApiError) : DraftCreateResult

    /** The canvas has not loaded (or failed to): there is nowhere to place the snippet. */
    data object CanvasNotLoaded : DraftCreateResult
}

/** The canvas the word bank was opened from (implemented by the canvas ViewModel). */
interface WordBankCanvasPort {
    /**
     * Create a snippet with [text] and [type] the way the canvas's "New snippet" does (at the
     * viewport centre) and show it there.
     */
    suspend fun createSnippetFromText(text: String, type: SnippetType): DraftCreateResult
}
