package dev.nymph.lyrics.app.canvas.list

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Icon
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.zIndex
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import dev.nymph.lyrics.app.canvas.render.CardLayout
import dev.nymph.lyrics.app.canvas.render.CardMetrics
import dev.nymph.lyrics.app.canvas.render.CardTextStyles
import dev.nymph.lyrics.app.canvas.render.cardLayout
import dev.nymph.lyrics.app.canvas.render.drawCard
import dev.nymph.lyrics.app.ui.theme.Palette
import dev.nymph.lyrics.model.Snippet
import dev.nymph.lyrics.model.SnippetId
import kotlin.math.ceil

/** Text measurements for the visible rows' syllables and labels. */
private const val TEXT_CACHE_SIZE = 512

/** Horizontal gutter of the list (each side). */
private val ROW_GUTTER = 16.dp

/** Space reserved right of / below a card for its drop shadow. */
private val SHADOW_SPACE = CardMetrics.SHADOW_OFFSET.dp

/** The drag handle's touch target (left of each row's card). */
private val HANDLE_WIDTH = 40.dp

/** Distance from the list's top / bottom edge in which a dragged row scrolls the list. */
private val EDGE_SCROLL_ZONE = 64.dp

/** The fastest edge scroll per drag frame. */
private val EDGE_SCROLL_STEP = 24.dp

/**
 * A row being dragged by its handle: [rows] is the list as it is being rearranged (the dragged
 * row moves through it as it passes its neighbours), [offset] the finger's distance in px from
 * the row's laid-out place.
 */
private class RowDrag {
    var id by mutableStateOf<SnippetId?>(null)
    var rows by mutableStateOf<List<Snippet>>(emptyList())
    var offset by mutableFloatStateOf(0f)

    fun start(id: SnippetId, rows: List<Snippet>) {
        this.id = id
        this.rows = rows
        offset = 0f
    }

    fun clear() {
        id = null
        rows = emptyList()
        offset = 0f
    }
}

/**
 * List mode: the canvas's snippets as a vertically scrolling column of cards, in the order the
 * ViewModel captured ([rows]). Each row is the canvas card ([cardLayout] + [drawCard]) sized by
 * [listCardWidth] / [listCardHeight]. Tap -> [onTap]; long-press -> [onLongPress] (selection).
 *
 * With [onMove], each row has a drag handle on its left: dragging it moves the row through the
 * list (the list scrolls near its edges) and the drop reports `onMove(id, index)`, the row's
 * index among the other rows. The handle also offers "Move up" / "Move down" accessibility
 * actions. Long-press on the card keeps meaning "select".
 */
@Composable
fun SnippetList(
    rows: List<Snippet>,
    selected: Set<SnippetId>,
    onTap: (SnippetId) -> Unit,
    onLongPress: (SnippetId) -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    /** The tag filter or the search is hiding snippets (changes the empty-list text). */
    filtered: Boolean = false,
    /** Snippets with a parked change ("Not synced" badge). */
    notSynced: Set<SnippetId> = emptySet(),
    /** Drop of a dragged row at its new index among the others; null hides the handles. */
    onMove: ((SnippetId, Int) -> Unit)? = null,
) {
    if (rows.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(if (filtered) "No snippets match the filter" else "No snippets yet", color = Palette.FallbackText)
        }
        return
    }
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer(cacheSize = TEXT_CACHE_SIZE)
    val styles = remember(density) { CardTextStyles(density) }
    val drag = remember { RowDrag() }
    val currentRows by rememberUpdatedState(rows)
    val currentOnMove by rememberUpdatedState(onMove)
    val shown = if (drag.id != null) drag.rows else rows
    val edgeZone = with(density) { EDGE_SCROLL_ZONE.toPx() }
    val edgeStep = with(density) { EDGE_SCROLL_STEP.toPx() }

    fun dragBy(id: SnippetId, dy: Float) {
        drag.offset += dy
        val info = listState.layoutInfo
        val items = info.visibleItemsInfo
        val current = items.firstOrNull { it.key == id.value } ?: return
        val span = RowSpan(current.index, current.offset, current.size)
        val scroll = edgeScroll(span.top + drag.offset, span.top + span.size + drag.offset, info.viewportStartOffset, info.viewportEndOffset, edgeZone, edgeStep)
        if (scroll != 0f) {
            // The rows move up by what the list scrolled; the dragged one stays under the finger.
            drag.offset += listState.dispatchRawDelta(scroll)
        }
        val above = items.firstOrNull { it.index == current.index - 1 }?.let { RowSpan(it.index, it.offset, it.size) }
        val below = items.firstOrNull { it.index == current.index + 1 }?.let { RowSpan(it.index, it.offset, it.size) }
        when (val step = dragStep(span, drag.offset, above, below)) {
            DragStep.Stay -> Unit
            is DragStep.Swap -> {
                drag.rows = drag.rows.moved(current.index, step.index)
                drag.offset += step.offsetShift
            }
        }
    }

    fun drop() {
        val id = drag.id ?: return
        val index = drag.rows.indexOfFirst { it.id == id }
        drag.clear()
        if (index >= 0) currentOnMove?.invoke(id, index)
    }

    BoxWithConstraints(modifier.fillMaxSize().background(Palette.CanvasBackground)) {
        val handle = if (onMove != null) HANDLE_WIDTH else 0.dp
        val availableDp = (maxWidth - ROW_GUTTER * 2 - SHADOW_SPACE - handle).value.toDouble()
        LazyColumn(
            Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(horizontal = ROW_GUTTER, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(shown, key = { it.id.value }) { snippet ->
                val dragging = drag.id == snippet.id
                Row(
                    Modifier
                        .zIndex(if (dragging) 1f else 0f)
                        .graphicsLayer { translationY = if (dragging) drag.offset else 0f }
                        .alpha(if (dragging) 0.85f else 1f),
                ) {
                    if (onMove != null) {
                        DragHandle(
                            onStart = { drag.start(snippet.id, currentRows) },
                            onDrag = { dy -> dragBy(snippet.id, dy) },
                            onEnd = ::drop,
                            onCancel = drag::clear,
                            onStep = { delta ->
                                val index = currentRows.indexOfFirst { it.id == snippet.id }
                                if (index >= 0) currentOnMove?.invoke(snippet.id, index + delta)
                            },
                        )
                    }
                    SnippetListRow(
                        snippet = snippet,
                        selected = snippet.id in selected,
                        notSynced = snippet.id in notSynced,
                        availableDp = availableDp,
                        measurer = measurer,
                        styles = styles,
                        onTap = { onTap(snippet.id) },
                        onLongPress = { onLongPress(snippet.id) },
                    )
                }
            }
        }
    }
}

