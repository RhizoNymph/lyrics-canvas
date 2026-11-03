package dev.nymph.lyrics.logic.tags

import dev.nymph.lyrics.model.TagErrorCode
import dev.nymph.lyrics.model.error.ApiError
import dev.nymph.lyrics.model.error.ExpressionErrorBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.IOException

class TagErrorTest {
    @Test
    fun `known codes become rejections`() {
        assertEquals(
            TagError.Rejected(TagErrorCode.TAG_NAME_TAKEN, 409, "taken", null),
            toTagError(ApiError.Server(409, "taken", "tag_name_taken")),
        )
        assertEquals(
            TagError.Rejected(TagErrorCode.PRESET_NOT_FOUND, 404, "gone", null),
            toTagError(ApiError.NotFound("gone", "preset_not_found")),
        )
    }

    @Test
    fun `expression rejections carry the typed location`() {
        val e = toTagError(ApiError.Validation(422, "bad", "invalid_tag_expression", ExpressionErrorBody("unclosed_paren", 2, "srv")))
        assertEquals(
            TagError.Rejected(TagErrorCode.INVALID_TAG_EXPRESSION, 422, "bad", TagExprError(TagExprErrorCode.UNCLOSED_PAREN, 2, "srv")),
            e,
        )
        val unknown = toTagError(ApiError.Validation(422, "bad", "invalid_tag_expression", ExpressionErrorBody("future_code", 2, "srv")))
        assertEquals(TagError.Rejected(TagErrorCode.INVALID_TAG_EXPRESSION, 422, "bad", null), unknown)
    }

    @Test
    fun `everything else keeps its kind`() {
        assertEquals(TagError.SessionExpired, toTagError(ApiError.Unauthorized(401, null)))
        assertEquals(TagError.Http(403, "Not authorized"), toTagError(ApiError.Unauthorized(403, null)))
        assertEquals(TagError.Http(404, "Not found"), toTagError(ApiError.NotFound(null)))
        assertEquals(TagError.Http(422, "x"), toTagError(ApiError.Validation(422, "x", "something_else")))
        assertEquals(TagError.Network("down"), toTagError(ApiError.Network(IOException("down"))))
        assertEquals(TagError.Unexpected("bad"), toTagError(ApiError.Decoding(IllegalStateException("bad"))))
    }
}
