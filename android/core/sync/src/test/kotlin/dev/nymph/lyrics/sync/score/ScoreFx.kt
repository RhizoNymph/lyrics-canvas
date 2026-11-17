package dev.nymph.lyrics.sync.score

import dev.nymph.lyrics.model.GridCell
import dev.nymph.lyrics.model.GridData
import dev.nymph.lyrics.model.GridLine
import dev.nymph.lyrics.model.score.Score
import dev.nymph.lyrics.model.score.ScoreLine
import dev.nymph.lyrics.model.score.ScoreMeter
import dev.nymph.lyrics.model.score.ScoreNote
import dev.nymph.lyrics.model.score.ScoreSnap
import dev.nymph.lyrics.model.score.ScoreTempo

/** Grids and scores for the score sync tests (4/4, one syllable per word). */
object ScoreFx {
    /** A grid line from tokens: `_` is a rest, anything else a one-syllable word. */
    fun line(vararg tokens: String): GridLine {
        var word = 0
        val cells = tokens.map { t -> if (t == "_") GridCell.REST else GridCell(syllable = t, stress = 1, wordIndex = word++, word = t) }
        return GridLine(cells, cells.count { !it.isRest }, tokens.joinToString(" "))
    }

    fun grid(vararg lines: GridLine) = GridData(lines.toList())

    fun score(vararg lines: List<ScoreNote>) = Score(
        meters = listOf(ScoreMeter(0, 4, 4)),
        tempos = listOf(ScoreTempo(0, 90.0)),
        snap = ScoreSnap.EIGHTH,
        lines = lines.map { ScoreLine(it) },
    )

    fun n(s: Int, t: Int, d: Int = 24, a: Int = 0) = ScoreNote(s, t, d, a)
}
