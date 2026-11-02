package dev.nymph.lyrics.logic.editor

import dev.nymph.lyrics.logic.grid.attachWords
import dev.nymph.lyrics.logic.grid.insertRest
import dev.nymph.lyrics.logic.grid.localSyllabifyLine
import dev.nymph.lyrics.logic.grid.moveChunk
import dev.nymph.lyrics.logic.grid.removeCell
import dev.nymph.lyrics.logic.grid.withRebuiltText
import dev.nymph.lyrics.logic.jsIsSpace
import dev.nymph.lyrics.logic.jsTrim
import dev.nymph.lyrics.logic.jsTrimEnd
import dev.nymph.lyrics.logic.jsTrimStart
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
 *
 * [caret] asks the view to put the caret of row `caret.row` at `caret.offset` when it shows that
 * row's field. Structural edits (insert, delete, split, join) issue a fresh [CaretRequest] every
 * time (identity equality), so the view can key on it; typing, grid edits and syllabify merges keep
 * it, and focusing another row or reordering clears it (the caret then goes to the end of the row).
 */
@ConsistentCopyVisibility
data class EditorRowsState internal constructor(
    val lines: List<String>,
    val gridData: GridData?,
    val arranged: Set<Int>,
    /** The focused row; -1 when none (a loaded snippet starts unfocused). */
    val activeRow: Int,
    val caret: CaretRequest?,
)

/**
 * Where the view should put the caret when it (re)builds row [row]'s field. Deliberately not a
 * data class: two requests are equal only when they are the same edit.
 */
class CaretRequest internal constructor(val row: Int, val offset: Int) {
    override fun toString(): String = "CaretRequest(row=$row, offset=$offset)"
}

private val BLANK = Row("", null, false)

private fun edited(text: String): Row = Row(text, if (isBlank(text)) null else localSyllabifyLine(text), false)

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

