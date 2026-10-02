package dev.nymph.lyrics.logic.grid

import dev.nymph.lyrics.logic.jsSplitWhitespace
import dev.nymph.lyrics.logic.jsTrim
import dev.nymph.lyrics.model.GridCell
import dev.nymph.lyrics.model.GridData
import dev.nymph.lyrics.model.GridLine

/**
 * Renumber `wordIndex` by order of first appearance (0, 1, 2, ...). Syllables that shared an
 * index keep sharing one, even when another word's cells now sit between them. Rests and
 * index-less cells are returned as-is.
 */
fun rebaseWordIndexes(cells: List<GridCell>): List<GridCell> {
    val remap = HashMap<Int, Int>()
    return cells.map { cell ->
        val index = cell.wordIndex
        if (cell.isRest || index == null) return@map cell
        val next = remap.getOrPut(index) { remap.size }
        if (index == next) cell else cell.copy(wordIndex = next)
    }
}

/** Number of sounding (non-rest) cells. */
fun countSyllables(cells: List<GridCell>): Int = cells.count { !it.isRest }

/**
 * Fill `cell.word` from the whitespace-split `rawText` at `wordIndex` where it is missing. Only
 * meaningful while rawText and wordIndex agree (a fresh grid, or one rebuilt by [withRebuiltText]).
 * Returns the same line when nothing changes.
 */
fun attachWords(line: GridLine): GridLine {
    val text = jsTrim(line.rawText)
    if (text.isEmpty()) return line
    val words = jsSplitWhitespace(text)
    var changed = false
    val cells = line.cells.map { cell ->
        val index = cell.wordIndex
        if (cell.isRest || cell.word != null || index == null) return@map cell
        val word = words.getOrNull(index) ?: return@map cell
        changed = true
        cell.copy(word = word)
    }
    return if (changed) line.copy(cells = cells) else line
}

fun attachWordsAll(grid: GridData): GridData = GridData(grid.lines.map(::attachWords))
