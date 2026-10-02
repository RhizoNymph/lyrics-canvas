package dev.nymph.lyrics.app.editor.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.nymph.lyrics.app.editor.CellSelection
import dev.nymph.lyrics.app.editor.GridEditorActions
import dev.nymph.lyrics.model.GridLine

/** Per-row facts the screen derives from [dev.nymph.lyrics.app.editor.EditorUiState]. */
internal data class RowModel(
    val index: Int,
    val text: String,
    val line: GridLine?,
    val isActive: Boolean,
    val arranged: Boolean,
    /** The selection when it is in this row. */
    val selection: CellSelection?,
    val canRemoveRests: Boolean,
    val canMoveUp: Boolean,
    val canMoveDown: Boolean,
)

/**
 * One editor row: drag handle (long-press to reorder; gesture supplied by the screen through
 * [handleModifier]), row number (tap to edit text), cell strip, syllable count, row menu; the
 * chunk toolbar while selected; the text field while active.
 */
@Composable
internal fun EditorRow(
    model: RowModel,
    extendMode: Boolean,
    actions: GridEditorActions,
    handleModifier: Modifier,
    modifier: Modifier = Modifier,
) {
    val row = model.index
    val colors = MaterialTheme.colorScheme
    Column(modifier) {
        Row(Modifier.heightIn(min = EditorDimens.RowControl), verticalAlignment = Alignment.Top) {
            Box(handleModifier.size(EditorDimens.RowControl), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Menu, contentDescription = "Reorder line ${row + 1}", tint = colors.onSurfaceVariant)
            }
            Box(
                Modifier
                    .size(EditorDimens.NumberWidth, EditorDimens.RowControl)
                    .clickable(onClickLabel = "Edit line text") { actions.activateRow(row) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "${row + 1}",
                    fontFamily = FontFamily.Monospace,
                    fontWeight = if (model.isActive) FontWeight.Bold else FontWeight.Normal,
                    color = if (model.isActive) colors.primary else colors.onSurfaceVariant,
                )
            }
            val line = model.line
            if (line != null) {
                CellStrip(
                    cells = line.cells,
                    selection = model.selection,
                    showTrailingCell = model.isActive,
                    onTapCell = { col -> actions.tapCell(row, col) },
                    onTapEmpty = { actions.activateRow(row) },
                    onBeginDrag = { col -> actions.beginChunkDrag(row, col) },
                    onDrop = actions::dropSelectionAt,
                    onResize = actions::resizeSelection,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Box(
                    Modifier
                        .weight(1f)
                        .heightIn(min = EditorDimens.CellHeight)
                        .clickable { actions.activateRow(row) }
                        .padding(horizontal = 8.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        model.text.trim().ifEmpty { if (model.isActive) "" else "(empty line)" },
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Box(Modifier.size(EditorDimens.CountWidth, EditorDimens.RowControl), contentAlignment = Alignment.Center) {
                val count = line?.syllableCount ?: 0
                Text(
                    if (count > 0) "$count" else "",
                    fontFamily = FontFamily.Monospace,
                    textAlign = TextAlign.End,
                    fontWeight = if (model.isActive) FontWeight.Bold else FontWeight.Normal,
                    color = if (model.isActive) colors.primary else colors.onSurfaceVariant,
                )
            }
            RowMenu(model, actions)
        }
        if (model.selection != null) {
            RowToolbar(
                canRemoveRests = model.canRemoveRests,
                extendMode = extendMode,
                onInsertRest = actions::insertRestBeforeSelection,
                onRemoveRests = actions::removeSelectedRests,
                onNudge = actions::nudgeSelection,
                onExtendModeChange = actions::setExtendMode,
                onClear = actions::clearSelection,
                modifier = Modifier.padding(start = EditorDimens.RowControl),
            )
        }
        if (model.isActive) {
            ActiveLineField(
                text = model.text,
                arranged = model.arranged,
                onTextChange = { actions.typeLine(row, it) },
                onNewLine = { actions.insertRowAfter(row) },
                onDeleteEmpty = { actions.deleteRow(row) },
                modifier = Modifier.padding(start = EditorDimens.RowControl, end = 8.dp, bottom = 4.dp),
            )
        }
    }
}

@Composable
private fun RowMenu(model: RowModel, actions: GridEditorActions) {
    var open by remember { mutableStateOf(false) }
    val row = model.index
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = "Line ${row + 1} actions")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Edit text") }, onClick = { open = false; actions.activateRow(row) })
            DropdownMenuItem(
                text = { Text("▲ Move up") },
                enabled = model.canMoveUp,
                onClick = { open = false; actions.moveRow(row, -1) },
            )
            DropdownMenuItem(
                text = { Text("▼ Move down") },
                enabled = model.canMoveDown,
                onClick = { open = false; actions.moveRow(row, 1) },
            )
            DropdownMenuItem(text = { Text("Insert line below") }, onClick = { open = false; actions.insertRowAfter(row) })
            DropdownMenuItem(text = { Text("Delete line") }, onClick = { open = false; actions.deleteRow(row) })
        }
    }
}
