package dev.nymph.lyrics.logic.rests

import dev.nymph.lyrics.logic.jsSplitWhitespace
import dev.nymph.lyrics.logic.jsTrim

/**
 * Rest marks: every `_` in a lyric line is one rest (an intentional empty grid cell) and also
 * separates words. The machine-checked definition is `rest_mark_vectors.json`, shared with the
 * backend and the web client (docs/features/rest_marks.md).
 */
const val REST_MARK: Char = '_'

/** One token of a line: a word (punctuation kept) or a rest. */
sealed interface LineToken {
    /** A word; [wordIndex] counts word tokens only, from 0, so rests never shift it. */
    data class Word(val text: String, val wordIndex: Int) : LineToken

    data object Rest : LineToken
}

/** Replace each `_` with ` _ `, split on whitespace (dropping empty pieces), `_` pieces are rests. */
fun tokenizeLine(line: String): List<LineToken> {
    val spaced = jsTrim(line.replace(REST_MARK.toString(), " $REST_MARK "))
    if (spaced.isEmpty()) return emptyList()
    var wordIndex = 0
    return jsSplitWhitespace(spaced).map { piece ->
        if (piece == REST_MARK.toString()) LineToken.Rest else LineToken.Word(piece, wordIndex++)
    }
}

/** The line's word tokens in order: what used to be `line.split()` (index = `word_index`). */
fun lineWordTokens(line: String): List<String> = tokenizeLine(line).mapNotNull { (it as? LineToken.Word)?.text }

/**
 * One line's display form: its words joined by single spaces, or null when the line had at least
 * one rest and no words (such lines are dropped from the display text).
 */
fun displayLine(line: String): String? {
    val tokens = tokenizeLine(line)
    val words = tokens.mapNotNull { (it as? LineToken.Word)?.text }
    if (words.isEmpty() && tokens.isNotEmpty()) return null
    return words.joinToString(" ")
}

/**
 * The text with its rest marks stripped: per line the word tokens joined by single spaces; lines
 * made only of rests are dropped, other empty lines (stanza breaks) kept. What search, the rhyme
 * anchors and the word bank read.
 */
fun displayText(text: String): String = text.split("\n").mapNotNull(::displayLine).joinToString("\n")
