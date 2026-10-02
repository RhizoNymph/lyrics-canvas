package dev.nymph.lyrics.app.editor.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.nymph.lyrics.app.editor.EditorUiState
import dev.nymph.lyrics.app.editor.GridEditorActions
import dev.nymph.lyrics.app.editor.RowSpan
import dev.nymph.lyrics.app.editor.SaveStatus
import dev.nymph.lyrics.app.editor.reorderTargetIndex
import dev.nymph.lyrics.app.editor.selectionIsAllRests

/**
 * The full-screen grid editor. Back clears the selection, then closes the active row's field,
 * then saves (the web editor's Escape / click-outside).
 */
@Composable
internal fun GridEditorScreen(
    state: EditorUiState,
    actions: GridEditorActions,
    onDiscard: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snackbar = remember { SnackbarHostState() }
    val save = state.save
    LaunchedEffect(save) {
        if (save is SaveStatus.Failed) {
            snackbar.showSnackbar("Save failed: ${save.error.message}")
            actions.acknowledgeSaveError()
        }
    }
    BackHandler {
        when {
            state.selection != null -> actions.clearSelection()
            state.rows.activeRow >= 0 -> actions.deactivateRow()
            else -> actions.save()
        }
    }
    Scaffold(
        modifier = modifier,
        topBar = {
            EditorTopBar(
                snippetType = state.snippetType,
                saving = state.isSaving,
                onSnippetTypeChange = actions::setSnippetType,
                onDiscard = onDiscard,
                onSave = actions::save,
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding(),
        ) {
            EditorRows(state, actions, Modifier.weight(1f))
            HintBar(state)
        }
    }
}

private enum class DropSide { ABOVE, BELOW }

@Composable
private fun EditorRows(state: EditorUiState, actions: GridEditorActions, modifier: Modifier = Modifier) {
    val rows = state.rows
    val haptics = LocalHapticFeedback.current
    val spans = remember { mutableStateMapOf<Int, RowSpan>() }
    var dragFrom by remember { mutableStateOf<Int?>(null) }
    var dragDy by remember { mutableFloatStateOf(0f) }
    val lineCount = rows.lines.size
    val currentLineCount by rememberUpdatedState(lineCount)
    val currentActions by rememberUpdatedState(actions)
    val spanList = List(lineCount) { spans[it] }
    val target = dragFrom?.let { reorderTargetIndex(spanList, it, dragDy) }
    val indicator = MaterialTheme.colorScheme.primary
    val dragTint = MaterialTheme.colorScheme.surfaceVariant

    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        rows.lines.forEachIndexed { row, text ->
            val sel = state.selection?.takeIf { it.row == row }
            val model = RowModel(
                index = row,
                text = text,
                line = state.rowLines.getOrNull(row),
                isActive = rows.activeRow == row,
                arranged = row in rows.arranged,
                selection = sel,
                canRemoveRests = sel != null && selectionIsAllRests(state),
                canMoveUp = row > 0,
                canMoveDown = row < lineCount - 1,
            )
            val dragged = dragFrom == row
            val side = when {
                target == null || dragged || target != row -> null
                target < (dragFrom ?: row) -> DropSide.ABOVE
                else -> DropSide.BELOW
            }
            val bringIntoView = remember { BringIntoViewRequester() }
            LaunchedEffect(model.isActive) { if (model.isActive) bringIntoView.bringIntoView() }
            EditorRow(
                model = model,
                extendMode = state.extendMode,
                actions = actions,
                handleModifier = Modifier.pointerInput(row) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            dragFrom = row
                            dragDy = 0f
                        },
                        onDrag = { change, amount ->
                            change.consume()
                            dragDy += amount.y
                        },
                        onDragEnd = {
                            val to = reorderTargetIndex(List(currentLineCount) { spans[it] }, row, dragDy)
                            dragFrom = null
                            dragDy = 0f
                            currentActions.reorderRow(row, to)
                        },
                        onDragCancel = {
                            dragFrom = null
                            dragDy = 0f
                        },
                    )
                },
                modifier = Modifier
                    .onGloballyPositioned { c ->
                        val top = c.positionInParent().y
                        spans[row] = RowSpan(top, top + c.size.height)
                    }
                    .bringIntoViewRequester(bringIntoView)
                    .zIndex(if (dragged) 1f else 0f)
                    .graphicsLayer { translationY = if (dragged) dragDy else 0f }
                    .then(if (dragged) Modifier.background(dragTint) else Modifier)
                    .drawWithContent {
                        drawContent()
                        if (side != null) {
                            val y = if (side == DropSide.ABOVE) 0f else size.height
                            drawLine(indicator, Offset(0f, y), Offset(size.width, y), strokeWidth = 3.dp.toPx())
                        }
                    },
            )
        }
        TextButton(onClick = { actions.insertRowAfter(lineCount - 1) }, modifier = Modifier.padding(start = 8.dp)) {
            Text("+ Add line")
        }
    }
}

@Composable
private fun HintBar(state: EditorUiState) {
    val hint = if (state.selection != null) {
        "Long-press the chunk and drag to move · drag the dots to extend · +· rest · − remove rests"
    } else {
        "Tap a syllable to select · tap a line number to edit · long-press ≡ to move a line"
    }
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        state.syllabifyError?.let {
            Text(
                "Syllables are local estimates (syllabify failed: ${it.message})",
                style = MaterialTheme.typography.labelSmall,
                color = colors.error,
            )
        }
        Text(hint, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
    }
}
