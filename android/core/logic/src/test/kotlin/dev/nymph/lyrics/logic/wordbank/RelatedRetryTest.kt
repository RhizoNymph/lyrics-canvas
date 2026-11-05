package dev.nymph.lyrics.logic.wordbank

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class RelatedRetryTest {
    @Test
    fun `retries honour Retry-After up to ten times`() {
        assertEquals(7_000L, relatedRetryDelayMs(retriesSoFar = 0, retryAfterSeconds = 7))
        assertEquals(10_000L, relatedRetryDelayMs(retriesSoFar = 9, retryAfterSeconds = 10))
        assertNull(relatedRetryDelayMs(retriesSoFar = MAX_RELATED_RETRIES, retryAfterSeconds = 10))
    }

    @Test
    fun `a zero or huge Retry-After is clamped`() {
        assertEquals(1_000L, relatedRetryDelayMs(0, 0))
        assertEquals(60_000L, relatedRetryDelayMs(0, 3600))
    }

    @Test
    fun `related query text is 1 to 64 code points after a strip`() {
        assertEquals(RelatedQueryCheck.Ok("ocean waves"), checkRelatedQuery("  ocean waves "))
        assertEquals(RelatedQueryCheck.Empty, checkRelatedQuery("   "))
        assertEquals(RelatedQueryCheck.TooLong, checkRelatedQuery("a".repeat(65)))
        assertEquals(RelatedQueryCheck.Ok("a".repeat(64)), checkRelatedQuery("a".repeat(64)))
        // A phrase or theme: punctuation and digits are allowed here.
        assertEquals(RelatedQueryCheck.Ok("1980s, neon!"), checkRelatedQuery("1980s, neon!"))
    }
}
