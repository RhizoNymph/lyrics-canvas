package dev.nymph.lyrics.logic.list

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** The port against the upstream `fractional-indexing` 3.2.0 test cases, plus the server's limits. */
class ListRankTest {
    private fun r(text: String): ListRank = checkNotNull(ListRank.parse(text)) { "valid key $text" }

    private fun between(a: String?, b: String?): RankResult<ListRank> = keyBetween(a?.let(::r), b?.let(::r))

    private fun ok(a: String?, b: String?): String = (between(a, b) as RankResult.Ok).value.value

    private fun n(a: String?, b: String?, count: Int): List<String> =
        (keysBetween(a?.let(::r), b?.let(::r), count) as RankResult.Ok).value.map { it.value }

    // ---- generateKeyBetween ----

    @Test
    fun `open ends`() {
        assertEquals("a0", ok(null, null))
        assertEquals("Zz", ok(null, "a0"))
        assertEquals("Zy", ok(null, "Zz"))
        assertEquals("a1", ok("a0", null))
        assertEquals("a2", ok("a1", null))
    }

    @Test
    fun `between two keys`() {
        assertEquals("a0V", ok("a0", "a1"))
        assertEquals("a1V", ok("a1", "a2"))
        assertEquals("a0l", ok("a0V", "a1"))
        assertEquals("ZzV", ok("Zz", "a0"))
        assertEquals("a0", ok("Zz", "a1"))
        assertEquals("a0G", ok("a0", "a0V"))
        assertEquals("a08", ok("a0", "a0G"))
        assertEquals("b127", ok("b125", "b129"))
        assertEquals("a1", ok("a0", "a1V"))
        assertEquals("a0", ok("Zz", "a01"))
    }

    @Test
    fun `before a key with a fraction or a longer integer`() {
        assertEquals("a0", ok(null, "a0V"))
        assertEquals("b99", ok(null, "b999"))
        assertEquals("Xzzz", ok(null, "Y00"))
    }

    @Test
    fun `integer rollovers`() {
        assertEquals("c000", ok("bzz", null))
        assertEquals("a0", ok("Zz", null))
        assertEquals("Zz", ok(null, "a0"))
    }

    @Test
    fun `the ends of the key space`() {
        assertEquals("A000000000000000000000000000V", ok(null, "A000000000000000000000000001"))
        assertEquals("zzzzzzzzzzzzzzzzzzzzzzzzzzz", ok("zzzzzzzzzzzzzzzzzzzzzzzzzzy", null))
        assertEquals("zzzzzzzzzzzzzzzzzzzzzzzzzzzV", ok("zzzzzzzzzzzzzzzzzzzzzzzzzzz", null))
    }

    @Test
    fun `a key not below b has nothing between`() {
        assertEquals(RankResult.Failed(ListRankError.NotAscending(r("a1"), r("a0"))), between("a1", "a0"))
        assertEquals(RankResult.Failed(ListRankError.NotAscending(r("a0"), r("a0"))), between("a0", "a0"))
    }

    @Test
    fun `a key over the length limit is refused, not returned`() {
        // Repeatedly halving towards a0 grows the key by one digit about every six steps.
        var b = r("a1")
        var failure: ListRankError? = null
        for (i in 0 until 1000) {
            when (val next = keyBetween(r("a0"), b)) {
                is RankResult.Ok -> {
                    assertTrue(next.value.value.length <= LIST_RANK_MAX_LENGTH)
                    b = next.value
                }
                is RankResult.Failed -> {
                    failure = next.error
                    break
                }
            }
        }
        assertEquals(ListRankError.TooLong(LIST_RANK_MAX_LENGTH + 1), failure)
    }

    // ---- validateOrderKey ----

    @Test
    fun `invalid keys do not parse`() {
        listOf(
            "",
            "A00000000000000000000000000", // the smallest integer alone
            "a00", // trailing zero in the fraction
            "a0V0",
            "0",
            "1",
            "a",
            "b1", // integer part too short
            "!0",
            " a0",
            "a".padEnd(LIST_RANK_MAX_LENGTH + 1, '1'),
        ).forEach { assertNull(ListRank.parse(it), "should be invalid: '$it'") }
    }

    @Test
    fun `valid keys parse`() {
        listOf("a0", "Zz", "a0V", "b125", "A000000000000000000000000000V", "zzzzzzzzzzzzzzzzzzzzzzzzzzz", "a".padEnd(LIST_RANK_MAX_LENGTH, '1'))
            .forEach { assertEquals(it, ListRank.parse(it)?.value) }
    }

    @Test
    fun `keys compare by character order, not locale`() {
        val sorted = listOf("a0", "Zz", "a0V", "a0a", "Y00").map(::r).sorted().map { it.value }
        assertEquals(listOf("Y00", "Zz", "a0", "a0V", "a0a"), sorted)
    }

    // ---- generateNKeysBetween ----

    @Test
    fun `n keys from scratch and at the ends`() {
        assertEquals(emptyList<String>(), n(null, null, 0))
        assertEquals(listOf("a0"), n(null, null, 1))
        assertEquals(listOf("a0", "a1", "a2", "a3", "a4"), n(null, null, 5))
        assertEquals(listOf("a5", "a6", "a7", "a8", "a9", "aA", "aB", "aC", "aD", "aE"), n("a4", null, 10))
        assertEquals(listOf("Zv", "Zw", "Zx", "Zy", "Zz"), n(null, "a0", 5))
    }

    @Test
    fun `n keys between two keys`() {
        assertEquals(
            listOf("a04", "a08", "a0G", "a0K", "a0O", "a0V", "a0Z", "a0d", "a0l", "a0t", "a1", "a14", "a18", "a1G", "a1O", "a1V", "a1Z", "a1d", "a1l", "a1t"),
            n("a0", "a2", 20),
        )
    }

    @Test
    fun `n keys are strictly ascending and between their bounds`() {
        for ((a, b) in listOf(null to null, "a0" to null, null to "a0", "a0" to "a1", "Zz" to "a0")) {
            val keys = n(a, b, 100)
            assertEquals(100, keys.size)
            keys.zipWithNext().forEach { (x, y) -> assertTrue(x < y, "$x < $y") }
            if (a != null) assertTrue(a < keys.first())
            if (b != null) assertTrue(keys.last() < b)
        }
    }

    @Test
    fun `n keys need ascending bounds and a count`() {
        assertEquals(RankResult.Failed(ListRankError.NotAscending(r("a1"), r("a1"))), keysBetween(r("a1"), r("a1"), 3))
        assertThrows<IllegalArgumentException> { keysBetween(null, null, -1) }
    }

    @Test
    fun `getOrNull`() {
        assertEquals(r("a0"), keyBetween(null, null).getOrNull())
        assertNull(between("a1", "a0").getOrNull())
    }
}
