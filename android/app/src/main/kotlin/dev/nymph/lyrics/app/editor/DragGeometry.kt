package dev.nymph.lyrics.app.editor

import dev.nymph.lyrics.logic.grid.gapFromX
import kotlin.math.floor

/**
 * Measured geometry of one row's cell strip, in the strip's content coordinates (px): cells are
 * laid out left to right from [originX], each [cellWidth] wide. Built from the first cell's
 * bounds (`onGloballyPositioned`); null until the strip has been laid out.
 */
data class CellMetrics(val originX: Float, val cellWidth: Float, val cellHeight: Float) {
    init {
        require(cellWidth > 0f && cellHeight > 0f) { "cell size must be positive" }
    }

    /** Left edge of gap [gap] (0..cellCount). */
    fun gapX(gap: Int): Float = originX + gap * cellWidth
}

/** The cell under content x [x], or null outside `0 until cellCount`. */
fun cellIndexAt(x: Float, metrics: CellMetrics, cellCount: Int): Int? {
    val col = floor((x - metrics.originX) / metrics.cellWidth).toInt()
    return if (x >= metrics.originX && col in 0 until cellCount) col else null
}

/** The cell a handle dragged to content x [x] points at, clamped into the row. */
fun cellIndexClamped(x: Float, metrics: CellMetrics, cellCount: Int): Int =
    floor((x - metrics.originX) / metrics.cellWidth).toInt().coerceIn(0, maxOf(cellCount - 1, 0))

/** The drop gap (0..cellCount) nearest to content x [x], via the core `gapFromX`. */
fun dropGapAt(x: Float, metrics: CellMetrics, cellCount: Int): Int =
    gapFromX((x - metrics.originX).toDouble(), metrics.cellWidth.toDouble(), cellCount)

/**
 * The selection handle under a press at ([x], [y]), if any. Handles sit below the cells (y at or
 * past `cellHeight`) at the selection's left edge (START) and right edge (END); [slop] is the
 * horizontal hit radius. When both are in reach, the nearer one wins.
 */
fun handleAt(x: Float, y: Float, metrics: CellMetrics, selection: CellSelection, slop: Float): SelectionEdge? {
    if (y < metrics.cellHeight) return null
    val startDist = kotlin.math.abs(x - metrics.gapX(selection.start))
    val endDist = kotlin.math.abs(x - metrics.gapX(selection.end))
    return when {
        startDist > slop && endDist > slop -> null
        startDist < endDist -> SelectionEdge.START
        else -> SelectionEdge.END
    }
}

/**
 * Auto-scroll step for a drag at viewport x [x] in a strip [viewportWidth] wide: negative inside
 * the left [edgeZone], positive inside the right one, growing linearly to [maxStep] at (and past)
 * the edge; zero in the middle. A strip narrower than two zones splits at its centre.
 */
fun edgeAutoScrollDelta(x: Float, viewportWidth: Float, edgeZone: Float, maxStep: Float): Float {
    if (viewportWidth <= 0f || edgeZone <= 0f) return 0f
    val zone = minOf(edgeZone, viewportWidth / 2f)
    return when {
        x < zone -> -maxStep * ((zone - x) / zone).coerceAtMost(1f)
        x > viewportWidth - zone -> maxStep * ((x - (viewportWidth - zone)) / zone).coerceAtMost(1f)
        else -> 0f
    }
}

/** Vertical extent of one laid-out row (px, in the rows column's coordinates). */
data class RowSpan(val top: Float, val bottom: Float) {
    val center: Float get() = (top + bottom) / 2f
}

/**
 * Target index for row [from] dragged by [dragOffsetY]: the dragged row passes a neighbour once its
 * centre enters that neighbour's span (half a row of travel for equal rows). A row above [from]
 * stays above while the centre is at or below its bottom; a row below moves above once the centre
 * is past its top. Rows not yet measured (null) keep their place.
 */
fun reorderTargetIndex(spans: List<RowSpan?>, from: Int, dragOffsetY: Float): Int {
    val dragged = spans.getOrNull(from) ?: return from
    val center = dragged.center + dragOffsetY
    var target = 0
    spans.forEachIndexed { i, span ->
        val above = when {
            i == from -> false
            span == null -> i < from
            i < from -> center >= span.bottom
            else -> center > span.top
        }
        if (above) target++
    }
    return target
}
