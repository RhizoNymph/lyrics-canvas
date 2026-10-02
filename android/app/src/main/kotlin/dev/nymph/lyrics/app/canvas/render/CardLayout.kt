package dev.nymph.lyrics.app.canvas.render

import dev.nymph.lyrics.logic.canvas.Rect
import dev.nymph.lyrics.model.GridCell
import dev.nymph.lyrics.model.Snippet

/**
 * Card geometry in card-local world units (multiply by the canvas zoom to draw). Mirrors
 * frontend/src/components/canvas/{gridConstants,gridDraw}.ts and SnippetCard.tsx.
 */
object CardMetrics {
    const val CELL_WIDTH: Double = 60.0
    const val CELL_HEIGHT: Double = 24.0
    const val PADDING: Double = 14.0
    const val HEADER_HEIGHT: Double = 30.0
    const val CORNER_RADIUS: Double = 8.0
    /** Drop-shadow offset, in screen units (not scaled by zoom, as on the web). */
    const val SHADOW_OFFSET: Double = 4.0
    const val SELECTION_STROKE: Double = 2.5

    const val TAG_PILL_HEIGHT: Double = 16.0
    const val TAG_PILL_GAP: Double = 4.0
    const val TAG_PILL_PAD_X: Double = 6.0
    /** Approximate glyph width of a 9-unit tag label (the web uses the same estimate). */
    const val TAG_CHAR_WIDTH: Double = 6.0

    const val CELL_FONT: Double = 13.0
    const val HEADER_FONT: Double = 11.0
    const val TAG_FONT: Double = 9.0
    const val COUNT_FONT: Double = 10.0
    const val FALLBACK_MAX_CHARS: Int = 200

    /** Below this on-screen cell font size (in dp) text is not drawn at all. */
    const val MIN_TEXT_SIZE: Double = 4.0
}

enum class CellTextKind { REST, STRESSED, NORMAL }

/** One cell's text and its top-left position. */
data class CellText(val text: String, val x: Double, val y: Double, val kind: CellTextKind)

data class CountText(val text: String, val x: Double, val y: Double)

data class TagPill(val bounds: Rect, val label: String, val color: String, val labelX: Double, val labelY: Double)

/** The beat grid's frame: [right] is clamped to the card's inner edge. */
data class GridFrame(val left: Double, val top: Double, val right: Double, val bottom: Double, val rows: Int, val columns: Int) {
    /** Even rows get a background stripe. */
    fun stripes(): List<Rect> = (0 until rows step 2).map {
        Rect(left, top + it * CardMetrics.CELL_HEIGHT, right - left, CardMetrics.CELL_HEIGHT)
    }

    fun horizontalLineYs(): List<Double> = (0..rows).map { top + it * CardMetrics.CELL_HEIGHT }

    /** Column lines up to the clamped right edge (one unit of tolerance, as on the web). */
    fun verticalLineXs(): List<Double> = (0..columns).map { left + it * CardMetrics.CELL_WIDTH }.takeWhile { it <= right + 1 }
}

data class CardLayout(
    val width: Double,
    val height: Double,
    val headerLabel: String,
    val headerLabelX: Double,
    val headerLabelY: Double,
    val tags: List<TagPill>,
    val grid: GridFrame?,
    val cells: List<CellText>,
    val counts: List<CountText>,
    /** Raw text shown when the snippet has no grid lines; null when there is nothing to show. */
    val fallbackText: String?,
    val fallbackX: Double,
    val fallbackY: Double,
    val fallbackWidth: Double,
)

/** Where a cell's text goes (placeCellText in gridDraw.ts). */
fun placeCellText(cell: GridCell, row: Int, col: Int, left: Double, top: Double): CellText {
    val x = left + col * CardMetrics.CELL_WIDTH + 4.0
    val y = top + row * CardMetrics.CELL_HEIGHT + 3.0
    if (cell.isRest) return CellText("·", x + CardMetrics.CELL_WIDTH * 0.35, y, CellTextKind.REST)
    val stressed = (cell.stress ?: 0) > 0
    return CellText(cell.syllable ?: "", x, y, if (stressed) CellTextKind.STRESSED else CellTextKind.NORMAL)
}

fun cardLayout(snippet: Snippet): CardLayout {
    val m = CardMetrics
    val w = snippet.width
    val gridLeft = m.PADDING
    val gridTop = m.HEADER_HEIGHT + m.PADDING
    val lines = snippet.gridData?.lines.orEmpty()

    val pillTop = (m.HEADER_HEIGHT - m.TAG_PILL_HEIGHT) / 2
    val pills = arrayOfNulls<TagPill>(snippet.tags.size)
    var tagX = w - m.PADDING
    for (i in snippet.tags.indices.reversed()) {
        val tag = snippet.tags[i]
        val pillW = m.TAG_PILL_PAD_X * 2 + tag.name.length * m.TAG_CHAR_WIDTH
        tagX -= pillW
        pills[i] = TagPill(
            bounds = Rect(tagX, pillTop, pillW, m.TAG_PILL_HEIGHT),
            label = tag.name,
            color = tag.color,
            labelX = tagX + m.TAG_PILL_PAD_X,
            labelY = pillTop + (m.TAG_PILL_HEIGHT - m.TAG_FONT) / 2,
        )
        tagX -= m.TAG_PILL_GAP
    }

    val grid = if (lines.isEmpty()) {
        null
    } else {
        val columns = maxOf(lines.maxOf { it.cells.size }, 1)
        GridFrame(
            left = gridLeft,
            top = gridTop,
            right = minOf(gridLeft + columns * m.CELL_WIDTH, w - m.PADDING),
            bottom = gridTop + lines.size * m.CELL_HEIGHT,
            rows = lines.size,
            columns = columns,
        )
    }

    val cells = lines.flatMapIndexed { row, line ->
        line.cells.mapIndexed { col, cell -> placeCellText(cell, row, col, gridLeft, gridTop) }
    }
    val counts = lines.mapIndexed { row, line ->
        CountText(line.syllableCount.toString(), w - m.PADDING - 14.0, gridTop + row * m.CELL_HEIGHT + 5.0)
    }
    val fallback = if (lines.isEmpty() && snippet.rawText.isNotEmpty()) snippet.rawText.take(m.FALLBACK_MAX_CHARS) else null

    return CardLayout(
        width = w,
        height = snippet.height,
        headerLabel = snippet.snippetType.name,
        headerLabelX = m.PADDING,
        headerLabelY = 7.0,
        tags = pills.filterNotNull(),
        grid = grid,
        cells = cells,
        counts = counts,
        fallbackText = fallback,
        fallbackX = m.PADDING,
        fallbackY = gridTop,
        fallbackWidth = w - m.PADDING * 2,
    )
}

/** Whether card text is legible at [scale] (canvas zoom); below it only shapes are drawn. */
fun textVisible(scale: Double): Boolean = CardMetrics.CELL_FONT * scale >= CardMetrics.MIN_TEXT_SIZE
