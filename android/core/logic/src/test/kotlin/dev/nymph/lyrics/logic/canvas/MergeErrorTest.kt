package dev.nymph.lyrics.logic.canvas

import dev.nymph.lyrics.model.error.ApiError
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.IOException

class MergeErrorTest {
    @Test
    fun `maps api errors to merge errors`() {
        assertEquals(MergeError.NotFound("Snippet not found"), toMergeError(ApiError.fromHttp(404, """{"detail":"Snippet not found"}""")))
        assertEquals(MergeError.Invalid("need two snippets"), toMergeError(ApiError.fromHttp(422, """{"detail":"need two snippets"}""")))
        assertEquals(MergeError.Network("down"), toMergeError(ApiError.Network(IOException("down"))))
        assertEquals(MergeError.Server(500, "Server error (500)"), toMergeError(ApiError.fromHttp(500, null)))
        assertEquals(MergeError.Server(401, "Invalid token"), toMergeError(ApiError.fromHttp(401, """{"detail":"Invalid token"}""")))
        assertEquals(MergeError.Unknown("bad"), toMergeError(ApiError.Decoding(IllegalStateException("bad"))))
    }

    @Test
    fun `fewer than two snippets is a local merge error`() {
        assertEquals(MergeError.TooFewSnippets, MergeError.TooFewSnippets)
        assertEquals("Select at least two snippets to merge", MergeError.TooFewSnippets.message)
    }
}
