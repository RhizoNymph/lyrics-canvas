package dev.nymph.lyrics.logic.editor

import dev.nymph.lyrics.logic.grid.attachWords
import dev.nymph.lyrics.logic.grid.insertRest
import dev.nymph.lyrics.logic.grid.localSyllabifyLine
import dev.nymph.lyrics.logic.grid.moveChunk
import dev.nymph.lyrics.logic.grid.removeCell
import dev.nymph.lyrics.logic.grid.withRebuiltText
import dev.nymph.lyrics.logic.jsTrim
import dev.nymph.lyrics.model.GridData
import dev.nymph.lyrics.model.GridLine

/**
 * The grid editor's row state (a port of frontend/src/components/canvas/editorState.ts).
 *
 * Invariant: `gridData.lines` holds exactly one line per non-blank entry of [lines], in order,
 * with `rawText` equal to the trimmed row text. Blank rows have no grid line. [gridData] is null
 * when no row is non-blank. Rows in [arranged] were laid out by hand (or loaded from a saved grid)
 * and are never replaced by a syllabify result until their text is typed again. The constructor is
 * internal so only the reducers below can build a state, keeping the invariant.
 */
@ConsistentCopyVisibility
data class EditorRowsState internal constructor(
    val lines: List<String>,
    val gridData: GridData?,
    val arranged: Set<Int>,
    /** The focused row; -1 when none (a loaded snippet starts unfocused). */
    val activeRow: Int,
)

private data class Row(val text: String, val grid: GridLine?, val arranged: Boolean)

private fun isBlank(text: String): Boolean = jsTrim(text).isEmpty()

private fun toRows(state: EditorRowsState): MutableList<Row> {
    val gridLines = state.gridData?.lines ?: emptyList()
    var gi = 0
    return state.lines.mapIndexedTo(mutableListOf()) { row, text ->
        val blank = isBlank(text)
        val grid = if (blank) null else (gridLines.getOrNull(gi++) ?: localSyllabifyLine(text))
        Row(text, grid, !blank && row in state.arranged)
    }
}

private fun fromRows(rows: List<Row>, activeRow: Int): EditorRowsState {
    val gridLines = rows.mapNotNull { it.grid }
    val arranged = rows.indices.filterTo(LinkedHashSet()) { rows[it].arranged && rows[it].grid != null }
    return EditorRowsState(
        lines = rows.map { it.text },
        gridData = if (gridLines.isNotEmpty()) GridData(gridLines) else null,
        arranged = arranged,
        activeRow = activeRow,
    )
}

/** Build the state from a snippet's text and saved grid; matching saved lines are kept and pinned. */
fun initEditorState(rawText: String, savedGrid: GridData?): EditorRowsState {
    val lines = if (rawText.isNotEmpty()) rawText.split("\n") else listOf("")
    val saved = savedGrid?.lines ?: emptyList()
    var si = 0
    val rows = lines.map { text ->
        if (isBlank(text)) return@map Row(text, null, false)
        val candidate = saved.getOrNull(si)
        if (candidate != null && jsTrim(candidate.rawText) == jsTrim(text)) {
            si++
            Row(text, attachWords(candidate), true)
        } else {
            Row(text, localSyllabifyLine(text), false)
        }
    }
    return fromRows(rows, if (rawText.isNotEmpty()) -1 else 0)
}

fun gridLineForRow(state: EditorRowsState, row: Int): GridLine? {
    if (row !in state.lines.indices || isBlank(state.lines[row])) return null
    val gi = (0 until row).count { !isBlank(state.lines[it]) }
    return state.gridData?.lines?.getOrNull(gi)
}

fun setActiveRow(state: EditorRowsState, activeRow: Int): EditorRowsState =
    if (state.activeRow == activeRow) state else state.copy(activeRow = activeRow)

/** The user typed into a row: its grid becomes a local estimate and it is no longer arranged. */
fun typeLine(state: EditorRowsState, row: Int, text: String): EditorRowsState {
    val rows = toRows(state)
    if (row !in rows.indices) return state
    rows[row] = Row(text, if (isBlank(text)) null else localSyllabifyLine(text), false)
    return fromRows(rows, state.activeRow)
}

