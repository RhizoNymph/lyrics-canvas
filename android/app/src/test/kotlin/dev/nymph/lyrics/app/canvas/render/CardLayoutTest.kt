package dev.nymph.lyrics.app.canvas.render

import dev.nymph.lyrics.app.fakes.line
import dev.nymph.lyrics.app.fakes.snippet
import dev.nymph.lyrics.app.fakes.tag
import dev.nymph.lyrics.logic.canvas.Rect
import dev.nymph.lyrics.model.GridCell
import dev.nymph.lyrics.model.GridLine
import dev.nymph.lyrics.model.SnippetType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CardLayoutTest {
    @Test
    fun `header label is the upper-case type at padding, 7`() {
        val layout = cardLayout(snippet("a", type = SnippetType.CHORUS))
        assertEquals("CHORUS", layout.headerLabel)
        assertEquals(14.0, layout.headerLabelX)
        assertEquals(7.0, layout.headerLabelY)
    }

    @Test
    fun `tag pills are laid out right to left from the right padding`() {
        // width 300: first pill from the right is "bb" (2 chars => 12 + 12 = 24 wide).
        val layout = cardLayout(snippet("a", width = 300.0, tags = listOf(tag("a"), tag("bb", "#00ff00"))))
        val (first, second) = layout.tags
        assertEquals(Rect(300.0 - 14.0 - 24.0, 7.0, 24.0, 16.0), second.bounds)
        assertEquals("bb", second.label)
        assertEquals("#00ff00", second.color)
        // "a" pill: 18 wide, 4 gap left of "bb".
        assertEquals(Rect(300.0 - 14.0 - 24.0 - 4.0 - 18.0, 7.0, 18.0, 16.0), first.bounds)
        assertEquals(first.bounds.left + 6.0, first.labelX)
        assertEquals(7.0 + 3.5, first.labelY)
    }

    @Test
    fun `cells are placed like placeCellText with stress and rest kinds`() {
        val grid = listOf(GridLine(listOf(GridCell("cat", 1, 0), GridCell.REST, GridCell("ty", 0, 0)), 2, "catty"))
        val layout = cardLayout(snippet("a", lines = grid))
        val top = 30.0 + 14.0
        assertEquals(
            listOf(
                CellText("cat", 14.0 + 4.0, top + 3.0, CellTextKind.STRESSED),
                CellText("·", 14.0 + 60.0 + 4.0 + 60.0 * 0.35, top + 3.0, CellTextKind.REST),
                CellText("ty", 14.0 + 120.0 + 4.0, top + 3.0, CellTextKind.NORMAL),
            ),
            layout.cells,
        )
    }

    @Test
    fun `secondary stress is drawn as stressed and a missing syllable as empty`() {
        val grid = listOf(GridLine(listOf(GridCell(null, 2, 0)), 1, "x"))
        assertEquals(CellTextKind.STRESSED, cardLayout(snippet("a", lines = grid)).cells.single().kind)
        assertEquals("", cardLayout(snippet("a", lines = grid)).cells.single().text)
    }

    @Test
    fun `per-line syllable counts sit in the count column`() {
        val layout = cardLayout(snippet("a", width = 300.0, lines = listOf(line("a:1:0"), line("b:0:0", "c:1:1"))))
        assertEquals(
            listOf(CountText("1", 300.0 - 14.0 - 14.0, 44.0 + 5.0), CountText("2", 300.0 - 14.0 - 14.0, 44.0 + 24.0 + 5.0)),
            layout.counts,
        )
    }

    @Test
    fun `grid frame is clamped to the card's inner right edge`() {
        val cells = List(10) { GridCell("x", 0, it) }
        val layout = cardLayout(snippet("a", width = 300.0, lines = listOf(GridLine(cells, 10, ""), GridLine(cells.take(2), 2, ""))))
        val grid = layout.grid!!
        assertEquals(14.0, grid.left)
        assertEquals(44.0, grid.top)
        assertEquals(286.0, grid.right)
        assertEquals(44.0 + 48.0, grid.bottom)
        assertEquals(listOf(44.0, 68.0, 92.0), grid.horizontalLineYs())
        // Columns stop once past the clamped right edge (+1 tolerance).
        assertEquals(listOf(14.0, 74.0, 134.0, 194.0, 254.0), grid.verticalLineXs())
        assertEquals(listOf(Rect(14.0, 44.0, 272.0, 24.0)), grid.stripes())
    }

    @Test
    fun `no grid and the raw text fallback when there are no lines`() {
        val long = "x".repeat(250)
        val layout = cardLayout(snippet("a", rawText = long))
        assertNull(layout.grid)
        assertEquals(200, layout.fallbackText!!.length)
        assertEquals(14.0, layout.fallbackX)
        assertEquals(44.0, layout.fallbackY)
        assertEquals(300.0 - 28.0, layout.fallbackWidth)
    }

    @Test
    fun `blank snippet has neither grid nor fallback`() {
        val layout = cardLayout(snippet("a"))
        assertNull(layout.grid)
        assertNull(layout.fallbackText)
        assertTrue(layout.cells.isEmpty())
    }

    @Test
    fun `text is hidden once it would be under 4 pixels`() {
        assertTrue(textVisible(1.0))
        assertTrue(textVisible(0.31))
        assertFalse(textVisible(0.3))
    }
}
