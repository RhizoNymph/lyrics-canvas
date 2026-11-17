package dev.nymph.lyrics.logic.score

import dev.nymph.lyrics.model.GridCell
import dev.nymph.lyrics.model.GridData
import dev.nymph.lyrics.model.score.Score
import dev.nymph.lyrics.model.score.ScoreLine
import dev.nymph.lyrics.model.score.ScoreNote

/** new line index -> old line index (by index for equal line counts, else LCS over line keys). */
private fun linePairs(old: GridData?, new: GridData?): Map<Int, Int> {
    val oldLines = old.scoreLines()
    val newLines = new.scoreLines()
    if (oldLines.size == newLines.size) return newLines.indices.associateWith { it }
    return lcsPairs(oldLines.map(::lineKey), newLines.map(::lineKey)).associate { (o, n) -> n to o }
}

private fun mappedNotes(oldCells: List<GridCell>, newCells: List<GridCell>, oldNotes: ScoreLine): List<ScoreNote> {
    val oldGroups = wordGroups(oldCells)
    val newGroups = wordGroups(newCells)
    val pairs = lcsPairs(oldGroups.map { wordKey(it.spelling) }, newGroups.map { wordKey(it.spelling) })
    val bySyllable = oldNotes.notes.associateBy { it.s }
    val candidates = mutableListOf<ScoreNote>()
    for ((o, n) in pairs) {
        val oldOrdinals = oldGroups[o].ordinals
        val newOrdinals = newGroups[n].ordinals
        for (r in 0 until minOf(oldOrdinals.size, newOrdinals.size)) {
            bySyllable[oldOrdinals[r]]?.let { candidates += it.copy(s = newOrdinals[r]) }
        }
    }
    return candidates.sortedBy { it.s }
}

/**
 * The score for [new] after a text or grid change (spec section 11): matched words keep their
 * syllables' notes, everything else is unplaced; a mapped note that would start before the
 * previous kept note ends is dropped. Err when [score] is not valid against [old].
 */
fun reconcile(score: Score, old: GridData?, new: GridData?): ScoreResult<Score> {
    validateScore(score, old)?.let { return ScoreResult.Err(it) }
    val oldLines = old.scoreLines()
    val pairs = linePairs(old, new)
    var prevEnd = 0
    val lines = new.scoreLines().mapIndexed { newIndex, newLine ->
        val oldIndex = pairs[newIndex] ?: return@mapIndexed ScoreLine()
        val kept = mutableListOf<ScoreNote>()
        for (note in mappedNotes(oldLines[oldIndex].cells, newLine.cells, score.lines[oldIndex])) {
            if (note.t >= prevEnd) {
                kept += note
                prevEnd = note.end
            }
        }
        ScoreLine(kept)
    }
    val result = score.copy(lines = lines)
    return validateScore(result, new).orOk { result }
}
