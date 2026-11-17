package dev.nymph.lyrics.app.editor

import dev.nymph.lyrics.app.editor.score.DraftProblem
import dev.nymph.lyrics.app.editor.score.ScoreLayer
import dev.nymph.lyrics.logic.editor.EditorRowsState
import dev.nymph.lyrics.logic.editor.gridLineForRow
import dev.nymph.lyrics.logic.editor.initEditorState
import dev.nymph.lyrics.model.GridLine
import dev.nymph.lyrics.model.Snippet
import dev.nymph.lyrics.model.SnippetType
import dev.nymph.lyrics.model.error.ApiError

/** Progress of the Save action. */
sealed interface SaveStatus {
    data object Idle : SaveStatus

    data object Saving : SaveStatus

    /** The server accepted the edit; the route hands [snippet] to the canvas and closes. */
    data class Saved(val snippet: Snippet) : SaveStatus

    /** The update failed; the edits are kept and Save can be retried. */
    data class Failed(val error: ApiError) : SaveStatus
}

/**
 * Everything the editor screen renders.
 *
 * Invariant (kept by [GridEditorViewModel]): [selection], when present, lies inside the cells of
 * its row's grid line.
 */
data class EditorUiState(
    val rows: EditorRowsState,
    val selection: CellSelection?,
    /** When on, a tap on a cell of the selected row extends the selection instead of replacing it. */
    val extendMode: Boolean,
    val snippetType: SnippetType,
    val save: SaveStatus,
    /** The last syllabify failure; rows keep their local estimates. Cleared by the next success. */
    val syllabifyError: ApiError?,
    /** The score layer (musical mode): the score, its view and the staff selection. */
    val score: ScoreLayer = ScoreLayer(null, null, false),
    /** Why the last "Make score" made nothing; the screen shows it once and clears it. */
    val draftProblem: DraftProblem? = null,
) {
    /** The score as the staff shows it: the layer's score for the rows' current grid. */
    val staffScore by lazy { score.scoreFor(rows.gridData) }

    /** True while the staff (not the grid rows) is shown. */
    val staffShown: Boolean get() = score.view && staffScore != null

    /** The grid line of each row (null for blank rows), index-aligned with `rows.lines`. */
    val rowLines: List<GridLine?> by lazy { rows.lines.indices.map { gridLineForRow(rows, it) } }

    val isSaving: Boolean get() = save is SaveStatus.Saving

    companion object {
        fun initial(snippet: Snippet): EditorUiState {
            val rows = initEditorState(snippet.rawText, snippet.gridData)
            return EditorUiState(
                rows = rows,
                selection = null,
                extendMode = false,
                snippetType = snippet.snippetType,
                save = SaveStatus.Idle,
                syllabifyError = null,
                score = ScoreLayer.open(snippet.score, snippet.scoreView, snippet.gridData, rows.gridData),
            )
        }
    }
}
