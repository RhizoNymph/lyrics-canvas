package dev.nymph.lyrics.app.canvas

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import dev.nymph.lyrics.app.canvas.gesture.CanvasGestureAction
import dev.nymph.lyrics.app.canvas.gesture.canvasGestures
import dev.nymph.lyrics.app.canvas.render.CardLayout
import dev.nymph.lyrics.app.canvas.render.CardTextStyles
import dev.nymph.lyrics.app.canvas.render.backgroundGridLines
import dev.nymph.lyrics.app.canvas.render.cardLayout
import dev.nymph.lyrics.app.canvas.render.drawCard
import dev.nymph.lyrics.app.canvas.render.visibleCards
import dev.nymph.lyrics.app.ui.theme.Palette
import dev.nymph.lyrics.logic.canvas.Point
import dev.nymph.lyrics.logic.canvas.ViewSize
import dev.nymph.lyrics.model.Snippet
import dev.nymph.lyrics.model.SnippetId

/** Text measurements per distinct syllable / label; sized for a few hundred visible cells. */
private const val TEXT_CACHE_SIZE = 512

/**
 * The canvas surface: background grid and the cards on screen, drawn in one Compose `Canvas`.
 * Layout and culling are in [dev.nymph.lyrics.app.canvas.render]; gestures in [canvasGestures].
 */
@Composable
fun SnippetCanvas(
    state: CanvasUiState.Ready,
    hitTest: (Point) -> SnippetId?,
    onAction: (CanvasGestureAction) -> Unit,
    onViewSize: (ViewSize) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer(cacheSize = TEXT_CACHE_SIZE)
    val styles = remember(density) { CardTextStyles(density) }
    // Layout depends only on the snippet, so it survives pans, zooms and selection changes.
    val layouts = remember { LayoutCache() }
    val selected = state.selection.ids

    Canvas(
        modifier
            .fillMaxSize()
            .onSizeChanged { onViewSize(ViewSize((it.width / density.density).toDouble(), (it.height / density.density).toDouble())) }
            .canvasGestures(hitTest, onAction),
    ) {
        val px = density.density
        drawRect(Palette.CanvasBackground)
        val viewport = state.viewport
        val view = ViewSize((size.width / px).toDouble(), (size.height / px).toDouble())

        val grid = backgroundGridLines(viewport, view)
        for (x in grid.xs) drawLine(Palette.CanvasGridLine, Offset(x.toFloat() * px, 0f), Offset(x.toFloat() * px, size.height), strokeWidth = 1f)
        for (y in grid.ys) drawLine(Palette.CanvasGridLine, Offset(0f, y.toFloat() * px), Offset(size.width, y.toFloat() * px), strokeWidth = 1f)

        val visible = visibleCards(state.snippets, viewport, view)
        layouts.retain(state.snippets)
        for (snippet in visible) {
            val origin = viewport.worldToScreen(Point(snippet.positionX, snippet.positionY))
            drawCard(
                snippet = snippet,
                layout = layouts.get(snippet),
                originPx = Offset(origin.x.toFloat() * px, origin.y.toFloat() * px),
                zoom = viewport.zoom,
                pxPerDp = px,
                selected = snippet.id in selected,
                measurer = measurer,
                styles = styles,
            )
        }
    }
}

/** Card layouts by snippet id, reused while the fields the layout reads are unchanged (moves keep the grid instance). */
private class LayoutCache {
    private val byId = HashMap<SnippetId, Pair<Snippet, CardLayout>>()

    fun get(snippet: Snippet): CardLayout {
        val cached = byId[snippet.id]
        val c = cached?.first
        if (c != null && c.gridData === snippet.gridData && c.width == snippet.width && c.height == snippet.height &&
            c.snippetType == snippet.snippetType && c.tags == snippet.tags && c.rawText == snippet.rawText
        ) {
            return cached.second
        }
        return cardLayout(snippet).also { byId[snippet.id] = snippet to it }
    }

    fun retain(snippets: List<Snippet>) {
        if (byId.size > snippets.size) {
            val live = snippets.mapTo(HashSet()) { it.id }
            byId.keys.retainAll(live)
        }
    }
}
