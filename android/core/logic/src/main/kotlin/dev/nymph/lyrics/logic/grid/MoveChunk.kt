package dev.nymph.lyrics.logic.grid

import dev.nymph.lyrics.model.GridLine

/**
 * Move `cells[start, end)` so the chunk begins at index [to], where [to] is an index into the line
 * with the chunk removed (clamped into range). Rests are ordinary cells, so dragging over them
 * shifts timing and dragging past other syllables reorders words. Word indexes are re-based by
 * first appearance and `syllableCount` recomputed; `rawText` is left for [withRebuiltText].
 * Returns the same line for an empty or out-of-range chunk.
 */
fun moveChunk(line: GridLine, start: Int, end: Int, to: Int): GridLine {
    val n = line.cells.size
    if (start < 0 || end > n || end <= start) return line
    val chunk = line.cells.subList(start, end)
    val rest = line.cells.subList(0, start) + line.cells.subList(end, n)
    val target = to.coerceIn(0, rest.size)
    val cells = rebaseWordIndexes(rest.subList(0, target) + chunk + rest.subList(target, rest.size))
    return line.copy(cells = cells, syllableCount = countSyllables(cells))
}
