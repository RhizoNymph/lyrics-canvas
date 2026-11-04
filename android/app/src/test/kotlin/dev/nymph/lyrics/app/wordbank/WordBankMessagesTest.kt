package dev.nymph.lyrics.app.wordbank

import dev.nymph.lyrics.logic.tags.TagError
import dev.nymph.lyrics.logic.wordbank.AddPlan
import dev.nymph.lyrics.logic.wordbank.RelatedQueryCheck
import dev.nymph.lyrics.logic.wordbank.WordBankError
import dev.nymph.lyrics.logic.wordbank.WordTextProblem
import dev.nymph.lyrics.model.TagErrorCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WordBankMessagesTest {
    @Test
    fun `every error has a message`() {
        val errors = listOf(
            WordBankError.InvalidWord(0, null),
            WordBankError.InvalidWord(null, null),
            WordBankError.WordExists,
            WordBankError.WordNotFound,
            WordBankError.TooManyWords,
            WordBankError.RelatedWarming(10),
            WordBankError.RelatedUnavailable,
            WordBankError.TagRejected(TagError.Rejected(TagErrorCode.TAG_NOT_FOUND, 404, null, null)),
            WordBankError.SessionExpired,
            WordBankError.Http(500, "boom"),
            WordBankError.Network("x"),
            WordBankError.Unexpected("x"),
        )
        assertTrue(errors.all { it.userMessage().isNotBlank() })
        assertEquals("The server rejected word 1.", WordBankError.InvalidWord(0, null).userMessage())
        assertEquals("Building related-word index…", WordBankError.RelatedWarming(10).userMessage())
        assertEquals("That tag no longer exists.", errors[7].userMessage())
    }

    @Test
    fun `local problems`() {
        assertEquals("Words can be at most 64 characters.", WordTextProblem.TooLong.userMessage())
        assertEquals("An entry can have at most 4 words.", WordTextProblem.TooManyWords.userMessage())
        assertEquals("An entry needs at least one letter.", WordTextProblem.NoLetter.userMessage())
        assertEquals("At most 200 words at a time (201 given).", AddPlan.TooMany(201).problemMessage())
        assertNull(AddPlan.Nothing.problemMessage())
        assertEquals("Search text can be at most 64 characters.", RelatedQueryCheck.TooLong.problemMessage())
        assertNull(RelatedQueryCheck.Ok("x").problemMessage())
    }
}
