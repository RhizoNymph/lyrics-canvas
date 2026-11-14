package dev.nymph.lyrics.logic.rhyme

import dev.nymph.lyrics.model.RhymeBand
import dev.nymph.lyrics.model.RhymeOrder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class RhymeOrderTest {
    @Test
    fun `band edges are inclusive at the bottom`() {
        assertEquals(RhymeBand.PERFECT, bandOf(1.0))
        assertEquals(RhymeBand.PERFECT, bandOf(0.95))
        assertEquals(RhymeBand.STRONG, bandOf(0.9499))
        assertEquals(RhymeBand.STRONG, bandOf(0.85))
        assertEquals(RhymeBand.NEAR, bandOf(0.8499))
        assertEquals(RhymeBand.NEAR, bandOf(0.75))
        assertEquals(RhymeBand.LOOSE, bandOf(0.7499))
        assertEquals(RhymeBand.LOOSE, bandOf(0.0))
    }

    @Test
    fun `the server's band wins over the score`() {
        assertEquals(RhymeBand.LOOSE, rowBand(RhymeBand.LOOSE, 0.99))
        assertEquals(RhymeBand.STRONG, rowBand(null, 0.9))
    }

    @Test
    fun labels() {
        assertEquals(listOf("Perfect", "Strong", "Near", "Loose"), RhymeBand.entries.map { it.label })
        assertEquals(listOf("Rhyme", "Rhyme, then meaning"), RhymeOrder.entries.map { it.label })
        assertEquals("Meaning order unavailable", MEANING_UNAVAILABLE_NOTE)
    }

    @Test
    fun `rhyme order is sent by omission`() {
        assertNull(RhymeOrder.RHYME.requestValue)
        assertEquals(RhymeOrder.MEANING, RhymeOrder.MEANING.requestValue)
    }

    @Test
    fun presentation() {
        assertEquals(OrderPresentation.PLAIN, orderPresentation(RhymeOrder.RHYME, RhymeOrder.RHYME))
        assertEquals(OrderPresentation.BANDED, orderPresentation(RhymeOrder.MEANING, RhymeOrder.MEANING))
        assertEquals(OrderPresentation.MEANING_UNAVAILABLE, orderPresentation(RhymeOrder.MEANING, RhymeOrder.RHYME))
        assertEquals(OrderPresentation.PLAIN, ResultOrdering().presentation)
        assertEquals(OrderPresentation.MEANING_UNAVAILABLE, ResultOrdering(asked = RhymeOrder.MEANING).presentation)
    }

    private data class R(val id: String, val band: RhymeBand)

    private fun render(items: List<BandedItem<R>>) = items.map {
        when (it) {
            is BandedItem.Header -> "[${it.band.label}]"
            is BandedItem.Row -> it.value.id
        }
    }

    @Test
    fun `banded rows get a header where the band changes`() {
        val rows = listOf(R("a", RhymeBand.PERFECT), R("b", RhymeBand.PERFECT), R("c", RhymeBand.NEAR), R("d", RhymeBand.LOOSE))
        assertEquals(listOf("[Perfect]", "a", "b", "[Near]", "c", "[Loose]", "d"), render(withBandHeaders(rows, OrderPresentation.BANDED) { it.band }))
    }

    @Test
    fun `an appended page that continues a band adds no header`() {
        val page1 = listOf(R("a", RhymeBand.STRONG))
        val page2 = listOf(R("b", RhymeBand.STRONG), R("c", RhymeBand.NEAR))
        assertEquals(listOf("[Strong]", "a", "b", "[Near]", "c"), render(withBandHeaders(page1 + page2, OrderPresentation.BANDED) { it.band }))
    }

    @Test
    fun `plain and unavailable lists have no headers`() {
        val rows = listOf(R("a", RhymeBand.PERFECT), R("b", RhymeBand.NEAR))
        assertEquals(listOf("a", "b"), render(withBandHeaders(rows, OrderPresentation.PLAIN) { it.band }))
        assertEquals(listOf("a", "b"), render(withBandHeaders(rows, OrderPresentation.MEANING_UNAVAILABLE) { it.band }))
        assertEquals(emptyList<String>(), render(withBandHeaders(emptyList<R>(), OrderPresentation.BANDED) { it.band }))
    }

    @Test
    fun `rows carry their band`() {
        val item = withBandHeaders(listOf(R("a", RhymeBand.NEAR)), OrderPresentation.PLAIN) { it.band }.single()
        assertEquals(BandedItem.Row(R("a", RhymeBand.NEAR), RhymeBand.NEAR), item)
    }

    @Test
    fun `similarity percent`() {
        assertEquals(73, similarityPercent(0.7349))
        assertEquals(0, similarityPercent(-0.2))
        assertEquals(100, similarityPercent(1.0))
        assertNull(similarityPercent(null))
        assertNull(similarityPercent(Double.NaN))
    }
}