/** Insert a blank row after [afterRow] and make it active. */
fun insertRow(state: EditorRowsState, afterRow: Int): EditorRowsState {
    val rows = toRows(state)
    rows.add((afterRow + 1).coerceIn(0, rows.size), Row("", null, false))
    return fromRows(rows, afterRow + 1)
}

/** Remove a row (keeping at least one) and activate the row above. */
fun deleteRow(state: EditorRowsState, row: Int): EditorRowsState {
    val rows = toRows(state)
    if (row !in rows.indices) return state
    if (rows.size == 1) return fromRows(listOf(Row("", null, false)), 0)
    rows.removeAt(row)
    return fromRows(rows, maxOf(0, row - 1))
}

private inline fun updateGrid(state: EditorRowsState, row: Int, fn: (GridLine) -> GridLine): EditorRowsState {
    val rows = toRows(state)
    val current = rows.getOrNull(row) ?: return state
    val grid = current.grid ?: return state
    val next = fn(grid)
    if (next === grid) return state
    val rebuilt = withRebuiltText(next)
    rows[row] = Row(rebuilt.rawText, rebuilt, true)
    return fromRows(rows, state.activeRow)
}

/** Move cells `[start, end)` of a row so the chunk starts at [to] (see [moveChunk]). */
fun moveChunkInRow(state: EditorRowsState, row: Int, start: Int, end: Int, to: Int): EditorRowsState =
    updateGrid(state, row) { moveChunk(it, start, end, to) }

fun insertRestInRow(state: EditorRowsState, row: Int, at: Int): EditorRowsState =
    updateGrid(state, row) { insertRest(it, at) }

/** Remove cells `[start, end)` of a row. */
fun removeCellsInRow(state: EditorRowsState, row: Int, start: Int, end: Int): EditorRowsState =
    updateGrid(state, row) { line ->
        var out = line
        for (i in end - 1 downTo start) out = removeCell(out, i)
        out
    }

/** Move row [from] to index [to], carrying its grid line and arranged flag; the active row follows its line. */
fun reorderRows(state: EditorRowsState, from: Int, to: Int): EditorRowsState {
    val n = state.lines.size
    if (from == to || from !in 0 until n || to !in 0 until n) return state
    val rows = toRows(state)
    val moved = rows.removeAt(from)
    rows.add(to, moved)
    var activeRow = state.activeRow
    if (activeRow == from) {
        activeRow = to
    } else if (from < activeRow && activeRow <= to) {
        activeRow -= 1
    } else if (to <= activeRow && activeRow < from) {
        activeRow += 1
    }
    return fromRows(rows, activeRow)
}

/**
 * Apply a syllabify result. Each unarranged row takes the first unconsumed result line with the
 * same text; arranged rows and rows whose text changed since the request keep their grid line.
 */
fun mergeSyllabified(state: EditorRowsState, result: GridData): EditorRowsState {
    val rows = toRows(state)
    val consumed = HashSet<Int>()
    val next = rows.map { row ->
        if (row.grid == null || row.arranged) return@map row
        val wanted = jsTrim(row.text)
        val match = result.lines.indices.firstOrNull { it !in consumed && jsTrim(result.lines[it].rawText) == wanted }
            ?: return@map row
        consumed += match
        row.copy(grid = attachWords(result.lines[match]))
    }
    return fromRows(next, state.activeRow)
}

/** True when some non-blank row still wants an API syllabification. */
fun needsSyllabify(state: EditorRowsState): Boolean =
    state.lines.indices.any { !isBlank(state.lines[it]) && it !in state.arranged }

/** The text to persist: arranged rows are spelled from their grid, others as typed. */
fun rawTextForSave(state: EditorRowsState): String =
    toRows(state).joinToString("\n") { row ->
        if (row.arranged && row.grid != null) withRebuiltText(row.grid).rawText else row.text
    }
