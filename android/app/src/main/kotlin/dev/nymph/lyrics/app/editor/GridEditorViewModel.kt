package dev.nymph.lyrics.app.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.nymph.lyrics.data.repository.NlpRepository
import dev.nymph.lyrics.data.repository.SnippetRepository
import dev.nymph.lyrics.logic.editor.EditorRowsState
import dev.nymph.lyrics.logic.editor.deleteRow
import dev.nymph.lyrics.logic.editor.insertRestInRow
import dev.nymph.lyrics.logic.editor.insertRow
import dev.nymph.lyrics.logic.editor.mergeSyllabified
import dev.nymph.lyrics.logic.editor.moveChunkInRow
import dev.nymph.lyrics.logic.editor.needsSyllabify
import dev.nymph.lyrics.logic.editor.rawTextForSave
import dev.nymph.lyrics.logic.editor.removeCellsInRow
import dev.nymph.lyrics.logic.editor.reorderRows
import dev.nymph.lyrics.logic.editor.setActiveRow
import dev.nymph.lyrics.logic.editor.typeLine
import dev.nymph.lyrics.logic.grid.moveTargetFromGap
import dev.nymph.lyrics.model.Snippet
import dev.nymph.lyrics.model.SnippetType
import dev.nymph.lyrics.model.SnippetUpdateRequest
import dev.nymph.lyrics.model.error.ApiResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Delay between the last text change and the API syllabify call (as the web editor). */
const val SYLLABIFY_DEBOUNCE_MS: Long = 300

/** The `PUT /api/snippets/{id}` body for the current editor state. */
fun saveRequest(state: EditorUiState): SnippetUpdateRequest = SnippetUpdateRequest(
    snippetType = state.snippetType,
    gridData = state.rows.gridData,
    rawText = rawTextForSave(state.rows),
)

private fun joinedText(rows: EditorRowsState): String = rows.lines.joinToString("\n")

