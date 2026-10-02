package dev.nymph.lyrics.logic.grid

import dev.nymph.lyrics.model.GridData

/** Move `lines[from]` so it sits at index [to]. The same grid for a no-op or out-of-range request. */
fun reorderLines(grid: GridData, from: Int, to: Int): GridData {
    val n = grid.lines.size
    if (from == to || from !in 0 until n || to !in 0 until n) return grid
    val lines = grid.lines.toMutableList()
    val moved = lines.removeAt(from)
    lines.add(to, moved)
    return GridData(lines)
}
