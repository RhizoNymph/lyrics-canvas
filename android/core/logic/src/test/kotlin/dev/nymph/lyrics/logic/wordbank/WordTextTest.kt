package dev.nymph.lyrics.logic.wordbank

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class WordTextTest {
    private fun ok(raw: String): String {
        val check = validateWordText(raw)
        assertTrue(check is WordTextCheck.Valid, "expected valid for ${raw.debug()}, got $check")
        return (check as WordTextCheck.Valid).text.value
    }

    private fun problem(raw: String): WordTextProblem {
        val check = validateWordText(raw)
        assertTrue(check is WordTextCheck.Invalid, "expected invalid for ${raw.debug()}, got $check")
        return (check as WordTextCheck.Invalid).problem
    }

    private fun String.debug() = map { if (it.code < 0x20 || it.code > 0x7e) "\\u%04x".format(it.code) else "$it" }.joinToString("")

    @Test
    fun `plain words and phrases are kept with their case`() {
        assertEquals("night", ok("night"))
        assertEquals("Night", ok("Night"))
        assertEquals("neon city lights", ok("neon city lights"))
        assertEquals("NEON", ok("NEON"))
    }

    @Test
    fun `ends are stripped and inner whitespace collapses to one space`() {
        assertEquals("neon lights", ok("  neon \t\n  lights  "))
        assertEquals("a b", ok("a  b"))
        assertEquals("a b", ok(" a b "))
        assertEquals("a b", ok("a\u0085b"))
    }

    @Test
    fun `apostrophes and hyphens are allowed`() {
        assertEquals("don't", ok("don't"))
        assertEquals("don’t", ok("don’t"))
        assertEquals("rock-n-roll", ok("rock-n-roll"))
        assertEquals("'til", ok("'til"))
        assertEquals("o' the-night", ok("o' the-night"))
    }

    @Test
    fun `letters of every script count`() {
        assertEquals("café", ok("café"))
        assertEquals("ночь", ok("ночь"))
        assertEquals("夜", ok("夜"))
        assertEquals("ʻokina", ok("ʻokina")) // Lm
        assertEquals("ǅemal", ok("ǅemal")) // Lt
    }

    @Test
    fun `text is NFC normalized`() {
        val decomposed = "café"
        assertEquals("café", ok(decomposed))
        assertEquals(4, ok(decomposed).codePointCount(0, 4))
    }

    @Test
    fun `empty and blank text is refused`() {
        assertEquals(WordTextProblem.Empty, problem(""))
        assertEquals(WordTextProblem.Empty, problem("   \t\n"))
    }

    @Test
    fun `at most 64 code points after normalizing`() {
        assertEquals("a".repeat(64), ok("a".repeat(64)))
        assertEquals(WordTextProblem.TooLong, problem("a".repeat(65)))
        // Whitespace removed by normalizing does not count.
        assertEquals("a".repeat(64), ok("   " + "a".repeat(64) + "   "))
        // Code points, not UTF-16 units: 64 astral letters fit.
        val astral = "𐐀" // DESERET CAPITAL LETTER LONG I (Lu)
        assertEquals(astral.repeat(64), ok(astral.repeat(64)))
        assertEquals(WordTextProblem.TooLong, problem(astral.repeat(65)))
        // NFC first: 64 decomposed é (128 code points) become 64.
        assertEquals("é".repeat(64), ok("é".repeat(64)))
    }

    @Test
    fun `at most four words`() {
        assertEquals("one two three four", ok("one two three four"))
        assertEquals(WordTextProblem.TooManyWords, problem("one two three four five"))
        assertEquals("one two three four", ok("one   two\tthree\nfour"))
    }

    @ParameterizedTest
    @ValueSource(strings = ["night1", "a.b", "hey!", "a_b", "a/b", "😀", "a,b", "tab\u0000", "x+y", "a\"b", "under–dash"])
    fun `other characters are refused`(raw: String) {
        assertTrue(problem(raw) is WordTextProblem.InvalidCharacter)
    }

    @Test
    fun `the first offending character is reported`() {
        assertEquals(WordTextProblem.InvalidCharacter("1"), problem("night1 2"))
        assertEquals(WordTextProblem.InvalidCharacter("😀"), problem("hi 😀"))
    }

    @Test
    fun `at least one letter`() {
        assertEquals(WordTextProblem.NoLetter, problem("--"))
        assertEquals(WordTextProblem.NoLetter, problem("' -"))
        assertEquals("a-", ok("a-"))
    }

    @Test
    fun `a too long text is reported before a bad character`() {
        assertEquals(WordTextProblem.TooLong, problem("1".repeat(65)))
    }

    @Test
    fun `isValidWordText mirrors the check`() {
        assertTrue(isValidWordText("tin"))
        assertTrue(!isValidWordText("tin2"))
    }
}
