package dev.nymph.lyrics.logic.wordbank

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class WordInputTest {
    private fun ready(raw: String): List<String> {
        val plan = planAdd(raw)
        check(plan is AddPlan.Ready) { "expected ready, got $plan" }
        return plan.words.map { it.value }
    }

    @Test
    fun `commas and newlines split, spaces do not`() {
        assertEquals(listOf("night", "neon lights", "rain"), splitWordInput("night, neon lights\nrain"))
        assertEquals(listOf("a", "b", "c"), splitWordInput("a\r\nb\rc"))
    }

    @Test
    fun `empty pieces are dropped`() {
        assertEquals(listOf("a", "b"), splitWordInput(",, a ,\n\n, b ,"))
        assertEquals(emptyList<String>(), splitWordInput("  ,\n , "))
        assertEquals(emptyList<String>(), splitWordInput(""))
    }

    @Test
    fun `nothing to add`() {
        assertEquals(AddPlan.Nothing, planAdd(""))
        assertEquals(AddPlan.Nothing, planAdd(" , \n "))
    }

    @Test
    fun `ready words are normalized`() {
        assertEquals(listOf("neon lights", "café"), ready("  neon   lights ,café"))
    }

    @Test
    fun `duplicates ignoring case collapse, first spelling wins`() {
        assertEquals(listOf("Night", "rain"), ready("Night, rain, night, NIGHT, Rain"))
    }

    @Test
    fun `the first bad piece rejects the batch with its index among the pieces`() {
        assertEquals(AddPlan.Invalid(1, "rain2", WordTextProblem.InvalidCharacter("2")), planAdd("night, rain2, snow3"))
        assertEquals(AddPlan.Invalid(0, "a b c d e", WordTextProblem.TooManyWords), planAdd(",a b c d e"))
    }

    @Test
    fun `at most 200 distinct words per batch`() {
        val words = (1..200).map { "w" + "a".repeat(it % 50) + letters(it) }
        assertEquals(200, ready(words.joinToString(",")).size)
        val plan = planAdd((words + "extra").joinToString(","))
        assertEquals(AddPlan.TooMany(201), plan)
    }

    @Test
    fun `duplicates do not count toward the batch limit`() {
        assertEquals(1, ready(List(500) { "same" }.joinToString(",")).size)
    }

    /** A distinct letters-only suffix for [n]. */
    private fun letters(n: Int): String {
        var x = n
        val sb = StringBuilder()
        do {
            sb.append('a' + x % 26)
            x /= 26
        } while (x > 0)
        return sb.toString()
    }
}
