package dev.nymph.lyrics.logic.rhyme.pattern

import dev.nymph.lyrics.logic.tags.TagExprError
import dev.nymph.lyrics.logic.tags.TagExprErrorCode
import dev.nymph.lyrics.model.PatternErrorCode
import dev.nymph.lyrics.model.error.ApiError
import dev.nymph.lyrics.model.error.ExpressionErrorBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.IOException

class PatternErrorTest {
    @Test
    fun `pattern codes become typed rejections`() {
        assertEquals(PatternError.Rejected(PatternErrorCode.RANGE_INVALID, "bad range"), toPatternError(ApiError.Validation(422, "bad range", "pattern_range_invalid")))
        assertEquals(PatternError.Rejected(PatternErrorCode.SPAN_INVALID, "bad span"), toPatternError(ApiError.Validation(422, "bad span", "pattern_span_invalid")))
        assertEquals(PatternError.Rejected(PatternErrorCode.SLOT_ORDER, "crossed"), toPatternError(ApiError.Validation(422, "crossed", "pattern_slot_order")))
    }

    @Test
    fun `an invalid tag expression keeps its location`() {
        val error = ApiError.Validation(422, "Unexpected end", "invalid_tag_expression", ExpressionErrorBody("unexpected_end", 6, "Unexpected end"))
        assertEquals(
            PatternError.Rejected(PatternErrorCode.INVALID_TAG_EXPRESSION, "Unexpected end", TagExprError(TagExprErrorCode.UNEXPECTED_END, 6, "Unexpected end")),
            toPatternError(error),
        )
    }

    @Test
    fun `other failures map by kind`() {
        assertEquals(PatternError.Invalid(422, "Unknown phoneme"), toPatternError(ApiError.Validation(422, "Unknown phoneme")))
        assertEquals(PatternError.NotFound("Snippet not found"), toPatternError(ApiError.NotFound("Snippet not found")))
        assertEquals(PatternError.NeedsConnection, toPatternError(ApiError.NeedsConnection))
        assertEquals(PatternError.Network("down"), toPatternError(ApiError.Network(IOException("down"))))
        assertEquals(PatternError.Server(500, "boom"), toPatternError(ApiError.Server(500, "boom")))
        assertEquals(PatternError.Server(401, "expired"), toPatternError(ApiError.Unauthorized(401, "expired")))
        assertEquals(PatternError.Unexpected("bad json"), toPatternError(ApiError.Decoding(IllegalStateException("bad json"))))
    }
}
