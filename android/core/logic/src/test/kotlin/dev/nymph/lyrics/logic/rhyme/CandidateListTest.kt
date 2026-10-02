package dev.nymph.lyrics.logic.rhyme

import dev.nymph.lyrics.model.GridLine
import dev.nymph.lyrics.model.Provenance
import dev.nymph.lyrics.model.RhymeCandidate
import dev.nymph.lyrics.model.RhymeLimits.PAGE_SIZE
import dev.nymph.lyrics.model.SourceName
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CandidateListTest {
    private fun cand(id: String) = RhymeCandidate(
        id = id, text = id, provenance = Provenance.DICTIONARY, ephemeral = true, syllableCount = 1, stress = "1",
        rhymeKey = emptyList(), syllables = emptyList(), gridLine = GridLine(emptyList()), score = 1.0, matchDepth = 1,
        bestAnchorId = "a", fitsSlot = true, alignment = emptyList(), source = SourceName.DICTIONARY,
    )

    private fun many(n: Int) = (0 until n).map { cand("c$it") }

    @Test
    fun `listRows returns the loaded candidates in order`() {
        assertEquals(listOf("x", "y"), listRows(listOf(cand("x"), cand("y")), 0, emptySet()).map { it.id })
    }

    @Test
    fun `listRows excludes dismissed ids`() {
        assertEquals(listOf("x", "z"), listRows(listOf(cand("x"), cand("y"), cand("z")), 0, setOf("y")).map { it.id })
    }

    @Test
    fun `listRows limits the window to the pages loaded so far (offset + page size)`() {
        assertEquals(PAGE_SIZE, listRows(many(100), 0, emptySet()).size)
        assertEquals(2 * PAGE_SIZE, listRows(many(100), PAGE_SIZE, emptySet()).size)
        assertEquals(5, listRows(many(5), PAGE_SIZE, emptySet()).size)
    }

    @Test
    fun `listRows applies the dismissed filter before the window so a dismissed row frees a slot`() {
        val rows = listRows(many(PAGE_SIZE + 1), 0, setOf("c0"))
        assertEquals(PAGE_SIZE, rows.size)
        assertEquals("c$PAGE_SIZE", rows.last().id)
    }

    @Test
    fun `shownRange is 0-0 with no rows`() {
        assertEquals(ShownRange(0, 0), shownRange(0, 0))
        assertEquals(ShownRange(0, 0), shownRange(0, 12))
    }

    @Test
    fun `shownRange counts from 1 to the number of rows`() {
        assertEquals(ShownRange(1, 5), shownRange(5, 77))
    }

    @Test
    fun `canLoadMore is true only while idle and another page exists`() {
        assertTrue(canLoadMore(false, 0, 41))
        assertFalse(canLoadMore(false, 0, 40))
        assertFalse(canLoadMore(true, 0, 41))
        assertFalse(canLoadMore(false, 40, 80))
        assertTrue(canLoadMore(false, 40, 81))
    }
}
