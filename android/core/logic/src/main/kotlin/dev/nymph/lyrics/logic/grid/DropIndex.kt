package dev.nymph.lyrics.logic.grid

import kotlin.math.floor

/** Gap index (0..cellCount) nearest to [x], measured from the left edge of cell 0 (halves round up, as JS Math.round). */
fun gapFromX(x: Double, cellWidth: Double, cellCount: Int): Int {
    if (cellWidth <= 0.0) return 0
    val gap = floor(x / cellWidth + 0.5)
    return gap.coerceIn(0.0, cellCount.toDouble()).toInt()
}

/**
 * Translate a drop gap (in the row's original cell coordinates) into the `to` argument of
 * [moveChunk] for the chunk `[start, end)`. Null when the drop would leave the chunk where it is.
 */
fun moveTargetFromGap(gap: Int, start: Int, end: Int): Int? {
    if (gap in start..end) return null
    return if (gap < start) gap else gap - (end - start)
}