/**
 * The grid editor: wraps the core [EditorRowsState] reducers (a port of the web
 * InlineGridEditor's orchestration) plus the cell selection, debounced syllabify and save.
 * Every failure becomes [EditorUiState] (`save`, `syllabifyError`); nothing throws.
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class GridEditorViewModel(
    private val snippet: Snippet,
    private val nlp: NlpRepository,
    private val snippets: SnippetRepository,
) : ViewModel(), GridEditorActions {
    private val _state = MutableStateFlow(EditorUiState.initial(snippet))
    val state: StateFlow<EditorUiState> = _state.asStateFlow()

    init {
        // Keyed on the joined text, so merging a result (which never changes text) does not
        // re-trigger it; a newer text cancels an in-flight request (mapLatest).
        viewModelScope.launch {
            _state
                .map { joinedText(it.rows) }
                .distinctUntilChanged()
                .debounce(SYLLABIFY_DEBOUNCE_MS)
                .filter { it.isNotBlank() && needsSyllabify(_state.value.rows) }
                .mapLatest { text -> nlp.syllabify(text) }
                .collect { result ->
                    when (result) {
                        is ApiResult.Success -> update { it.copy(rows = mergeSyllabified(it.rows, result.value), syllabifyError = null) }
                        is ApiResult.Failure -> update { it.copy(syllabifyError = result.error) }
                    }
                }
        }
    }

    /** Apply [transform], then drop a selection that no longer fits its row's cells. */
    private inline fun update(transform: (EditorUiState) -> EditorUiState) {
        _state.update { current ->
            val next = transform(current)
            val sel = next.selection ?: return@update next
            val line = next.rowLines.getOrNull(sel.row)
            if (line != null && sel.fitsIn(line.cells.size)) next else next.copy(selection = null)
        }
    }

    // ---- Rows and text ----

    override fun typeLine(row: Int, text: String) = update { s ->
        s.copy(rows = typeLine(s.rows, row, text), selection = s.selection?.takeIf { it.row != row })
    }

    /** Open a row's text field (clears the cell selection, as on the web). */
    override fun activateRow(row: Int) = update { it.copy(rows = setActiveRow(it.rows, row), selection = null) }

    override fun deactivateRow() = update { it.copy(rows = setActiveRow(it.rows, -1)) }

    /** Insert a blank row after [row] and focus it (Enter on the web). */
    override fun insertRowAfter(row: Int) = update { it.copy(rows = insertRow(it.rows, row), selection = null) }

    override fun deleteRow(row: Int) = update { it.copy(rows = deleteRow(it.rows, row), selection = null) }

    override fun setSnippetType(type: SnippetType) = update { it.copy(snippetType = type) }

    // ---- Selection ----

    override fun tapCell(row: Int, col: Int) = update { s ->
        val line = s.rowLines.getOrNull(row) ?: return@update s
        if (col !in line.cells.indices) return@update s
        s.copy(selection = tapSelection(s.selection, row, col, s.extendMode))
    }

    /** A long-press on a cell starts a chunk drag: an unselected cell becomes the selection. */
    override fun beginChunkDrag(row: Int, col: Int) = update { s ->
        val sel = s.selection
        if (sel != null && sel.row == row && col in sel) s else s.copy(selection = CellSelection.cell(row, col))
    }

    override fun resizeSelection(edge: SelectionEdge, col: Int) = update { s ->
        val sel = s.selection ?: return@update s
        val count = s.rowLines.getOrNull(sel.row)?.cells?.size ?: return@update s
        s.copy(selection = resizeSelection(sel, edge, col, count))
    }

    override fun setExtendMode(on: Boolean) = update { it.copy(extendMode = on) }

    override fun clearSelection() = update { it.copy(selection = null) }

    // ---- Chunk operations ----

    /** Drop the selected chunk at gap [gap] (0..cellCount, in the row's current cells). */
    override fun dropSelectionAt(gap: Int) {
        val sel = _state.value.selection ?: return
        val to = moveTargetFromGap(gap, sel.start, sel.end) ?: return
        moveSelection(to)
    }

    /** Move the selected chunk one cell left (-1) or right (+1). */
    override fun nudgeSelection(direction: Int) {
        val sel = _state.value.selection ?: return
        moveSelection(sel.start + direction)
    }

    /** Move the selected chunk so it starts at [to] (an index into the row without the chunk). */
    private fun moveSelection(to: Int) = update { s ->
        val sel = s.selection ?: return@update s
        val line = s.rowLines.getOrNull(sel.row) ?: return@update s
        val landing = to.coerceIn(0, maxOf(line.cells.size - sel.size, 0))
        if (landing == sel.start) return@update s
        s.copy(
            rows = moveChunkInRow(s.rows, sel.row, sel.start, sel.end, to),
            selection = CellSelection.of(sel.row, landing, landing + sel.size),
        )
    }

    override fun insertRestBeforeSelection() = update { s ->
        val sel = s.selection ?: return@update s
        s.copy(rows = insertRestInRow(s.rows, sel.row, sel.start), selection = sel.shiftedBy(1))
    }

    /** Remove the selected cells when every one is a rest (syllables are never deleted here). */
    override fun removeSelectedRests() = update { s ->
        val sel = s.selection ?: return@update s
        if (!selectionIsAllRests(s)) return@update s
        s.copy(rows = removeCellsInRow(s.rows, sel.row, sel.start, sel.end), selection = null)
    }

    // ---- Row reorder ----

    /** Move [row] one place up (-1) or down (+1). */
    override fun moveRow(row: Int, direction: Int) = reorderRow(row, row + direction)

    /** Move row [from] to index [to]; the active row and the selection follow their lines. */
    override fun reorderRow(from: Int, to: Int) = update { s ->
        val next = reorderRows(s.rows, from, to)
        if (next === s.rows) return@update s
        s.copy(rows = next, selection = s.selection?.let { it.inRow(indexAfterReorder(it.row, from, to)) })
    }

    // ---- Save ----

    /** Save the edit: raw_text rebuilt from arranged rows, grid_data, snippet_type. Ignored while saving or saved. */
    override fun save() {
        val current = _state.value
        if (current.save is SaveStatus.Saving || current.save is SaveStatus.Saved) return
        _state.update { it.copy(save = SaveStatus.Saving) }
        val body = saveRequest(current)
        viewModelScope.launch {
            val status = when (val result = snippets.update(snippet.id, body)) {
                is ApiResult.Success -> SaveStatus.Saved(result.value)
                is ApiResult.Failure -> SaveStatus.Failed(result.error)
            }
            _state.update { it.copy(save = status) }
        }
    }

    /** Dismiss a save failure message. */
    override fun acknowledgeSaveError() = _state.update { s -> if (s.save is SaveStatus.Failed) s.copy(save = SaveStatus.Idle) else s }
}

/** True when a selection exists and every selected cell is a rest. */
fun selectionIsAllRests(state: EditorUiState): Boolean {
    val sel = state.selection ?: return false
    val cells = state.rowLines.getOrNull(sel.row)?.cells ?: return false
    return sel.fitsIn(cells.size) && (sel.start until sel.end).all { cells[it].isRest }
}
