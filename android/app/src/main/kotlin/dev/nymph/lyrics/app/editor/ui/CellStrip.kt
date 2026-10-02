package dev.nymph.lyrics.app.editor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.nymph.lyrics.app.editor.CellMetrics
import dev.nymph.lyrics.app.editor.CellSelection
import dev.nymph.lyrics.app.editor.SelectionEdge
import dev.nymph.lyrics.app.editor.cellIndexAt
import dev.nymph.lyrics.app.editor.cellIndexClamped
import dev.nymph.lyrics.app.editor.dropGapAt
import dev.nymph.lyrics.app.editor.edgeAutoScrollDelta
import dev.nymph.lyrics.app.editor.handleAt
import dev.nymph.lyrics.model.GridCell
import kotlin.math.roundToInt

/** What a press on the strip turned into before the long-press timeout. */
private enum class PressOutcome { TAP, CANCELLED }

/** Wait for the press [down] to lift (a tap) or to travel past [slop] / be taken by a scroll. */
private suspend fun AwaitPointerEventScope.awaitTapOrCancel(down: PointerInputChange, slop: Float): PressOutcome {
    while (true) {
        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: return PressOutcome.CANCELLED
        if (change.changedToUp()) return PressOutcome.TAP
        if (change.isConsumed || (change.position - down.position).getDistance() > slop) return PressOutcome.CANCELLED
    }
}

/**
 * One row's horizontally scrollable cell strip.
 *
 * Gestures (all in the strip's content coordinates, so the scroll offset is already applied):
 * tap a cell -> [onTapCell]; tap past the cells -> [onTapEmpty]; press on a selection handle and
 * drag -> [onResize]; long-press a cell -> [onBeginDrag] (selects it unless already selected),
 * then drag horizontally: a drop indicator follows the nearest gap and the strip auto-scrolls
 * near its edges; lifting calls [onDrop] with the gap. A plain horizontal swipe scrolls the strip.
 */
