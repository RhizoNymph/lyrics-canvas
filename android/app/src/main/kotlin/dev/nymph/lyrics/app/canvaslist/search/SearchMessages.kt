package dev.nymph.lyrics.app.canvaslist.search

import dev.nymph.lyrics.app.ui.SESSION_EXPIRED
import dev.nymph.lyrics.logic.search.MAX_SEARCH_QUERY_LENGTH
import dev.nymph.lyrics.logic.search.SearchError

/** The empty state of a search with no hits. */
const val NO_MATCHES: String = "No matches"

const val INVALID_SEARCH_MESSAGE: String = "Search for 1 to $MAX_SEARCH_QUERY_LENGTH characters."

/** The text shown for a failed search across canvases. */
fun SearchError.userMessage(): String = when (this) {
    is SearchError.InvalidQuery -> INVALID_SEARCH_MESSAGE
    SearchError.SessionExpired -> SESSION_EXPIRED
    is SearchError.Http -> "Search failed: $message"
    is SearchError.Network -> "Can't reach the server. Check your connection."
    is SearchError.Unexpected -> "Unexpected response from the server."
}
