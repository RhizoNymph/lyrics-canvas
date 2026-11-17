package dev.nymph.lyrics.logic.score

import dev.nymph.lyrics.model.GridCell
import dev.nymph.lyrics.model.GridData
import dev.nymph.lyrics.model.score.Score
import dev.nymph.lyrics.model.score.ScoreLine
import dev.nymph.lyrics.model.score.ScoreNote

/** The note of syllable [s] in this line, if placed. */
fun ScoreLine.noteFor(s: Int): ScoreNote? = notes.firstOrNull { it.s == s }

private fun linkedCells(score: Score, cells: List<GridCell>, line: ScoreLine): List<GridCell> {
    val syl = syllablePositions(cells)
    if (syl.isEmpty()) return cells
    val notes = line.notes.associateBy { it.s }
    val out = cells.subList(0, syl[0] + 1).toMutableList()
    for (k in 0 until syl.size - 1) {
        val run = cells.subList(syl[k] + 1, syl[k + 1])
        val left = notes[k]
        val right = notes[k + 1]
        if (left != null && right != null) {
            val target = (right.t - left.end) / beatUnit(score, left.end)
            out += run.take(target)
            repeat(target - run.size) { out += GridCell.REST }
        } else {
            out += run
        }
        out += cells[syl[k + 1]]
    }
    out += cells.subList(syl.last() + 1, cells.size)
    return out
}

/**
 * Score -> grid (spec/score_format.md section 8): the rest count between every two consecutive
 * placed syllables follows the score; other cells are kept. A line whose cells do not change is
 * returned as it is (its `raw_text` too); a changed line gets the rebuilt `raw_text`.
 */
fun applyRestLink(score: Score, grid: GridData?): ScoreResult<GridData?> {
    validateScore(score, grid)?.let { return ScoreResult.Err(it) }
    if (grid == null) return ScoreResult.Ok(null)
    val lines = grid.lines.zip(score.lines) { line, scoreLine ->
        val linked = linkedCells(score, line.cells, scoreLine)
        if (linked == line.cells) line else line.copy(cells = linked, rawText = rebuildLineText(linked))
    }
    return ScoreResult.Ok(grid.copy(lines = lines))
}

/** Same lines with the same syllables (`syllable` and `word_index`); only rest cells differ. */
fun isRestOnlyEdit(old: GridData?, new: GridData?): Boolean {
    val oldLines = old.scoreLines()
    val newLines = new.scoreLines()
    if (oldLines.size != newLines.size) return false
    return oldLines.zip(newLines).all { (o, n) -> syllableIdentity(o.cells) == syllableIdentity(n.cells) }
}

/** The moved score and the canonical (rest-linked) grid of a rest-only edit. */
data class GridEditResult(val score: Score, val grid: GridData?)

private fun List<ScoreNote>.shifted(delta: Int, fromS: Int = 0): List<ScoreNote> =
    if (delta == 0) this else map { if (it.s >= fromS) it.copy(t = it.t + delta) else it }

/**
 * Grid -> score (spec section 9): move the notes for a grid edit that only changed rest cells, then
 * make the grid canonical. Errors: `Invalid` (score not valid for [old], or `exceeds_max_ticks`
 * after moving) and `NotRestOnly`.
 */
fun applyGridRestEdit(score: Score, old: GridData?, new: GridData?): ScoreResult<GridEditResult> {
    validateScore(score, old)?.let { return ScoreResult.Err(it) }
    if (!isRestOnlyEdit(old, new)) return ScoreResult.Err(ScoreError.NotRestOnly("the grid edit changed syllables or lines, not only rests"))
    val oldLines = old.scoreLines()
    val newLines = new.scoreLines()
    var prevEnd = 0L
    val moved = score.lines.mapIndexed { index, line ->
        if (line.notes.isEmpty()) return@mapIndexed line
        // Long while moving: a push may pass Int range only in theory, but the check below must see it.
        var notes = line.notes.shifted(maxOf(0L, prevEnd - line.notes[0].t).toInt())
        val oldR = restsBetween(oldLines[index].cells)
        val newR = restsBetween(newLines[index].cells)
        for (k in oldR.indices) {
            val bySyllable = notes.associateBy { it.s }
            val left = bySyllable[k] ?: continue
            val right = bySyllable[k + 1] ?: continue
            val delta = newR[k] - oldR[k]
            if (delta == 0) continue
            var amount = delta * beatUnit(score, left.end)
            if (amount < 0) amount = -minOf(-amount, right.t - left.end)
            notes = notes.shifted(amount, k + 1)
        }
        prevEnd = notes.last().end.toLong()
        ScoreLine(notes)
    }
    val result = score.copy(lines = moved)
    validateScore(result, new)?.let { return ScoreResult.Err(it) }
    return applyRestLink(result, new).map { GridEditResult(result, it) }
}