@Composable
internal fun CellStrip(
    cells: List<GridCell>,
    selection: CellSelection?,
    showTrailingCell: Boolean,
    onTapCell: (Int) -> Unit,
    onTapEmpty: () -> Unit,
    onBeginDrag: (Int) -> Unit,
    onDrop: (Int) -> Unit,
    onResize: (SelectionEdge, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    var metrics by remember { mutableStateOf<CellMetrics?>(null) }
    var viewportWidth by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    var pointerX by remember { mutableFloatStateOf(0f) }

    val currentSelection by rememberUpdatedState(selection)
    val currentCount by rememberUpdatedState(cells.size)
    val currentOnTapCell by rememberUpdatedState(onTapCell)
    val currentOnTapEmpty by rememberUpdatedState(onTapEmpty)
    val currentOnBeginDrag by rememberUpdatedState(onBeginDrag)
    val currentOnDrop by rememberUpdatedState(onDrop)
    val currentOnResize by rememberUpdatedState(onResize)

    val dropGap: Int? = if (dragging) metrics?.let { dropGapAt(pointerX, it, cells.size) } else null

    LaunchedEffect(dragging) {
        if (!dragging) return@LaunchedEffect
        val edge = with(density) { EditorDimens.AutoScrollEdge.toPx() }
        val step = with(density) { EditorDimens.AutoScrollStep.toPx() }
        while (true) {
            withFrameNanos { }
            val delta = edgeAutoScrollDelta(pointerX - scroll.value, viewportWidth, edge, step)
            // The finger stays put on screen while the content moves under it.
            if (delta != 0f) pointerX += scroll.scrollBy(delta)
        }
    }

    val colors = MaterialTheme.colorScheme
    Box(
        modifier
            .onSizeChanged { viewportWidth = it.width.toFloat() }
            .horizontalScroll(scroll, enabled = !dragging),
    ) {
        Box(
            Modifier.pointerInput(Unit) {
                val handleSlop = EditorDimens.HandleSlop.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = true)
                    val m = metrics ?: return@awaitEachGesture
                    val sel = currentSelection
                    val edge = sel?.let { handleAt(down.position.x, down.position.y, m, it, handleSlop) }
                    if (edge != null) {
                        down.consume()
                        drag(down.id) { change ->
                            currentOnResize(edge, cellIndexClamped(change.position.x, m, currentCount))
                            change.consume()
                        }
                        return@awaitEachGesture
                    }
                    if (down.position.y >= m.cellHeight) return@awaitEachGesture
                    val outcome = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                        awaitTapOrCancel(down, viewConfiguration.touchSlop)
                    }
                    when (outcome) {
                        PressOutcome.TAP -> {
                            val col = cellIndexAt(down.position.x, m, currentCount)
                            if (col != null) currentOnTapCell(col) else currentOnTapEmpty()
                        }
                        PressOutcome.CANCELLED -> Unit
                        null -> {
                            val col = cellIndexAt(down.position.x, m, currentCount) ?: return@awaitEachGesture
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            currentOnBeginDrag(col)
                            pointerX = down.position.x
                            dragging = true
                            var committed = false
                            try {
                                while (true) {
                                    val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                    if (change.changedToUpIgnoreConsumed()) {
                                        change.consume()
                                        committed = true
                                        break
                                    }
                                    pointerX = change.position.x
                                    change.consume()
                                }
                            } finally {
                                dragging = false
                            }
                            val gap = metrics?.let { dropGapAt(pointerX, it, currentCount) }
                            if (committed && gap != null) currentOnDrop(gap)
                        }
                    }
                }
            },
        ) {
            Column {
                Row {
                    cells.forEachIndexed { col, cell ->
                        val measure = if (col == 0) {
                            Modifier.onGloballyPositioned { c ->
                                val w = c.size.width.toFloat()
                                val h = c.size.height.toFloat()
                                if (w > 0f && h > 0f) metrics = CellMetrics(c.positionInParent().x, w, h)
                            }
                        } else {
                            Modifier
                        }
                        Cell(cell, selected = selection != null && col in selection, dragging = dragging, modifier = measure)
                    }
                    if (showTrailingCell) {
                        Box(
                            Modifier
                                .size(EditorDimens.CellWidth, EditorDimens.CellHeight)
                                .background(colors.primary.copy(alpha = 0.04f))
                                .border(0.5.dp, colors.outlineVariant),
                        )
                    }
                }
                if (selection != null) Spacer(Modifier.height(EditorDimens.HandleZone))
            }
            val m = metrics
            if (m != null && dropGap != null) {
                Box(
                    Modifier
                        .offset { IntOffset((m.gapX(dropGap) - 1.dp.toPx()).roundToInt(), 0) }
                        .size(2.dp, EditorDimens.CellHeight)
                        .background(colors.primary),
                )
            }
            if (m != null && selection != null && !dragging) {
                SelectionHandle(m.gapX(selection.start), m.cellHeight)
                SelectionHandle(m.gapX(selection.end), m.cellHeight)
            }
        }
    }
}

@Composable
private fun SelectionHandle(centerX: Float, top: Float) {
    val color = MaterialTheme.colorScheme.primary
    Box(
        Modifier
            .offset {
                val half = EditorDimens.HandleSize.toPx() / 2f
                IntOffset((centerX - half).roundToInt(), (top + 4.dp.toPx()).roundToInt())
            }
            .size(EditorDimens.HandleSize)
            .background(color, CircleShape),
    )
}

@Composable
private fun Cell(cell: GridCell, selected: Boolean, dragging: Boolean, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val stressed = (cell.stress ?: 0) > 0
    val background = when {
        selected && dragging -> colors.primary.copy(alpha = 0.18f)
        selected -> colors.primary.copy(alpha = 0.38f)
        else -> Color.Transparent
    }
    Box(
        modifier
            .size(EditorDimens.CellWidth, EditorDimens.CellHeight)
            .background(background)
            .border(0.5.dp, colors.outlineVariant)
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = if (cell.isRest) "·" else cell.syllable.orEmpty(),
            fontFamily = FontFamily.Monospace,
            fontWeight = if (stressed) FontWeight.Bold else FontWeight.Normal,
            color = if (cell.isRest) colors.onSurfaceVariant.copy(alpha = 0.6f) else colors.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
