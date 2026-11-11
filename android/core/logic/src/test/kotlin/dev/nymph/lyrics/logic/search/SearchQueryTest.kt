package dev.nymph.lyrics.logic.search

import dev.nymph.lyrics.model.SearchErrorCode
import dev.nymph.lyrics.model.error.ApiError
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

class SearchQueryTest {
    @Test
    fun `parse trims, splits on any whitespace and lowercases`() {
        assertEquals(listOf("black", "cat"), SearchQuery.parse("  Black \tCAT ").terms)
        assertTrue(SearchQuery.parse(" \n ").isEmpty)
        assertEquals(SearchQuery.EVERYTHING, SearchQuery.parse(""))
    }

    @Test
    fun `terms and text are NFC-normalised`() {
        val decomposed = "café"
        assertTrue(SearchQuery.parse(decomposed).matches("Café"))
        assertTrue(SearchQuery.parse("café").matches("CAFÉ noir"))
    }

    @Test
    fun `matching reads display text, so a rest mark separates words`() {
        assertTrue(SearchQuery.parse("go now").matches("go_now"))
        assertFalse(SearchQuery.parse("go_now").matches("go_now"))
        assertTrue(SearchQuery.parse("o n").matches("go_now"))
    }

    @Test
    fun `terms match across lines but each term is a substring of the joined display text`() {
        assertTrue(SearchQuery.parse("cat white").matches("black cat\n_\nwhite night"))
    }

    @Test
    fun `checkGlobalSearch trims and limits to 200 code points`() {
        assertEquals(GlobalSearchInput.Blank, checkGlobalSearch("   "))
        assertEquals("cat", (checkGlobalSearch("  cat ") as GlobalSearchInput.Valid).query)
        assertTrue(checkGlobalSearch("a".repeat(200)) is GlobalSearchInput.Valid)
        assertEquals(GlobalSearchInput.TooLong(201), checkGlobalSearch("a".repeat(201)))
        // 200 astral characters are 400 UTF-16 units but 200 code points.
        assertTrue(checkGlobalSearch("🎵".repeat(200)) is GlobalSearchInput.Valid)
    }

    @Test
    fun `highlightSpans finds every term ignoring case and merges overlaps`() {
        val q = SearchQuery.parse("cat at")
        assertEquals(listOf(TextSpan(6, 9), TextSpan(13, 15)), highlightSpans("black Cat is at", q))
        assertEquals(listOf(TextSpan(0, 4)), highlightSpans("aaaa", SearchQuery.parse("aa")))
        assertEquals(listOf(TextSpan(0, 3)), highlightSpans("abc", SearchQuery.parse("ab bc")))
        assertEquals(emptyList<TextSpan>(), highlightSpans("dog", q))
        assertEquals(emptyList<TextSpan>(), highlightSpans("dog", SearchQuery.EVERYTHING))
        // A server excerpt cut mid-line, with ellipses: positions come from the text itself.
        assertEquals(listOf(TextSpan(5, 8)), highlightSpans("…the cat sat…", SearchQuery.parse("CAT")))
        assertEquals(emptyList<TextSpan>(), highlightSpans("", SearchQuery.parse("cat")))
    }

    @Test
    fun `toSearchError maps invalid_search_query and the rest`() {
        assertEquals(
            SearchError.InvalidQuery("too long"),
            toSearchError(ApiError.Validation(422, "too long", SearchErrorCode.INVALID_SEARCH_QUERY.wire)),
        )
        assertEquals(SearchError.SessionExpired, toSearchError(ApiError.Unauthorized(401, null)))
        assertEquals(SearchError.Http(403, "Not authorized"), toSearchError(ApiError.Unauthorized(403, null)))
        assertEquals(SearchError.Http(422, "bad"), toSearchError(ApiError.Validation(422, "bad")))
        assertEquals(SearchError.Http(500, "Server error (500)"), toSearchError(ApiError.Server(500, null)))
        assertEquals(SearchError.Network("offline"), toSearchError(ApiError.Network(IOException("offline"))))
        assertTrue(toSearchError(ApiError.Decoding(IllegalStateException("x"))) is SearchError.Unexpected)
    }
}
