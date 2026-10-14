package dev.nymph.lyrics.app.canvas.list

import dev.nymph.lyrics.app.canvas.render.CardMetrics
import dev.nymph.lyrics.model.Snippet

/**
 * Sizing of a list-mode row's card. A row draws the same card as the canvas
 * ([dev.nymph.lyrics.app.canvas.render.cardLayout] + `drawCard`), but sized to its content and to
 * the row instead of the snippet's canvas width / height. All widths and heights are card units;
 * a scale is dp per card unit.
 */
object ListCardMetrics {
    /** Room right of the grid for the per-line syllable counts (drawn at `width - PADDING - 14`). */
    const val COUNT_GUTTER: Double = 30.0

    /** Approximate glyph width of the bold 11-unit header label. */
    const val HEADER_CHAR_WIDTH: Double = 7.5

    /** Space between the header label and the first tag pill. */
    const val HEADER_GAP: Double = 12.0

    /** Wide grids are scaled down at most this far; beyond it the row scrolls horizontally. */
    const val MIN_SCALE: Double = 0.5
}

/** A row card's width and the scale it is drawn at. */
data class ListCardWidth(val width: Double, val scale: Double)

/** The narrowest card that shows the whole grid, the counts, the header label and every tag pill. */
fun listCardNeededWidth(snippet: Snippet): Double {
    val m = CardMetrics
    val lines = snippet.gridData?.lines.orEmpty()
    val grid = if (lines.isEmpty()) {
        0.0
    } else {
        maxOf(lines.maxOf { it.cells.size }, 1) * m.CELL_WIDTH + 2 * m.PADDING + ListCardMetrics.COUNT_GUTTER
    }
    val pills = snippet.tags.sumOf { m.TAG_PILL_PAD_X * 2 + it.name.length * m.TAG_CHAR_WIDTH } +
        m.TAG_PILL_GAP * (snippet.tags.size - 1).coerceAtLeast(0)
    val label = snippet.snippetType.name.length * ListCardMetrics.HEADER_CHAR_WIDTH
    val header = m.PADDING + label + (if (snippet.tags.isEmpty()) 0.0 else ListCardMetrics.HEADER_GAP + pills) + m.PADDING
    return maxOf(grid, header)
}

/**
 * Fit a card into a row [availableDp] wide: a card that fits fills the row at scale 1; a wider one
 * keeps its needed width and is scaled down, but not below [ListCardMetrics.MIN_SCALE]. Before the
 * row is measured (no width) the needed width is used at scale 1.
 */
fun listCardWidth(snippet: Snippet, availableDp: Double): ListCardWidth {
    val needed = listCardNeededWidth(snippet)
    if (availableDp <= 0.0) return ListCardWidth(needed, 1.0)
    if (needed <= availableDp) return ListCardWidth(availableDp, 1.0)
    return ListCardWidth(needed, maxOf(availableDp / needed, ListCardMetrics.MIN_SCALE))
}

/**
 * The card's height: header, padding, one cell per grid line and padding; or, without grid lines,
 * the measured raw-text height [fallbackTextHeight] (at least one cell); or, with neither, a header
 * over one empty cell.
 */
fun listCardHeight(snippet: Snippet, fallbackTextHeight: Double): Double {
    val m = CardMetrics
    val rows = snippet.gridData?.lines.orEmpty().size
    return when {
        rows > 0 -> m.HEADER_HEIGHT + m.PADDING + rows * m.CELL_HEIGHT + m.PADDING
        snippet.rawText.isNotEmpty() -> m.HEADER_HEIGHT + m.PADDING + maxOf(fallbackTextHeight, m.CELL_HEIGHT) + m.PADDING
        else -> m.HEADER_HEIGHT + m.CELL_HEIGHT
    }
}

/** The snippet as a list card: same content, list-sized (the copy is only drawn, never sent). */
fun listCard(snippet: Snippet, width: Double, height: Double): Snippet = snippet.copy(width = width, height = height)
