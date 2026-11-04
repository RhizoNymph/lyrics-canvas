package dev.nymph.lyrics.logic.wordbank

import dev.nymph.lyrics.logic.tags.TagError
import dev.nymph.lyrics.logic.tags.TagExprError
import dev.nymph.lyrics.logic.tags.TagExprErrorCode
import dev.nymph.lyrics.model.TagErrorCode
import dev.nymph.lyrics.model.error.ApiError
import dev.nymph.lyrics.model.error.ExpressionErrorBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.IOException

class WordBankErrorTest {
    @Test
    fun `invalid word carries the batch index`() {
        assertEquals(WordBankError.InvalidWord(3, "bad"), toWordBankError(ApiError.Validation(422, "bad", "invalid_word", index = 3)))
        assertEquals(WordBankError.InvalidWord(null, "bad"), toWordBankError(ApiError.Validation(422, "bad", "invalid_word")))
    }

    @Test
    fun `word codes map to their cases`() {
        assertEquals(WordBankError.WordExists, toWordBankError(ApiError.Server(409, "dup", "word_exists")))
        assertEquals(WordBankError.WordNotFound, toWordBankError(ApiError.NotFound("gone", "word_not_found")))
        assertEquals(WordBankError.TooManyWords, toWordBankError(ApiError.Validation(422, "many", "too_many_words")))
        assertEquals(WordBankError.RelatedUnavailable, toWordBankError(ApiError.Server(503, "no model", "related_words_unavailable")))
    }

    @Test
    fun `warming honours Retry-After and defaults to ten seconds`() {
        assertEquals(WordBankError.RelatedWarming(7), toWordBankError(ApiError.Server(503, "w", "related_words_warming", retryAfterSeconds = 7)))
        assertEquals(WordBankError.RelatedWarming(10), toWordBankError(ApiError.Server(503, "w", "related_words_warming")))
    }

    @Test
    fun `tag codes are tag rejections`() {
        assertEquals(
            WordBankError.TagRejected(TagError.Rejected(TagErrorCode.TAG_NOT_FOUND, 404, "no tag", null)),
            toWordBankError(ApiError.NotFound("no tag", "tag_not_found")),
        )
        assertEquals(
            WordBankError.TagRejected(
                TagError.Rejected(TagErrorCode.INVALID_TAG_EXPRESSION, 422, "bad", TagExprError(TagExprErrorCode.UNCLOSED_PAREN, 2, "m")),
            ),
            toWordBankError(ApiError.Validation(422, "bad", "invalid_tag_expression", ExpressionErrorBody("unclosed_paren", 2, "m"))),
        )
    }

    @Test
    fun `transport failures and unknown codes`() {
        assertEquals(WordBankError.SessionExpired, toWordBankError(ApiError.Unauthorized(401, null)))
        assertEquals(WordBankError.Http(403, "Not authorized"), toWordBankError(ApiError.Unauthorized(403, null)))
        assertEquals(WordBankError.Network("down"), toWordBankError(ApiError.Network(IOException("down"))))
        assertEquals(WordBankError.Unexpected("x"), toWordBankError(ApiError.Decoding(IllegalArgumentException("x"))))
        assertEquals(WordBankError.Http(500, "boom"), toWordBankError(ApiError.Server(500, "boom", "future_code")))
        assertEquals(WordBankError.Http(422, "pydantic"), toWordBankError(ApiError.Validation(422, "pydantic")))
    }
}
