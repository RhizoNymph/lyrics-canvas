package dev.nymph.lyrics.logic.rhyme.pattern

import dev.nymph.lyrics.logic.canvas.Point
import dev.nymph.lyrics.logic.rhyme.pattern.PatternFx.SLOTS
import dev.nymph.lyrics.logic.rhyme.pattern.PatternFx.TARGET
import dev.nymph.lyrics.logic.rhyme.pattern.PatternFx.candidate
import dev.nymph.lyrics.logic.rhyme.pattern.PatternFx.gridLine
import dev.nymph.lyrics.model.DerivedFrom
import dev.nymph.lyrics.model.LineMatch
import dev.nymph.lyrics.model.LineMatchHit
import dev.nymph.lyrics.model.PatternCandidate
import dev.nymph.lyrics.model.PatternSlotResult
import dev.nymph.lyrics.model.PatternSuggestResponse
import dev.nymph.lyrics.model.Provenance
import dev.nymph.lyrics.model.RhymeK
import dev.nymph.lyrics.model.RhymeUnit
import dev.nymph.lyrics.model.SnippetId
import dev.nymph.lyrics.model.SnippetType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PatternResultsTest {
    private fun match(id: String, vararg slots: String, score: Double = 0.8) = LineMatch(
        unit = RhymeUnit(id, "ready for the weather", Provenance.NOTE, false, 6, "101010", listOf("ER"), emptyList(), gridLine("ready for the weather"), SnippetId("n1"), 0, 0),
        hits = slots.mapIndexed { i, s -> LineMatchHit(s, i, i + 1, "w$i", score) },
        slotsHit = slots.size,
        score = score,
    )

    private fun response(columns: Map<String, Pair<List<PatternCandidate>, Int>>, lines: List<LineMatch> = emptyList(), lineTotal: Int = lines.size) =
        PatternSuggestResponse(
            slots = SLOTS.filter { it.id in columns }.map { PatternSlotResult(it, columns.getValue(it.id).first, columns.getValue(it.id).second) },
            lineMatches = lines,
            lineMatchTotal = lineTotal,
            elapsedMs = 1.0,
        )

    @Test
    fun `the first page becomes one column per slot with dismissed rows hidden`() {
        val (columns, lines) = firstPage(
            response(mapOf("s0" to (listOf(candidate("a"), candidate("b")) to 40), "s1" to (listOf(candidate("c")) to 1)), listOf(match("note:n1:0:0", "s0", "s1"), match("note:n2:0:0", "s0", "s2")), 45),
            dismissed = setOf("b", "note:n2:0:0"),
        )
        assertEquals(listOf("s0", "s1"), columns.map { it.id.value })
        assertEquals(listOf("a"), columns[0].candidates.map { it.id })
        assertTrue(columns[0].canLoadMore)
        assertFalse(columns[1].canLoadMore)
        assertEquals(listOf("note:n1:0:0"), lines.matches.map { it.unit.id })
        assertEquals(45, lines.total)
        assertTrue(lines.canLoadMore)
    }

    @Test
    fun `a slot page appends to its column only, skipping rows already shown`() {
        val (columns, _) = firstPage(response(mapOf("s0" to (listOf(candidate("a")) to 61), "s1" to (listOf(candidate("c")) to 1))), emptySet())
        val next = appendSlotPage(columns, SlotId("s0"), 30, response(mapOf("s0" to (listOf(candidate("a"), candidate("d"), candidate("x")) to 61), "s1" to (listOf(candidate("z")) to 1))), setOf("x"))
        assertEquals(listOf("a", "d"), next[0].candidates.map { it.id })
        assertEquals(30, next[0].offset)
        assertEquals(60, next[0].nextOffset)
        assertTrue(next[0].canLoadMore)
        assertEquals(listOf("c"), next[1].candidates.map { it.id })
    }

    @Test
    fun `a line match page appends`() {
        val first = LineMatchList(listOf(match("m1", "s0", "s1")), 31)
        val next = appendLineMatchPage(first, 30, response(emptyMap(), listOf(match("m1", "s0", "s1"), match("m2", "s1", "s2")), 31), emptySet())
        assertEquals(listOf("m1", "m2"), next.matches.map { it.unit.id })
        assertFalse(next.canLoadMore)
        assertEquals(listOf("m2"), next.without("m1").matches.map { it.unit.id })
    }

    @Test
    fun `a line match materializes like a note candidate with the pattern as anchor`() {
        val slots = listOf(SLOTS[0].copy(k = RhymeK.K3), SLOTS[1].copy(k = RhymeK.K2), SLOTS[2].copy(k = RhymeK.K1))
        val request = lineMatchRequest(match("note:n1:0:0", "s0", "s1", score = 0.85), TARGET, slots, "I keep it steady / already heavy", Point(5.0, 6.0))
        assertEquals("ready for the weather", request.rawText)
        assertEquals(SnippetType.FREESTYLE, request.snippetType)
        assertEquals(5.0 to 6.0, request.positionX to request.positionY)
        assertEquals(DerivedFrom(Provenance.NOTE, "note:n1:0:0", 0.85, 2, 2, "pattern:a:0-3", "I keep it steady / already heavy"), request.derivedFrom)
    }
}
