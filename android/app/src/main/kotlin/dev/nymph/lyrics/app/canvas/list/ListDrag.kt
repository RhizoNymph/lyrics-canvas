package dev.nymph.lyrics.app.canvas.list

/** A laid-out list row in pixels: its index in the list, its top edge and its height. */
data class RowSpan(val index: Int, val top: Int, val size: Int) {
    val centre: Float get() = top + size / 2f
}

/** What one drag frame does to the dragged row. */
sealed interface DragStep {
    data object Stay : DragStep

    /**
     * Swap places with the neighbour at [index]; the row's layout moves by the neighbour's
     * height plus the gap, so the finger offset changes by [offsetShift] to keep it under the finger.
     */
    data class Swap(val index: Int, val offsetShift: Float) : DragStep
}

/**
 * The dragged row ([dragged], drawn [offset] px from its layout place) swaps with the neighbour
 * whose centre its own centre has passed: [below] when moving down, [above] when moving up.
 */
fun dragStep(dragged: RowSpan, offset: Float, above: RowSpan?, below: RowSpan?): DragStep {
    val centre = dragged.centre + offset
    if (below != null && offset > 0 && centre > below.centre) {
        // After the swap the row starts where `below` ended minus its own height.
        val moved = (below.top + below.size) - (dragged.top + dragged.size)
        return DragStep.Swap(below.index, -moved.toFloat())
    }
    if (above != null && offset < 0 && centre < above.centre) {
        val moved = dragged.top - above.top
        return DragStep.Swap(above.index, moved.toFloat())
    }
    return DragStep.Stay
}

/** [rows] with the item at [from] moved to [to] (both valid indexes). */
fun <T> List<T>.moved(from: Int, to: Int): List<T> {
    if (from == to) return this
    val next = toMutableList()
    next.add(to, next.removeAt(from))
    return next
}

/**
 * How far to scroll the list this frame while a dragged row is within [edge] px of the
 * viewport's top or bottom (negative = up), at most [maxStep] px; 0 elsewhere.
 */
fun edgeScroll(rowTop: Float, rowBottom: Float, viewportStart: Int, viewportEnd: Int, edge: Float, maxStep: Float): Float {
    val intoTop = viewportStart + edge - rowTop
    val intoBottom = rowBottom - (viewportEnd - edge)
    return when {
        intoTop > 0 -> -minOf(maxStep, intoTop / edge * maxStep)
        intoBottom > 0 -> minOf(maxStep, intoBottom / edge * maxStep)
        else -> 0f
    }
}
