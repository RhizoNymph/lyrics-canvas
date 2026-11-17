package dev.nymph.lyrics.logic.score

import dev.nymph.lyrics.model.GridCell
import dev.nymph.lyrics.model.GridData
import dev.nymph.lyrics.model.GridLine
import dev.nymph.lyrics.model.score.Score
import dev.nymph.lyrics.model.score.ScoreLine
import dev.nymph.lyrics.model.score.ScoreMeter
import dev.nymph.lyrics.model.score.ScoreNote
import dev.nymph.lyrics.model.score.ScoreSnap
import dev.nymph.lyrics.model.score.ScoreTempo

/** A line from words ("_" = rest), one syllable per word, word_index in order, stress 1. */
internal fun line(vararg words: String): GridLine {
    var index = 0
    val cells = words.map { w -> if (w == "_") GridCell.REST else GridCell(syllable = w, stress = 1, wordIndex = index++, word = w) }
    return GridLine(cells, cells.count { !it.isRest }, rebuildLineText(cells))
}

internal fun grid(vararg lines: GridLine) = GridData(lines.toList())

internal fun note(s: Int, t: Int, d: Int, a: Int = 0) = ScoreNote(s, t, d, a)

internal fun score(vararg lines: List<ScoreNote>, pickup: Int = 0, meters: List<ScoreMeter> = listOf(ScoreMeter(0, 4, 4)), tempos: List<ScoreTempo> = listOf(ScoreTempo(0, 90.0))) =
    Score(pickup = pickup, meters = meters, tempos = tempos, snap = ScoreSnap.SIXTEENTH, lines = lines.map { ScoreLine(it) })
