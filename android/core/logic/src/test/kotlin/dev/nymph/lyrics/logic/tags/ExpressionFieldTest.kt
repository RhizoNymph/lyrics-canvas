package dev.nymph.lyrics.logic.tags

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ExpressionFieldTest {
    @Test
    fun `field parses as it is edited`() {
        val f = ExpressionField().edit("love &", 6)
        assertEquals(TagExprErrorCode.UNEXPECTED_END, f.error?.code)
        assertNull(f.validFilter)
        val g = f.edit("love & night", 12)
        assertNull(g.error)
        assertEquals("love & night", g.validFilter?.format())
        assertEquals(12, g.caret)
    }

    @Test
    fun `caret is clamped to the text`() {
        assertEquals(2, ExpressionField().edit("ab", 9).caret)
        assertEquals(0, ExpressionField().edit("ab", -1).caret)
    }

    @Test
    fun `accepting a suggestion and inserting a tag edit text and caret`() {
        val f = ExpressionField().edit("lo", 2)
        val completion = f.completion(listOf("love"))
        val accepted = f.accept(completion, completion.suggestions.first())
        assertEquals(ExpressionField("love ", 5), accepted)
        assertEquals(ExpressionField("love & night", 12), ExpressionField("love", 4).insertTag("night"))
        assertEquals(ExpressionField(), ExpressionField("x", 1).cleared())
    }

    @Test
    fun `filter bar keeps the last valid filter while the text is invalid`() {
        val bar = FilterBarState.restored("love")
        assertEquals("love", bar.applied.format())
        val broken = bar.edit("love & (", 8)
        assertTrue(broken.invalid)
        assertEquals("love", broken.applied.format())
        assertEquals("love", broken.appliedText)
        val fixed = broken.edit("love & (night)", 14)
        assertFalse(fixed.invalid)
        assertEquals("love & night", fixed.applied.format())
        assertEquals("love & (night)", fixed.appliedText)
    }

    @Test
    fun `restoring an invalid saved text applies nothing but shows it`() {
        val bar = FilterBarState.restored("a &")
        assertTrue(bar.invalid)
        assertEquals(TagFilter.Everything, bar.applied)
        assertEquals("", bar.appliedText)
        assertEquals(3, bar.input.caret)
    }

    @Test
    fun `clear, presets and insertion go through the same rule`() {
        val bar = FilterBarState.restored("love")
        assertEquals(FilterBarState.restored(""), bar.clear())
        val preset = bar.applyPreset("night | dark")
        assertEquals("night | dark", preset.appliedText)
        assertEquals(12, preset.input.caret)
        assertEquals("love & dark", bar.insertTag("dark").appliedText)
        val completion = bar.edit("love & ni", 9).let { it to it.input.completion(listOf("night")) }
        assertEquals("love & night ", completion.first.accept(completion.second, completion.second.suggestions.first()).appliedText)
    }

    @Test
    fun `applied text changes only when the valid filter text changes`() {
        val bar = FilterBarState.restored("love")
        assertEquals(bar.appliedText, bar.edit("love", 2).appliedText)
        assertEquals(bar.applied, bar.edit("love ", 5).applied)
    }

    @Test
    fun `rename rewrites a valid filter that mentions the tag`() {
        val bar = FilterBarState.restored("Love & night")
        val renamed = bar.renameTag("love", "romance")
        assertEquals("romance & night", renamed?.appliedText)
        assertNull(bar.renameTag("dark", "x"))
        assertNull(bar.edit("love &", 6).renameTag("love", "x"))
    }

    @Test
    fun `rhyme filter plan`() {
        assertEquals(RhymeTagFilter.None, rhymeTagFilter(ExpressionField(), notesEnabled = true))
        assertEquals(RhymeTagFilter.None, rhymeTagFilter(ExpressionField("  ", 2), notesEnabled = true))
        assertEquals(RhymeTagFilter.Send("love & night"), rhymeTagFilter(ExpressionField("love night", 0), notesEnabled = true))
        val blocked = rhymeTagFilter(ExpressionField("love &", 0), notesEnabled = true)
        assertEquals(TagExprErrorCode.UNEXPECTED_END, (blocked as RhymeTagFilter.Blocked).error.code)
        assertEquals(RhymeTagFilter.None, rhymeTagFilter(ExpressionField("love &", 0), notesEnabled = false))
    }
}
