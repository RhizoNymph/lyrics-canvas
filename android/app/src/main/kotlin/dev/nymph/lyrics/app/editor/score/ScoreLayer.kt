package dev.nymph.lyrics.app.editor.score

import dev.nymph.lyrics.logic.score.ScoreResult
import dev.nymph.lyrics.logic.score.applyGridRestEdit
import dev.nymph.lyrics.logic.score.applyRestLink
import dev.nymph.lyrics.logic.score.isRestOnlyEdit
import dev.nymph.lyrics.logic.score.reconcile
import dev.nymph.lyrics.logic.score.validateScore
import dev.nymph.lyrics.logic.score.edit.SyllableRef
import dev.nymph.lyrics.model.GridData
import dev.nymph.lyrics.model.score.Score
import dev.nymph.lyrics.model.score.ScoreChange

/**
 * The editor's score layer ("musical mode"): the score as last committed, the grid it is valid
 * against, and which view (grid or staff) the editor shows.
 *
 * The rows stay the source of the grid. Text edits do not touch the layer: [scoreFor] reconciles
 * the committed score with the rows' current grid whenever it is read, so retyping a word inside
 * one editing session keeps its notes (the server reconciles once per save, from the stored grid,
 * and the editor reads the same way). Rest-only grid edits commit at once ([afterGridEdit]), since
 * they move notes. Staff edits commit the edited score together with the rows' rest-linked grid.
 *
 * Invariant: [score] is null or valid against [grid].
 */
data class ScoreLayer(
    val score: Score?,
    val grid: GridData?,
    /** True: the staff is shown. Only with a score. */
    val view: Boolean,
    /** The note (or tray syllable) the staff has selected. */
    val selected: SyllableRef? = null,
    /** The snippet's score and view when the editor opened (what a save compares with). */
    val original: Score? = null,
    val originalView: Boolean = false,
) {
    /** The score for the rows' [rowsGrid]: the committed score, reconciled when the grid moved on. */
    fun scoreFor(rowsGrid: GridData?): Score? {
        val s = score ?: return null
        if (rowsGrid == grid) return s
        return reconcile(s, grid, rowsGrid).getOrNull()
    }

    /**
     * After the rows' grid went from [before] to [after]: a rest-only edit moves the notes (spec
     * section 9) and commits; any other change is left to [scoreFor]. A refused move (past
     * MAX_TICKS) leaves the layer as it was, so the staff keeps the old timing.
     */
    fun afterGridEdit(before: GridData?, after: GridData?): ScoreLayer {
        if (score == null || before == after || !isRestOnlyEdit(before, after)) return this
        val current = scoreFor(before) ?: return this
        return when (val moved = applyGridRestEdit(current, before, after)) {
            is ScoreResult.Ok -> copy(score = moved.value.score, grid = after)
            is ScoreResult.Err -> this
        }
    }

    /** [next] as the committed score with the rows' grid rest-linked to it; null when [next] does not fit. */
    fun committed(next: Score, rowsGrid: GridData?): Linked? {
        if (validateScore(next, rowsGrid) != null) return null
        val linked = applyRestLink(next, rowsGrid).getOrNull() ?: return null
        return Linked(copy(score = next, grid = linked), linked)
    }

    /** The layer committed for the staff: the current score with its gaps written into the grid. */
    fun linkedFor(rowsGrid: GridData?): Linked? = scoreFor(rowsGrid)?.let { committed(it, rowsGrid) }

    /** The layer after a commit and the grid the rows must take ([dev.nymph.lyrics.logic.editor.withLinkedGrid]). */
    data class Linked(val layer: ScoreLayer, val grid: GridData?)

    /** The score fields of the save body for the rows' [rowsGrid]: null fields are left out. */
    fun saveFields(rowsGrid: GridData?): SaveFields {
        val current = scoreFor(rowsGrid)
        val view = current != null && view
        return when {
            current == null && original == null -> SaveFields(null, null)
            current == null -> SaveFields(ScoreChange.REMOVE, null)
            else -> SaveFields(ScoreChange.set(current), view)
        }
    }

    data class SaveFields(val score: ScoreChange?, val scoreView: Boolean?)

    companion object {
        /**
         * The layer for a snippet opened with [score] (valid against [savedGrid]) whose rows built
         * [rowsGrid]: equal grids keep it; otherwise it is reconciled, and a score that cannot
         * follow is dropped from the layer (the save then sends Remove score, as the server would
         * have to reconcile it anyway).
         */
        fun open(score: Score?, view: Boolean, savedGrid: GridData?, rowsGrid: GridData?): ScoreLayer {
            val fitted = when {
                score == null -> null
                savedGrid == rowsGrid -> score
                validateScore(score, savedGrid) == null -> reconcile(score, savedGrid, rowsGrid).getOrNull()
                validateScore(score, rowsGrid) == null -> score
                else -> null
            }
            return ScoreLayer(fitted, rowsGrid, view && fitted != null, original = score, originalView = view)
        }
    }
}
