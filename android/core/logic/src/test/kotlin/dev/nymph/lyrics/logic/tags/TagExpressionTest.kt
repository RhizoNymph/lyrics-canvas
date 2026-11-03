package dev.nymph.lyrics.logic.tags

import dev.nymph.lyrics.model.SnippetType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Kotlin-side behaviour beyond the shared vectors: AST shape, helpers and the Python whitespace rule. */
class TagExpressionTest {
    private fun valid(text: String): TagFilter = (parseTagExpression(text) as TagExprParse.Valid).filter

    private fun invalid(text: String): TagExprError = (parseTagExpression(text) as TagExprParse.Invalid).error

    @Test
    fun `ast is flattened and keeps double negation`() {
        assertEquals(
            TagFilter.Where(TagExpr.and(listOf(TagExpr.Tag("a"), TagExpr.Tag("b"), TagExpr.Tag("c")))),
            valid("a & (b & c)"),
        )
        assertEquals(TagFilter.Where(TagExpr.Not(TagExpr.Not(TagExpr.Tag("a")))), valid("!!a"))
        assertEquals(TagFilter.Where(TagExpr.Type(SnippetType.HOOK)), valid("TYPE:Hook"))
        assertEquals(TagFilter.Everything, valid(" \t "))
    }

    @Test
    fun `and and or nodes cannot hold fewer than two children or their own kind`() {
        assertThrows(IllegalArgumentException::class.java) { TagExpr.And(listOf(TagExpr.Tag("a"))) }
        assertThrows(IllegalArgumentException::class.java) {
            TagExpr.Or(listOf(TagExpr.Tag("a"), TagExpr.Or(listOf(TagExpr.Tag("b"), TagExpr.Tag("c")))))
        }
        assertEquals(TagExpr.Tag("a"), TagExpr.or(listOf(TagExpr.Tag("a"))))
    }

    @Test
    fun `python whitespace separates tokens, including no-break spaces`() {
        assertEquals("a & b", valid("a b").format())
        assertEquals("a & b", valid("a b").format())
        assertEquals("a & b", valid("a\u0085b").format())
        assertEquals("a & b", valid("a　b").format())
        // Zero-width space is not whitespace: it stays inside the name, which then needs no quotes.
        assertEquals("a​b", valid("a​b").format())
    }

    @Test
    fun `positions count code points`() {
        assertEquals(TagExprError(TagExprErrorCode.UNEXPECTED_END, 3, defaultMessage(TagExprErrorCode.UNEXPECTED_END)), invalid("🔥 &"))
        assertEquals(4, codePointLength("🔥abc"))
        assertEquals(1, codePointOffsetOf("🔥abc", 2))
        assertEquals(2, utf16IndexOf("🔥abc", 1))
        assertEquals(5, utf16IndexOf("🔥abc", 99))
    }

    @Test
    fun `tag names are quoted only when needed`() {
        assertEquals("love", formatTagName("love"))
        assertEquals("\"hook idea\"", formatTagName("hook idea"))
        assertEquals("\"and\"", formatTagName("and"))
        assertEquals("\"Type:verse\"", formatTagName("Type:verse"))
        assertEquals("\"a\\\"b\\\\c\"", formatTagName("a\"b\\c"))
        assertEquals("\"(x)\"", formatTagName("(x)"))
        assertTrue(isBareTagName("🔥"))
        assertFalse(isBareTagName(""))
    }

    @Test
    fun `quoted names round trip through format`() {
        for (name in listOf("hook idea", "or", "type:x", "a\"b", "back\\slash", "  padded ")) {
            val parsed = valid(formatTagName(name))
            assertEquals(TagFilter.Where(TagExpr.Tag(name)), parsed)
        }
    }

    @Test
    fun `evaluate against snippets and rename helpers`() {
        val f = valid("Love & !type:hook")
        assertTrue(f.matches(SnippetType.VERSE, listOf("LOVE")))
        assertFalse(f.matches(SnippetType.HOOK, listOf("love")))
        assertTrue(TagFilter.Everything.matches(SnippetType.HOOK, emptyList()))
        assertTrue(f.mentionsTag("LOVE"))
        assertFalse(f.mentionsTag("hook"))
        assertEquals("\"true love\" & !type:hook", f.renameTag("love", "true love").format())
        assertEquals(TagFilter.Everything, TagFilter.Everything.renameTag("a", "b"))
    }
}
