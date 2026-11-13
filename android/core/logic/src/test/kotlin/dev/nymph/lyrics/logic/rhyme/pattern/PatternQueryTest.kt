package dev.nymph.lyrics.logic.rhyme.pattern

import dev.nymph.lyrics.logic.rhyme.RhymeSources
import dev.nymph.lyrics.logic.rhyme.pattern.PatternFx.TARGET
import dev.nymph.lyrics.logic.rhyme.pattern.PatternFx.board
import dev.nymph.lyrics.logic.tags.ExpressionField
import dev.nymph.lyrics.logic.tags.TagExprErrorCode
import dev.nymph.lyrics.model.CanvasId
import dev.nymph.lyrics.model.PatternDetectRequest
import dev.nymph.lyrics.model.SnippetId
import dev.nymph.lyrics.model.SourceName
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PatternQueryTest {
    private val none = ExpressionField()

    private fun query(
        b: SlotBoard = board(),
        params: PatternParams = PatternParams(),
        filter: ExpressionField = none,
        dismissed: Set<String> = emptySet(),
        page: PatternPage = PatternPage.First,
    ) = (planPatternSuggest(TARGET, b, params, filter, dismissed, page) as PatternPlan.Query).request

    @Test
    fun `detect asks for the range with the detection threshold`() {
        assertEquals(
            PatternDetectRequest(CanvasId("c1"), SnippetId("a"), 0, 3, minScore = 0.6),
            detectRequest(TARGET, PatternParams(detectMinScore = 0.6)),
        )
    }

    @Test
    fun `the first page sends the on slots, controls and dismissals`() {
        val b = board().applied(SlotEdit.Toggle(SlotId("s1")))
        val r = query(b, PatternParams(minScore = 0.65, includeRare = true), dismissed = setOf("dict:b", "dict:a"))
        assertEquals(listOf("s0", "s2"), r.slots.map { it.id })
        assertEquals(0 to 3, r.lineStart to r.lineEnd)
        assertEquals(0.65, r.minScore)
        assertTrue(r.includeRare)
        assertEquals(listOf(SourceName.NOTES, SourceName.DICTIONARY), r.sources)
        assertEquals(listOf("dict:a", "dict:b"), r.excludeIds)
        assertEquals(30, r.limit)
        assertEquals(emptyMap<String, Int>(), r.offsets)
        assertTrue(r.lineMatches)
        assertEquals(0, r.lineMatchOffset)
        assertNull(r.tagFilter)
    }

    @Test
    fun `edited slots are sent with their ids, user slots as u ids`() {
        val b = board().applied(SlotEdit.NewSlot(0, 1)).applied(SlotEdit.AddWord(SlotId("s0"), 2, 1))
        val r = query(b)
        assertEquals(listOf("u0", "s0", "s1", "s2"), r.slots.map { it.id })
        assertEquals(listOf(0 to 3, 1 to 1, 2 to 1), r.slots[1].members.map { it.lineIndex to it.wordStart })
    }

    @Test
    fun `a slot page sends the same slots with only that offset and no line matches`() {
        val r = query(page = PatternPage.Slot(SlotId("s1"), 30))
        assertEquals(listOf("s0", "s1", "s2"), r.slots.map { it.id })
        assertEquals(mapOf("s1" to 30), r.offsets)
        assertFalse(r.lineMatches)
    }

    @Test
    fun `a line match page asks for that offset with one row per slot`() {
        val r = query(page = PatternPage.LineMatches(30))
        assertTrue(r.lineMatches)
        assertEquals(30, r.lineMatchOffset)
        assertEquals(1, r.limit)
        assertEquals(emptyMap<String, Int>(), r.offsets)
    }

    @Test
    fun `line matches need the notes source`() {
        val r = query(params = PatternParams(sources = RhymeSources(notes = false, dictionary = true)))
        assertFalse(r.lineMatches)
        assertEquals(listOf(SourceName.DICTIONARY), r.sources)
    }

    @Test
    fun `no on slots or no sources skip`() {
        val off = board().applied(SlotEdit.Toggle(SlotId("s0"))).applied(SlotEdit.Toggle(SlotId("s1"))).applied(SlotEdit.Toggle(SlotId("s2")))
        assertEquals(PatternPlan.Skip(PatternSkip.NO_SLOTS_ON), planPatternSuggest(TARGET, off, PatternParams(), none, emptySet()))
        assertEquals(PatternPlan.Skip(PatternSkip.NO_SLOTS_ON), planPatternSuggest(TARGET, SlotBoard.empty(emptyList()), PatternParams(), none, emptySet()))
        val noSources = PatternParams(sources = RhymeSources(notes = false, dictionary = false))
        assertEquals(PatternPlan.Skip(PatternSkip.NO_SOURCES), planPatternSuggest(TARGET, board(), noSources, none, emptySet()))
    }

    @Test
    fun `the tag filter is sent normalized, blocks when invalid, and is ignored without notes`() {
        assertEquals("love & night", query(filter = ExpressionField("love night", 0)).tagFilter)
        val blocked = planPatternSuggest(TARGET, board(), PatternParams(), ExpressionField("love &", 0), emptySet())
        assertEquals(TagExprErrorCode.UNEXPECTED_END, (blocked as PatternPlan.Blocked).error.code)
        val dictOnly = PatternParams(sources = RhymeSources(notes = false, dictionary = true))
        assertNull(query(params = dictOnly, filter = ExpressionField("love &", 0)).tagFilter)
    }
}
