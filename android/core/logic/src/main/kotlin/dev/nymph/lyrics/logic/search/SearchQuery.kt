package dev.nymph.lyrics.logic.search

import dev.nymph.lyrics.logic.jsSplitWhitespace
import dev.nymph.lyrics.logic.jsTrim
import dev.nymph.lyrics.logic.rests.displayText
import java.text.Normalizer
import java.util.Locale

/** NFC, then lowercase (locale-independent): both sides of a match go through this. */
internal fun foldForSearch(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFC).lowercase(Locale.ROOT)

/**
 * A parsed search query (the same rule on the backend, web and Android): NFC, trim, split on
 * whitespace into terms, lowercase. A snippet matches when every term is a substring of its
 * display text (rest marks stripped), NFC-normalised and lowercased. No terms matches everything.
 */
@ConsistentCopyVisibility
data class SearchQuery private constructor(val terms: List<String>) {
    /** True when the query has no terms (the filter is off). */
    val isEmpty: Boolean get() = terms.isEmpty()

    /** Whether the display text of [text] (a snippet's `raw_text`) contains every term. */
    fun matches(text: String): Boolean {
        if (terms.isEmpty()) return true
        val haystack = foldForSearch(displayText(text))
        return terms.all { haystack.contains(it) }
    }

    companion object {
        val EVERYTHING: SearchQuery = SearchQuery(emptyList())

        fun parse(raw: String): SearchQuery {
            val trimmed = jsTrim(Normalizer.normalize(raw, Normalizer.Form.NFC))
            if (trimmed.isEmpty()) return EVERYTHING
            return SearchQuery(jsSplitWhitespace(trimmed).map { it.lowercase(Locale.ROOT) })
        }
    }
}
