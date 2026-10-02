package dev.nymph.lyrics.logic.grid

import dev.nymph.lyrics.model.GridCell
import dev.nymph.lyrics.model.GridLine

/** Insert a rest before cell [at] (clamped; past the end appends). */
fun insertRest(line: GridLine, at: Int): GridLine {
    val index = at.coerceIn(0, line.cells.size)
    val cells = line.cells.toMutableList().apply { add(index, GridCell.REST) }
    return line.copy(cells = cells)
}

/**
 * Remove cell [at]. Removing a syllable recounts, renumbers words and drops the surface `word`
 * from the rest of that word (it no longer spells it). The same line when out of range.
 */
fun removeCell(line: GridLine, at: Int): GridLine {
    if (at !in line.cells.indices) return line
    val removed = line.cells[at]
    val remaining = line.cells.toMutableList().apply { removeAt(at) }
    if (removed.isRest) return line.copy(cells = remaining)
    val orphaned = remaining.map { cell ->
        if (!cell.isRest && cell.word != null && cell.wordIndex == removed.wordIndex) cell.copy(word = null) else cell
    }
    val cells = rebaseWordIndexes(orphaned)
    return line.copy(cells = cells, syllableCount = countSyllables(cells))
}
