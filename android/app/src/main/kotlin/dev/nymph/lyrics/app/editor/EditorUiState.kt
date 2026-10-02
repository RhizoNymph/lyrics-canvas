package dev.nymph.lyrics.app.editor

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
) {
    /** The grid line of each row (null for blank rows), index-aligned with `rows.lines`. */
    val rowLines: List<GridLine?> by lazy { rows.lines.indices.map { gridLineForRow(rows, it) } }

    val isSaving: Boolean get() = save is SaveStatus.Saving

    companion object {
        fun initial(snippet: Snippet): EditorUiState = EditorUiState(
            rows = initEditorState(snippet.rawText, snippet.gridData),
            selection = null,
            extendMode = false,
            snippetType = snippet.snippetType,
            save = SaveStatus.Idle,
            syllabifyError = null,
        )
    }
}
