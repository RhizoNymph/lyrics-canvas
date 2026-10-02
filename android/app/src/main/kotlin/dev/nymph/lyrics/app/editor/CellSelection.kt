package dev.nymph.lyrics.app.editor

/**
 * A contiguous chunk of cells `[start, end)` in one editor row. Only [of] and [cell] build one,
 * so a selection is never empty or negative.
 */
@ConsistentCopyVisibility
data class CellSelection private constructor(val row: Int, val start: Int, val end: Int) {
    val size: Int get() = end - start

    operator fun contains(col: Int): Boolean = col in start until end

    /** The same chunk in another row (the row moved). */
    fun inRow(newRow: Int): CellSelection = if (newRow == row) this else CellSelection(newRow, start, end)

    /** The chunk shifted by [delta] cells; null if it would start before cell 0. */
    fun shiftedBy(delta: Int): CellSelection? = of(row, start + delta, end + delta)

    /** True when the chunk lies within a row of [cellCount] cells. */
    fun fitsIn(cellCount: Int): Boolean = end <= cellCount

    companion object {
        fun of(row: Int, start: Int, end: Int): CellSelection? =
            if (row >= 0 && start >= 0 && end > start) CellSelection(row, start, end) else null

        fun cell(row: Int, col: Int): CellSelection? = of(row, col, col + 1)
    }
}

/** Which end of a selection a handle drags. */
enum class SelectionEdge { START, END }

/**
 * A tap on cell [col] of [row]: selects that cell, or, when [extend] is on and the tap is in the
 * selected row, grows the selection to cover it (the web's shift-click).
 */
fun tapSelection(current: CellSelection?, row: Int, col: Int, extend: Boolean): CellSelection? {
    if (extend && current != null && current.row == row) {
        return CellSelection.of(row, minOf(current.start, col), maxOf(current.end, col + 1))
    }
    return CellSelection.cell(row, col)
}

/**
 * Drag a selection handle to cell [col] (clamped to `0 until cellCount`). The dragged edge never
 * crosses the other one: the selection keeps at least one cell.
 */
fun resizeSelection(current: CellSelection, edge: SelectionEdge, col: Int, cellCount: Int): CellSelection {
    if (cellCount <= 0) return current
    val c = col.coerceIn(0, cellCount - 1)
    val next = when (edge) {
        SelectionEdge.START -> CellSelection.of(current.row, minOf(c, current.end - 1), current.end)
        SelectionEdge.END -> CellSelection.of(current.row, current.start, maxOf(c + 1, current.start + 1))
    }
    return if (next == null || next == current) current else next
}

/**
 * Where index [index] ends up after the element at [from] moves to [to]
 * (the active-row rule of `reorderRows`, shared by the selection).
 */
fun indexAfterReorder(index: Int, from: Int, to: Int): Int = when {
    index == from -> to
    index in (from + 1)..to -> index - 1
    index in to until from -> index + 1
    else -> index
}
