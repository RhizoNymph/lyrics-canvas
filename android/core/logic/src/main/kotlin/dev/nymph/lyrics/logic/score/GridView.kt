package dev.nymph.lyrics.logic.score

import dev.nymph.lyrics.logic.rests.REST_MARK
import dev.nymph.lyrics.model.GridCell
import dev.nymph.lyrics.model.GridData
import dev.nymph.lyrics.model.GridLine

/** A grid as the score algorithms read it (spec/score_format.md section 3); `null` has no lines. */
internal fun GridData?.scoreLines(): List<GridLine> = this?.lines ?: emptyList()

/** `syl[k]`: the cell index of the syllable with ordinal `k`. */
internal fun syllablePositions(cells: List<GridCell>): List<Int> = cells.indices.filter { !cells[it].isRest }

internal fun syllableCounts(grid: GridData?): List<Int> = grid.scoreLines().map { line -> line.cells.count { !it.isRest } }

/** For each `k` in `0 .. n-2`: the rest cells between syllables `k` and `k+1`. */
internal fun restsBetween(cells: List<GridCell>): List<Int> {
    val syl = syllablePositions(cells)
    return (0 until (syl.size - 1).coerceAtLeast(0)).map { syl[it + 1] - syl[it] - 1 }
}

/** A word of a line: its spelling (rebuild rule) and the ordinals of its syllables, in cell order. */
internal data class WordGroup(val spelling: String, val ordinals: List<Int>)

/** Groups by `word_index` in order of first appearance; a syllable without one is its own group. */
internal fun wordGroups(cells: List<GridCell>): List<WordGroup> {
    data class Acc(val ordinals: MutableList<Int> = mutableListOf(), val syllables: MutableList<String> = mutableListOf(), var word: String? = null)
    val order = mutableListOf<Any>()
    val groups = HashMap<Any, Acc>()
    var ordinal = 0
    for (cell in cells) {
        if (cell.isRest) continue
        val index = cell.wordIndex
        val key: Any = index ?: Anonymous(ordinal)
        val acc = groups.getOrPut(key) { Acc().also { order += key } }
        acc.ordinals += ordinal
        acc.syllables += cell.syllable ?: ""
        val word = cell.word
        if (index != null && acc.word == null && !word.isNullOrEmpty()) acc.word = word
        ordinal++
    }
    return order.map { key ->
        val acc = groups.getValue(key)
        WordGroup(acc.word ?: acc.syllables.joinToString(""), acc.ordinals.toList())
    }
}

private data class Anonymous(val ordinal: Int)

/** What a rest-only edit keeps per syllable: `(syllable, word_index)`. */
internal fun syllableIdentity(cells: List<GridCell>): List<Pair<String?, Int?>> =
    cells.filter { !it.isRest }.map { it.syllable to it.wordIndex }

/**
 * `rest_marks.rebuild_line_text` exactly: `_` per rest, each word once at its first syllable, spelled
 * by the first non-empty `word` of its cells, else its concatenated syllables; anonymous cells by
 * their syllable; empty pieces dropped; joined by single spaces.
 */
internal fun rebuildLineText(cells: List<GridCell>): String {
    val pieces = mutableListOf<String>()
    val emitted = HashSet<Int>()
    for (cell in cells) {
        if (cell.isRest) {
            pieces += REST_MARK.toString()
            continue
        }
        val index = cell.wordIndex
        if (index == null) {
            pieces += cell.syllable ?: ""
            continue
        }
        if (!emitted.add(index)) continue
        val spelled = cells.firstOrNull { it.wordIndex == index && !it.word.isNullOrEmpty() }?.word
        pieces += spelled ?: cells.filter { !it.isRest && it.wordIndex == index }.joinToString("") { it.syllable ?: "" }
    }
    return pieces.filter { it.isNotEmpty() }.joinToString(" ")
}
