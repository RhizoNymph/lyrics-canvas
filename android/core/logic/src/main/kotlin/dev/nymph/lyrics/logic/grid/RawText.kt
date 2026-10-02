package dev.nymph.lyrics.logic.grid

import dev.nymph.lyrics.model.GridData
import dev.nymph.lyrics.model.GridLine

private class WordParts(var word: String?, val syllables: MutableList<String>)

/**
 * The line's text as implied by its cells: words in order of first appearance of each
 * `wordIndex`, each spelled by its surface `word` when known, else by its syllables concatenated.
 * Rests are skipped; cells without a wordIndex count as their own words.
 */
fun rebuildRawText(line: GridLine): String {
    val parts = LinkedHashMap<Int, WordParts>()
    var anonymous = -1
    for (cell in line.cells) {
        if (cell.isRest) continue
        val key = cell.wordIndex ?: anonymous--
        val entry = parts.getOrPut(key) { WordParts(null, mutableListOf()) }
        if (entry.word == null && cell.word != null) entry.word = cell.word
        entry.syllables += cell.syllable ?: ""
    }
    return parts.values
        .map { it.word ?: it.syllables.joinToString("") }
        .filter { it.isNotEmpty() }
        .joinToString(" ")
}

/** The line with `rawText` replaced by [rebuildRawText]; the same line when it already matches. */
fun withRebuiltText(line: GridLine): GridLine {
    val rawText = rebuildRawText(line)
    return if (rawText == line.rawText) line else line.copy(rawText = rawText)
}

fun rebuildRawTextAll(grid: GridData): String = grid.lines.joinToString("\n", transform = ::rebuildRawText)
