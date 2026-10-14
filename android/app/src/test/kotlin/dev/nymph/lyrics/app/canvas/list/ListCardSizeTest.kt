package dev.nymph.lyrics.app.canvas.list

import dev.nymph.lyrics.app.canvas.render.CardMetrics
import dev.nymph.lyrics.app.canvas.render.cardLayout
import dev.nymph.lyrics.app.fakes.line
import dev.nymph.lyrics.app.fakes.snippet
import dev.nymph.lyrics.app.fakes.tag
import dev.nymph.lyrics.model.SnippetType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ListCardSizeTest {
    private val m = CardMetrics

    /** A grid snippet with [columns] cells in its longest line and [rows] lines. */
    private fun grid(columns: Int, rows: Int = 1) =
        snippet("g", lines = List(rows) { line(*Array(columns) { "la:0:$it" }) }, width = 120.0, height = 40.0)

    @Test
    fun `needed width covers the grid, its padding and the count gutter`() {
        assertEquals(4 * m.CELL_WIDTH + 2 * m.PADDING + ListCardMetrics.COUNT_GUTTER, listCardNeededWidth(grid(4)))
    }

    @Test
    fun `needed width covers the header label and the tag pills`() {
        val tags = listOf(tag("a-long-tag-name"), tag("another-long-one"), tag("third"))
        val s = snippet("t", type = SnippetType.FREESTYLE, tags = tags)
        val pills = tags.sumOf { m.TAG_PILL_PAD_X * 2 + it.name.length * m.TAG_CHAR_WIDTH } + m.TAG_PILL_GAP * (tags.size - 1)
        val label = "FREESTYLE".length * ListCardMetrics.HEADER_CHAR_WIDTH
        assertEquals(m.PADDING + label + ListCardMetrics.HEADER_GAP + pills + m.PADDING, listCardNeededWidth(s))
    }

    @Test
    fun `a card that fits fills the row at scale 1`() {
        val fit = listCardWidth(grid(4), availableDp = 400.0)
        assertEquals(ListCardWidth(400.0, 1.0), fit)
    }

    @Test
    fun `a card wider than the row is scaled down to fit`() {
        val needed = listCardNeededWidth(grid(8))
        val fit = listCardWidth(grid(8), availableDp = 400.0)
        assertEquals(needed, fit.width)
        assertEquals(400.0 / needed, fit.scale, 1e-9)
        assertEquals(400.0, fit.width * fit.scale, 1e-9)
    }

    @Test
    fun `scaling stops at the minimum so text stays legible`() {
        val fit = listCardWidth(grid(40), availableDp = 300.0)
        assertEquals(ListCardMetrics.MIN_SCALE, fit.scale)
        assertTrue(fit.width * fit.scale > 300.0)
    }

    @Test
    fun `no width yet keeps the needed width at scale 1`() {
        val fit = listCardWidth(grid(4), availableDp = 0.0)
        assertEquals(ListCardWidth(listCardNeededWidth(grid(4)), 1.0), fit)
    }

    @Test
    fun `grid height is header, padding, one cell per line and padding`() {
        assertEquals(m.HEADER_HEIGHT + m.PADDING + 3 * m.CELL_HEIGHT + m.PADDING, listCardHeight(grid(2, rows = 3), fallbackTextHeight = 999.0))
    }

    @Test
    fun `raw text height comes from the measured text, at least one cell`() {
        val s = snippet("r", rawText = "loose words")
        assertEquals(m.HEADER_HEIGHT + m.PADDING + 51.0 + m.PADDING, listCardHeight(s, fallbackTextHeight = 51.0))
        assertEquals(m.HEADER_HEIGHT + m.PADDING + m.CELL_HEIGHT + m.PADDING, listCardHeight(s, fallbackTextHeight = 3.0))
    }

    @Test
    fun `an empty snippet is a header and one empty cell`() {
        assertEquals(m.HEADER_HEIGHT + m.CELL_HEIGHT, listCardHeight(snippet("e"), fallbackTextHeight = 0.0))
    }

    @Test
    fun `the list card is the snippet resized, so cardLayout keeps the grid unclamped`() {
        val s = grid(8)
        val fit = listCardWidth(s, availableDp = 400.0)
        val card = listCard(s, fit.width, listCardHeight(s, 0.0))
        val layout = cardLayout(card)
        assertEquals(m.PADDING + 8 * m.CELL_WIDTH, layout.grid!!.right)
        // The per-line counts sit right of the grid.
        assertTrue(layout.counts.single().x > layout.grid.right)
        assertEquals(s.id, card.id)
        assertEquals(s.gridData, card.gridData)
    }
}
