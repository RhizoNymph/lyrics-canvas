package dev.nymph.lyrics.app.editor

import dev.nymph.lyrics.model.SnippetType

/** Everything the editor screen can ask for; implemented by [GridEditorViewModel]. */
interface GridEditorActions {
    fun typeLine(row: Int, text: String)
    fun activateRow(row: Int)
    fun deactivateRow()
    fun insertRowAfter(row: Int)
    fun deleteRow(row: Int)
    fun setSnippetType(type: SnippetType)

    fun tapCell(row: Int, col: Int)
    fun beginChunkDrag(row: Int, col: Int)
    fun resizeSelection(edge: SelectionEdge, col: Int)
    fun setExtendMode(on: Boolean)
    fun clearSelection()

    fun dropSelectionAt(gap: Int)
    fun nudgeSelection(direction: Int)
    fun insertRestBeforeSelection()
    fun removeSelectedRests()

    fun moveRow(row: Int, direction: Int)
    fun reorderRow(from: Int, to: Int)

    fun save()
    fun acknowledgeSaveError()
}