private fun fromRows(rows: List<Row>, activeRow: Int, caret: CaretRequest?): EditorRowsState {
    val gridLines = rows.mapNotNull { it.grid }
    val arranged = rows.indices.filterTo(LinkedHashSet()) { rows[it].arranged && rows[it].grid != null }
    return EditorRowsState(
        lines = rows.map { it.text },
        gridData = if (gridLines.isNotEmpty()) GridData(gridLines) else null,
        arranged = arranged,
        activeRow = activeRow,
        caret = caret,
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
    return fromRows(rows, if (rawText.isNotEmpty()) -1 else 0, null)
}

fun gridLineForRow(state: EditorRowsState, row: Int): GridLine? {
    if (row !in state.lines.indices || isBlank(state.lines[row])) return null
    val gi = (0 until row).count { !isBlank(state.lines[it]) }
    return state.gridData?.lines?.getOrNull(gi)
}

fun setActiveRow(state: EditorRowsState, activeRow: Int): EditorRowsState =
    if (state.activeRow == activeRow) state else state.copy(activeRow = activeRow, caret = null)

/** The user typed into a row: its grid becomes a local estimate and it is no longer arranged. */
fun typeLine(state: EditorRowsState, row: Int, text: String): EditorRowsState {
    val rows = toRows(state)
    if (row !in rows.indices) return state
    rows[row] = edited(text)
    return fromRows(rows, state.activeRow, state.caret)
}

/** Insert a blank row after [afterRow] and make it active. */
fun insertRow(state: EditorRowsState, afterRow: Int): EditorRowsState {
    val rows = toRows(state)
    rows.add((afterRow + 1).coerceIn(0, rows.size), BLANK)
    return fromRows(rows, afterRow + 1, CaretRequest(afterRow + 1, 0))
}

/** Remove a row (keeping at least one) and activate the row above, caret at its end. */
fun deleteRow(state: EditorRowsState, row: Int): EditorRowsState {
    val rows = toRows(state)
    if (row !in rows.indices) return state
    if (rows.size == 1) return fromRows(listOf(BLANK), 0, CaretRequest(0, 0))
    rows.removeAt(row)
    val active = maxOf(0, row - 1)
    return fromRows(rows, active, CaretRequest(active, rows[active].text.length))
}

/**
 * Enter at [caret] in [row]: the text before stays (trailing whitespace trimmed), the text after
 * becomes a new row directly below (leading whitespace trimmed), which becomes active with the
 * caret at its start. Both halves are re-estimated and lose `arranged`. With only whitespace after
 * the caret this is [insertRow] (the row is untouched); with only whitespace before it, a blank
 * row opens above and the row moves down unchanged (keeping its grid and `arranged`).
 */
fun splitRow(state: EditorRowsState, row: Int, caret: Int): EditorRowsState {
    val rows = toRows(state)
    val current = rows.getOrNull(row) ?: return state
    val at = caret.coerceIn(0, current.text.length)
    val before = current.text.substring(0, at)
    val after = current.text.substring(at)
    if (isBlank(after)) return insertRow(state, row)
    rows.removeAt(row)
    if (isBlank(before)) {
        rows.addAll(row, listOf(BLANK, current.copy(text = jsTrimStart(after))))
    } else {
        rows.addAll(row, listOf(edited(jsTrimEnd(before)), edited(jsTrimStart(after))))
    }
    return fromRows(rows, row + 1, CaretRequest(row + 1, 0))
}

/** The rows after joining `upper + 1` onto `upper`, with the seam offsets before / after the separator. */
private class Joined(val rows: List<Row>, val seam: Int, val joinPoint: Int)

/**
 * Join row `upper + 1` onto [upper], with one space between unless either side is blank or already
 * has whitespace at the seam. A blank side contributes nothing: the other row is kept as is (grid
 * and `arranged` included). Otherwise the joined row is re-estimated and loses `arranged`.
 */
private fun join(state: EditorRowsState, upper: Int): Joined? {
    val rows = toRows(state)
    if (upper < 0) return null
    val top = rows.getOrNull(upper) ?: return null
    val bottom = rows.getOrNull(upper + 1) ?: return null
    val joined = when {
        isBlank(bottom.text) -> Joined(rows, top.text.length, top.text.length)
        isBlank(top.text) -> Joined(rows, 0, 0).also { rows[upper] = bottom }
        else -> {
            val hasSpace = jsIsSpace(top.text.last()) || jsIsSpace(bottom.text.first())
            val sep = if (hasSpace) "" else " "
            rows[upper] = edited(top.text + sep + bottom.text)
            Joined(rows, top.text.length, top.text.length + sep.length)
        }
    }
    rows.removeAt(upper + 1)
    return joined
}

/** Backspace at caret 0 of [row]: join it onto the row above; caret at the join point. */
fun joinWithPrevious(state: EditorRowsState, row: Int): EditorRowsState {
    val joined = join(state, row - 1) ?: return state
    return fromRows(joined.rows, row - 1, CaretRequest(row - 1, joined.joinPoint))
}

/** Delete at the end of [row]: join the row below onto it; the caret stays where it was. */
fun joinWithNext(state: EditorRowsState, row: Int): EditorRowsState {
    val joined = join(state, row) ?: return state
    return fromRows(joined.rows, row, CaretRequest(row, joined.seam))
}

private inline fun updateGrid(state: EditorRowsState, row: Int, fn: (GridLine) -> GridLine): EditorRowsState {
    val rows = toRows(state)
    val current = rows.getOrNull(row) ?: return state
    val grid = current.grid ?: return state
    val next = fn(grid)
    if (next === grid) return state
    val rebuilt = withRebuiltText(next)
    rows[row] = Row(rebuilt.rawText, rebuilt, true)
    return fromRows(rows, state.activeRow, state.caret)
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
    return fromRows(rows, activeRow, null)
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
    return fromRows(next, state.activeRow, state.caret)
}

/** True when some non-blank row still wants an API syllabification. */
fun needsSyllabify(state: EditorRowsState): Boolean =
    state.lines.indices.any { !isBlank(state.lines[it]) && it !in state.arranged }

/** The text to persist: arranged rows are spelled from their grid, others as typed. */
fun rawTextForSave(state: EditorRowsState): String =
    toRows(state).joinToString("\n") { row ->
        if (row.arranged && row.grid != null) withRebuiltText(row.grid).rawText else row.text
    }
