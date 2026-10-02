package dev.nymph.lyrics.logic.rhyme

import dev.nymph.lyrics.logic.jsSlice
import dev.nymph.lyrics.logic.jsSplitWhitespace
import dev.nymph.lyrics.logic.jsTrim
import dev.nymph.lyrics.model.AnchorRange
import dev.nymph.lyrics.model.GridCell
import dev.nymph.lyrics.model.GridLine
import dev.nymph.lyrics.model.Snippet
import dev.nymph.lyrics.model.SnippetAnchorRef

/**
 * The anchor chip's word-click state. Partial-line anchors are picked by tapping a start word and
 * an end word; ranges snap to word boundaries and are stored with an exclusive end.
 */
sealed interface RangeClickState {
    /** The range so far, if any. */
    val range: AnchorRange?

    /** True after the first click: the next click on the same line closes the range. */
    val awaitingEnd: Boolean

    /** No range: the whole last line anchors the query. */
    data object None : RangeClickState {
        override val range: AnchorRange? get() = null
        override val awaitingEnd: Boolean get() = false
    }

    /** One word picked; the next click on its line sets the end. */
    data class AwaitingEnd(override val range: AnchorRange) : RangeClickState {
        override val awaitingEnd: Boolean get() = true
    }

    data class Complete(override val range: AnchorRange) : RangeClickState {
        override val awaitingEnd: Boolean get() = false
    }
}

/** Two inclusive word clicks on one line, in any order, as a stored (exclusive-end) range. */
fun wordSpan(lineIndex: Int, a: Int, b: Int): AnchorRange = AnchorRange(lineIndex, minOf(a, b), maxOf(a, b) + 1)

/**
 * Click-to-range transitions:
 * - no range, or a click on another line: start a single-word range there and await the end;
 * - awaiting the end: the start word again clears; another word closes the range;
 * - complete range: the start word again clears; another word starts over from it.
 */
fun clickWord(state: RangeClickState, lineIndex: Int, wordIndex: Int): RangeClickState {
    val range = state.range
    if (range == null || range.lineIndex != lineIndex) {
        return RangeClickState.AwaitingEnd(wordSpan(lineIndex, wordIndex, wordIndex))
    }
    if (wordIndex == range.wordStart) return RangeClickState.None
    return if (state.awaitingEnd) {
        RangeClickState.Complete(wordSpan(lineIndex, range.wordStart, wordIndex))
    } else {
        RangeClickState.AwaitingEnd(wordSpan(lineIndex, wordIndex, wordIndex))
    }
}

fun cellInRange(cell: GridCell, range: AnchorRange): Boolean {
    val index = cell.wordIndex
    return !cell.isRest && index != null && index >= range.wordStart && index < range.wordEnd
}

/**
 * The cells of [line] whose word index falls in the range, re-based to it, with leading and
 * trailing rests dropped and interior rests kept (the server slices the echoed grid line the same way).
 */
fun sliceGridLine(line: GridLine, range: AnchorRange): GridLine {
    val cells = mutableListOf<GridCell>()
    for (cell in line.cells) {
        if (cell.isRest) {
            if (cells.isNotEmpty()) cells += cell
            continue
        }
        if (cellInRange(cell, range)) cells += cell.copy(wordIndex = cell.wordIndex!! - range.wordStart)
    }
    while (cells.isNotEmpty() && cells.last().isRest) cells.removeAt(cells.lastIndex)
    return GridLine(
        cells = cells,
        syllableCount = cells.count { !it.isRest },
        rawText = lineWords(line).jsSlice(range.wordStart, range.wordEnd).joinToString(" "),
    )
}

/** The line's words as the server tokenises them (whitespace split of the stripped line). */
fun lineWords(line: GridLine): List<String> {
    val text = jsTrim(line.rawText)
    return if (text.isEmpty()) emptyList() else jsSplitWhitespace(text)
}

fun lineWordCount(line: GridLine): Int = lineWords(line).size

fun gridLineOf(snippet: Snippet, lineIndex: Int): GridLine? = snippet.gridData?.lines?.getOrNull(lineIndex)

/** The selected words of the snippet line, or null when the line does not exist. */
fun rangeText(snippet: Snippet, range: AnchorRange): String? {
    val line = gridLineOf(snippet, range.lineIndex) ?: return null
    return lineWords(line).jsSlice(range.wordStart, range.wordEnd).joinToString(" ")
}

/** True when the range addresses existing words of an existing line (what the server will accept). */
fun rangeFits(snippet: Snippet, range: AnchorRange): Boolean {
    val line = gridLineOf(snippet, range.lineIndex) ?: return false
    return range.wordStart >= 0 && range.wordStart < range.wordEnd && range.wordEnd <= lineWordCount(line)
}

/** The request anchor for a selected snippet: its whole last line, or the given word range. */
fun anchorRefFor(snippet: Snippet, range: AnchorRange?): SnippetAnchorRef =
    if (range == null) {
        SnippetAnchorRef(snippet.id, lineIndex = null)
    } else {
        SnippetAnchorRef(snippet.id, range.lineIndex, range.wordStart, range.wordEnd)
    }
