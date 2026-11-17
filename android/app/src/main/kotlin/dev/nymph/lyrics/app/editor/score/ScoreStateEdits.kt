package dev.nymph.lyrics.app.editor.score

import dev.nymph.lyrics.app.editor.EditorUiState
import dev.nymph.lyrics.logic.editor.setActiveRow
import dev.nymph.lyrics.logic.editor.withLinkedGrid
import dev.nymph.lyrics.logic.score.ScoreResult
import dev.nymph.lyrics.logic.score.draftFromGrid
import dev.nymph.lyrics.logic.score.edit.StaffEdit
import dev.nymph.lyrics.logic.score.edit.SyllableRef
import dev.nymph.lyrics.model.score.Score
import dev.nymph.lyrics.model.score.ScoreSnap

/**
 * Editor state transitions of the score layer (pure; the ViewModel applies them). Every commit
 * writes the score's gaps into the rows' grid as rests ([withLinkedGrid]), so the grid view and the
 * saved text already show what the server's rest link will store.
 */

/** Commit [next] (valid for the rows' grid) and link the rows; unchanged when it does not fit. */
fun EditorUiState.committing(next: Score, view: Boolean = score.view, selected: SyllableRef? = score.selected): EditorUiState {
    val linked = score.committed(next, rows.gridData) ?: return this
    val linkedRows = withLinkedGrid(rows, linked.grid)
    return copy(rows = linkedRows, score = linked.layer.copy(grid = linkedRows.gridData, view = view, selected = selected), selection = null)
}

fun EditorUiState.showingStaff(): EditorUiState {
    val current = staffScore ?: return this
    return copy(rows = setActiveRow(rows, -1)).committing(current, view = true, selected = null)
}

fun EditorUiState.showingGrid(): EditorUiState = copy(score = score.copy(view = false, selected = null))

/** Why "Make score" made nothing. */
enum class DraftProblem { NO_LYRICS, TOO_LONG }

/** "Make score" from the rows' grid: the draft in the staff view, or why there is none. */
fun EditorUiState.drafted(num: Int, den: Int, bpm: Double, note: ScoreSnap): Pair<EditorUiState, DraftProblem?> {
    val grid = rows.gridData
    if (grid == null || grid.lines.none { line -> line.cells.any { !it.isRest } }) return this to DraftProblem.NO_LYRICS
    return when (val draft = draftFromGrid(grid, num, den, bpm, note)) {
        is ScoreResult.Ok -> withDraft(draft.value) to null
        is ScoreResult.Err -> this to DraftProblem.TOO_LONG
    }
}

fun EditorUiState.withDraft(draft: Score): EditorUiState = committing(draft, view = true, selected = null)

fun EditorUiState.withoutScore(): EditorUiState = copy(score = score.copy(score = null, view = false, selected = null))

fun EditorUiState.selecting(ref: SyllableRef?): EditorUiState = copy(score = score.copy(selected = ref))

/** Apply a staff edit to the shown score; a refused edit leaves everything (the selection included). */
fun EditorUiState.staffEdited(selected: SyllableRef? = score.selected, edit: (Score) -> StaffEdit): EditorUiState {
    val current = staffScore ?: return this
    return when (val result = edit(current)) {
        is StaffEdit.Changed -> committing(result.score, selected = selected)
        is StaffEdit.Refused -> this
    }
}

/** The snap length of the shown score, in ticks. */
val EditorUiState.snapTicks: Int get() = (staffScore?.snap ?: ScoreSnap.SIXTEENTH).ticks
