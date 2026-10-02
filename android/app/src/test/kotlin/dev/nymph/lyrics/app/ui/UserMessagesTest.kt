package dev.nymph.lyrics.app.ui

import dev.nymph.lyrics.model.error.ApiError
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class UserMessagesTest {
    @Test
    fun `api errors become readable messages`() {
        assertEquals("Your session has expired. Sign in again.", ApiError.Unauthorized(401, "Could not validate credentials").userMessage())
        assertEquals("Not signed in.", ApiError.Unauthorized(403, null).userMessage())
        assertEquals("Not found", ApiError.NotFound(null).userMessage())
        assertEquals("bad k", ApiError.Validation(422, "bad k").userMessage())
        assertEquals("Server error (502)", ApiError.Server(502, null).userMessage())
        assertEquals("Can't reach the server. Check your connection.", ApiError.Network(java.io.IOException("x")).userMessage())
        assertEquals("Unexpected response from the server.", ApiError.Decoding(IllegalStateException("x")).userMessage())
    }

    @Test
    fun `hex colours parse to opaque argb`() {
        assertEquals(0xFF3B82F6, parseHexColor("#3b82f6"))
        assertEquals(0xFF3B82F6, parseHexColor("3B82F6"))
        assertEquals(null, parseHexColor("#12"))
        assertEquals(null, parseHexColor("#zzzzzz"))
    }
}
