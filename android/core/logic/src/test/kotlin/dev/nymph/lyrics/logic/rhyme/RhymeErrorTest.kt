package dev.nymph.lyrics.logic.rhyme

import dev.nymph.lyrics.model.error.ApiError
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import java.io.IOException

class RhymeErrorTest {
    @Test
    fun `maps a missing response to network`() {
        val e = toRhymeError(ApiError.Network(IOException("Request failed")), RhymeOperation.QUERY)
        assertEquals(RhymeErrorKind.NETWORK, e.kind)
        assertNull(e.status)
        assertEquals(RhymeOperation.QUERY, e.operation)
    }

    @Test
    fun `maps 404 to not_found with the detail message`() {
        val e = toRhymeError(ApiError.fromHttp(404, """{"detail":"Canvas not found"}"""), RhymeOperation.QUERY)
        assertEquals(RhymeErrorKind.NOT_FOUND, e.kind)
        assertEquals(404, e.status)
        assertEquals("Canvas not found", e.message)
    }

    @Test
    fun `maps 422 to invalid_request and joins pydantic messages`() {
        val e = toRhymeError(ApiError.fromHttp(422, """{"detail":[{"msg":"k must be 1..4"},{"msg":"bad stress"}]}"""), RhymeOperation.QUERY)
        assertEquals(RhymeErrorKind.INVALID_REQUEST, e.kind)
        assertEquals("k must be 1..4; bad stress", e.message)
    }

    @Test
    fun `maps 500 to server`() {
        val e = toRhymeError(ApiError.fromHttp(500, null), RhymeOperation.MATERIALIZE)
        assertEquals(RhymeErrorKind.SERVER, e.kind)
        assertEquals(RhymeOperation.MATERIALIZE, e.operation)
    }

    @Test
    fun `wraps non-HTTP failures as unknown`() {
        assertEquals(RhymeErrorKind.UNKNOWN, toRhymeError(ApiError.Decoding(IllegalStateException("boom")), RhymeOperation.QUERY).kind)
        assertEquals("boom", toRhymeError(ApiError.Decoding(IllegalStateException("boom")), RhymeOperation.QUERY).message)
    }

    @Test
    fun `passes through an existing RhymeError`() {
        val orig = RhymeError(RhymeErrorKind.SERVER, RhymeOperation.QUERY, "x", 503)
        assertSame(orig, toRhymeError(orig, RhymeOperation.MATERIALIZE))
    }

    @Test
    fun `maps unauthorized like any other http failure`() {
        val e = toRhymeError(ApiError.fromHttp(401, """{"detail":"Invalid token"}"""), RhymeOperation.QUERY)
        assertEquals(RhymeError(RhymeErrorKind.SERVER, RhymeOperation.QUERY, "Invalid token", 401), e)
    }
}