/** A row's drag handle: drag to move the row; "Move up" / "Move down" for accessibility services ([onStep] ±1). */
@Composable
private fun DragHandle(onStart: () -> Unit, onDrag: (Float) -> Unit, onEnd: () -> Unit, onCancel: () -> Unit, onStep: (Int) -> Unit) {
    val start by rememberUpdatedState(onStart)
    val move by rememberUpdatedState(onDrag)
    val end by rememberUpdatedState(onEnd)
    val cancel by rememberUpdatedState(onCancel)
    Box(
        Modifier
            .size(HANDLE_WIDTH, HANDLE_WIDTH + 8.dp)
            .semantics {
                customActions = listOf(
                    CustomAccessibilityAction("Move up") { onStep(-1).let { true } },
                    CustomAccessibilityAction("Move down") { onStep(1).let { true } },
                )
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { start() },
                    onDragEnd = { end() },
                    onDragCancel = { cancel() },
                    onDrag = { change, amount ->
                        change.consume()
                        move(amount.y)
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Default.Menu, contentDescription = "Drag to reorder", tint = Palette.Muted)
    }
}

/** A row card: the list-sized snippet, its layout and the scale it is drawn at. */
private data class RowCard(val layout: CardLayout, val card: Snippet, val scale: Double)

private fun rowCard(snippet: Snippet, availableDp: Double, measurer: TextMeasurer, styles: CardTextStyles): RowCard {
    val fit = listCardWidth(snippet, availableDp)
    // The raw-text fallback wraps inside the card, so its height is measured at the card's inner width.
    val fallback = cardLayout(listCard(snippet, fit.width, CardMetrics.HEADER_HEIGHT)).fallbackText
    val fallbackHeight = fallback?.let {
        val innerWidth = (fit.width - CardMetrics.PADDING * 2).toInt().coerceAtLeast(1)
        measurer.measure(it, styles.fallback, constraints = Constraints(maxWidth = innerWidth)).size.height.toDouble()
    } ?: 0.0
    val card = listCard(snippet, fit.width, ceil(listCardHeight(snippet, fallbackHeight)))
    return RowCard(cardLayout(card), card, fit.scale)
}

@Composable
private fun SnippetListRow(
    snippet: Snippet,
    selected: Boolean,
    notSynced: Boolean,
    availableDp: Double,
    measurer: TextMeasurer,
    styles: CardTextStyles,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
) {
    val row = remember(snippet, availableDp, styles) { rowCard(snippet, availableDp, measurer, styles) }
    val widthDp = (row.layout.width * row.scale).dp + SHADOW_SPACE
    val heightDp = (row.layout.height * row.scale).dp + SHADOW_SPACE
    Box(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .combinedClickable(onClick = onTap, onLongClick = onLongPress),
    ) {
        Canvas(Modifier.size(widthDp, heightDp)) {
            drawCard(
                snippet = row.card,
                layout = row.layout,
                originPx = Offset.Zero,
                zoom = row.scale,
                pxPerDp = density,
                selected = selected,
                measurer = measurer,
                styles = styles,
                notSynced = notSynced,
            )
        }
    }
}
