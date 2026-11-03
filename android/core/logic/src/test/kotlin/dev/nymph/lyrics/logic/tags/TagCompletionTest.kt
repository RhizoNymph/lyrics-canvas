package dev.nymph.lyrics.logic.tags

import dev.nymph.lyrics.model.SnippetType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TagCompletionTest {
    private val tags = listOf("love", "Lonely", "night", "hook idea", "glove", "type:x")

    private fun at(text: String, caret: Int = codePointLength(text)) = completeAt(text, caret, tags)

    @Test
    fun `empty input offers tags, types and not`() {
        val c = at("")
        assertEquals(0, c.start)
        assertEquals(0, c.end)
        val labels = c.suggestions.map { it.insertText }
        assertEquals(listOf("glove", "\"hook idea\"", "Lonely", "love", "night"), labels.take(5))
        assertTrue("type:verse" in labels)
        assertTrue("not" in labels)
        assertTrue("and" !in labels && "or" !in labels)
    }

    @Test
    fun `a word prefix matches tag names case-insensitively, prefix matches first`() {
        val c = at("lo")
        assertEquals(0, c.start)
        assertEquals(2, c.end)
        assertEquals(listOf("Lonely", "love", "glove"), c.suggestions.filterIsInstance<Suggestion.TagName>().map { it.insertText })
    }

    @Test
    fun `after an operand the binary keywords fit`() {
        val c = at("love ")
        assertEquals(listOf("and", "or", "not"), c.suggestions.filterIsInstance<Suggestion.Keyword>().map { it.insertText })
        val o = at("love o")
        assertEquals(listOf("or"), o.suggestions.filterIsInstance<Suggestion.Keyword>().map { it.insertText })
        assertEquals(5, o.start)
    }

    @Test
    fun `after an operator only operands and not fit`() {
        for (text in listOf("love & ", "love | ", "(", "!", "love and ")) {
            val keywords = at(text).suggestions.filterIsInstance<Suggestion.Keyword>().map { it.keyword }
            assertEquals(listOf(ExprKeyword.NOT), keywords, text)
        }
    }

    @Test
    fun `type prefix offers type values`() {
        val c = at("ty")
        assertEquals(SnippetType.entries.map { "type:${it.wire}" }, c.suggestions.filterIsInstance<Suggestion.TypeValue>().map { it.insertText })
        val ch = at("love & TYPE:ch")
        assertEquals(listOf("type:chorus"), ch.suggestions.filterIsInstance<Suggestion.TypeValue>().map { it.insertText })
        assertEquals(7, ch.start)
    }

    @Test
    fun `the word around the caret is replaced`() {
        val c = completeAt("love & nig | x", 9, tags)
        assertEquals(7, c.start)
        assertEquals(10, c.end)
        val applied = c.apply("love & nig | x", c.suggestions.first { it.insertText == "night" })
        assertEquals(TextEdit("love & night | x", 12), applied)
    }

    @Test
    fun `applying at the end adds a space to keep typing`() {
        val c = at("lo")
        assertEquals(TextEdit("love ", 5), c.apply("lo", Suggestion.TagName("love")))
        assertEquals(TextEdit("(love)", 5), completeAt("(lo)", 3, tags).apply("(lo)", Suggestion.TagName("love")))
    }

    @Test
    fun `inside an open quote the quoted text is the prefix`() {
        val c = at("love & \"hook i")
        assertEquals(7, c.start)
        assertEquals(listOf("\"hook idea\""), c.suggestions.map { it.insertText })
        assertEquals(TextEdit("love & \"hook idea\" ", 19), c.apply("love & \"hook i", c.suggestions.single()))
    }

    @Test
    fun `quoted names that need quoting insert quoted`() {
        val c = at("type:")
        assertTrue(c.suggestions.any { it is Suggestion.TypeValue })
        val x = at("\"type")
        assertEquals(listOf("\"type:x\""), x.suggestions.map { it.insertText })
    }

    @Test
    fun `positions are code points around emoji`() {
        val text = "🔥 & lo"
        val c = at(text)
        assertEquals(4, c.start)
        assertEquals(6, c.end)
        assertEquals(TextEdit("🔥 & love ", 9), c.apply(text, Suggestion.TagName("love")))
    }

    @Test
    fun `an exact match is not offered again and the limit applies`() {
        assertTrue(at("love").suggestions.none { it.insertText == "love" })
        assertEquals(2, completeAt("", 0, tags, limit = 2).suggestions.size)
    }

    @Test
    fun `tag pills insert at the caret joined with and`() {
        assertEquals(TextEdit("night", 5), insertTagAt("", 0, "night"))
        assertEquals(TextEdit("love & night", 12), insertTagAt("love", 4, "night"))
        assertEquals(TextEdit("love & night", 12), insertTagAt("love ", 5, "night"))
        assertEquals(TextEdit("love | night", 12), insertTagAt("love |", 6, "night"))
        assertEquals(TextEdit("love | night", 12), insertTagAt("love | ", 7, "night"))
        assertEquals(TextEdit("night & love", 5), insertTagAt("love", 0, "night"))
        assertEquals(TextEdit("!night", 6), insertTagAt("!", 1, "night"))
        assertEquals(TextEdit("(a & night)", 10), insertTagAt("(a)", 2, "night"))
        assertEquals(TextEdit("a & \"hook idea\" & b", 15), insertTagAt("a  b", 2, "hook idea"))
    }

    @Test
    fun `inserting inside a word or quote moves past it`() {
        assertEquals(TextEdit("love & night", 12), insertTagAt("love", 2, "night"))
        assertEquals(TextEdit("\"a b\" & night", 13), insertTagAt("\"a b\"", 2, "night"))
        assertEquals(TextEdit("🔥 & night", 9), insertTagAt("🔥", 1, "night"))
    }
}
