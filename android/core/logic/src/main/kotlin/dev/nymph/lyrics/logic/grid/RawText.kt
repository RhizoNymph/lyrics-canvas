package dev.nymph.lyrics.logic.grid

import dev.nymph.lyrics.logic.rests.REST_MARK
import dev.nymph.lyrics.model.GridData
import dev.nymph.lyrics.model.GridLine

private class WordParts(var word: String?, val syllables: MutableList<String>)

/** One piece of the canonical text, in cell order: a rest mark, or the first cell of a word. */
private sealed interface Piece {
    data object Rest : Piece

    data class Word(val key: Int) : Piece
}

/**
 * The line's canonical text as implied by its cells (rest_mark_vectors.json `rebuild`): walking the
 * cells in order, a rest emits `_`; the first cell of each word (by `wordIndex`; a cell without one
 * is its own word) emits the word, spelled by its surface `word` when known, else by all its
 * syllables concatenated; later cells of that word emit nothing. Pieces are joined by single
 * spaces; words with no text are dropped.
 */
fun rebuildRawText(line: GridLine): String {
    val parts = HashMap<Int, WordParts>()
    val pieces = mutableListOf<Piece>()
    var anonymous = -1
    for (cell in line.cells) {
        if (cell.isRest) {
            pieces += Piece.Rest
            continue
        }
        val key = cell.wordIndex ?: anonymous--
        val entry = parts.getOrPut(key) { WordParts(null, mutableListOf()).also { pieces += Piece.Word(key) } }
        if (entry.word == null && cell.word != null) entry.word = cell.word
        entry.syllables += cell.syllable ?: ""
    }
    return pieces
        .map { piece ->
            when (piece) {
                Piece.Rest -> REST_MARK.toString()
                is Piece.Word -> parts.getValue(piece.key).let { it.word ?: it.syllables.joinToString("") }
            }
        }
        .filter { it.isNotEmpty() }
        .joinToString(" ")
}

/** The line with `rawText` replaced by [rebuildRawText]; the same line when it already matches. */
fun withRebuiltText(line: GridLine): GridLine {
    val rawText = rebuildRawText(line)
    return if (rawText == line.rawText) line else line.copy(rawText = rawText)
}

fun rebuildRawTextAll(grid: GridData): String = grid.lines.joinToString("\n", transform = ::rebuildRawText)
