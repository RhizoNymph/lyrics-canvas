package dev.nymph.lyrics.app.canvas.render

import dev.nymph.lyrics.logic.canvas.Rect
import dev.nymph.lyrics.logic.canvas.ViewSize
import dev.nymph.lyrics.logic.canvas.Viewport
import dev.nymph.lyrics.logic.canvas.cardScreenBounds
import dev.nymph.lyrics.logic.canvas.drawOrder
import dev.nymph.lyrics.model.Snippet

/** True when the rectangles overlap by a positive area (touching edges do not count). */
fun intersects(a: Rect, b: Rect): Boolean = a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom

/**
 * The cards to draw, in paint order: those whose screen rectangle, grown by [margin] screen units
 * (the drop shadow), overlaps the view.
 */
fun visibleCards(snippets: List<Snippet>, viewport: Viewport, viewSize: ViewSize, margin: Double = CardMetrics.SHADOW_OFFSET): List<Snippet> {
    val view = Rect(0.0, 0.0, viewSize.width, viewSize.height)
    return drawOrder(snippets).filter {
        val r = cardScreenBounds(it, 1.0, viewport)
        intersects(Rect(r.left - margin, r.top - margin, r.width + 2 * margin, r.height + 2 * margin), view)
    }
}

/** Screen positions of the background grid lines (InfiniteGrid.tsx). */
data class BackgroundGrid(val xs: List<Double>, val ys: List<Double>)

const val BACKGROUND_GRID_SPACING: Double = 50.0

/** Lines closer together than this (screen units) are not drawn. */
const val MIN_BACKGROUND_GRID_GAP: Double = 8.0

fun backgroundGridLines(viewport: Viewport, viewSize: ViewSize, spacing: Double = BACKGROUND_GRID_SPACING): BackgroundGrid {
    val gap = spacing * viewport.zoom
    if (gap < MIN_BACKGROUND_GRID_GAP) return BackgroundGrid(emptyList(), emptyList())
    fun lines(offset: Double, extent: Double): List<Double> = generateSequence(offset % gap) { it + gap }.takeWhile { it < extent }.toList()
    return BackgroundGrid(lines(viewport.x * viewport.zoom, viewSize.width), lines(viewport.y * viewport.zoom, viewSize.height))
}
