package dev.nymph.lyrics.app.tags

import dev.nymph.lyrics.logic.tags.TagError
import dev.nymph.lyrics.logic.tags.TagExprError
import dev.nymph.lyrics.logic.tags.TagExprErrorCode
import dev.nymph.lyrics.logic.tags.TagNameProblem
import dev.nymph.lyrics.model.TagErrorCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TagMessagesTest {
    @Test
    fun `every server code has its own message`() {
        val texts = TagErrorCode.entries.map { TagError.Rejected(it, 400, "server text", null).userMessage() }
        assertEquals(texts.size, texts.toSet().size)
        assertTrue(texts.none { it == "server text" })
    }

    @Test
    fun `expression errors point at a 1-based character`() {
        assertEquals("Missing closing quote (at 3)", TagExprError(TagExprErrorCode.UNTERMINATED_STRING, 2, "Missing closing quote").userMessage())
        assertEquals(
            "The server rejected the filter: Empty parentheses (at 1).",
            TagError.Rejected(TagErrorCode.INVALID_TAG_EXPRESSION, 422, null, TagExprError(TagExprErrorCode.EMPTY_GROUP, 0, "srv")).userMessage(),
        )
        assertEquals("The server rejected the filter.", TagError.Rejected(TagErrorCode.INVALID_TAG_EXPRESSION, 422, null, null).userMessage())
    }

    @Test
    fun `other failures`() {
        assertEquals("Your session has expired. Sign in again.", TagError.SessionExpired.userMessage())
        assertEquals("boom", TagError.Http(500, "boom").userMessage())
        assertEquals("Can't reach the server. Check your connection.", TagError.Network("x").userMessage())
        assertEquals("Unexpected response from the server.", TagError.Unexpected("x").userMessage())
        assertEquals("Enter a name.", TagNameProblem.EMPTY.userMessage())
    }
}
