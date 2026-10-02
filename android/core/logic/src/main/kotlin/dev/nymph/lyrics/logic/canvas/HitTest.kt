package dev.nymph.lyrics.logic.canvas

import dev.nymph.lyrics.model.Snippet
import dev.nymph.lyrics.model.SnippetId

/** An axis-aligned rectangle; [contains] includes the top-left edge and excludes the bottom-right. */
data class Rect(val left: Double, val top: Double, val width: Double, val height: Double) {
    val right: Double get() = left + width
    val bottom: Double get() = top + height

    fun contains(p: Point): Boolean = p.x >= left && p.x < right && p.y >= top && p.y < bottom
}

/** Per-card scale bounds (the web's corner-resize handle clamps to these). */
const val MIN_SNIPPET_SCALE: Double = 0.3
const val MAX_SNIPPET_SCALE: Double = 4.0

fun clampSnippetScale(scale: Double): Double = scale.coerceIn(MIN_SNIPPET_SCALE, MAX_SNIPPET_SCALE)

/** A card's world rectangle: anchored at its position, sized width/height times its scale. */
fun cardBounds(snippet: Snippet, scale: Double = 1.0): Rect {
    val s = clampSnippetScale(scale)
    return Rect(snippet.positionX, snippet.positionY, snippet.width * s, snippet.height * s)
}

/** A card's screen rectangle under [viewport]. */
fun cardScreenBounds(snippet: Snippet, scale: Double, viewport: Viewport): Rect {
    val topLeft = viewport.worldToScreen(Point(snippet.positionX, snippet.positionY))
    val s = viewport.zoom * clampSnippetScale(scale)
    return Rect(topLeft.x, topLeft.y, snippet.width * s, snippet.height * s)
}

/** Paint order: ascending z; equal z keeps list order (later is drawn on top). */
fun drawOrder(snippets: List<Snippet>): List<Snippet> = snippets.sortedBy { it.zIndex }

/** The topmost card containing the world point [world], or null over empty canvas. */
fun hitTest(snippets: List<Snippet>, world: Point, scales: Map<SnippetId, Double> = emptyMap()): Snippet? =
    drawOrder(snippets).lastOrNull { cardBounds(it, scales[it.id] ?: 1.0).contains(world) }

/** [hitTest] for a screen point (a touch position). */
fun hitTestScreen(
    snippets: List<Snippet>,
    screen: Point,
    viewport: Viewport,
    scales: Map<SnippetId, Double> = emptyMap(),
): Snippet? = hitTest(snippets, viewport.screenToWorld(screen), scales)
