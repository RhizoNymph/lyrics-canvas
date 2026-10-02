package dev.nymph.lyrics.logic.rhyme

import dev.nymph.lyrics.logic.canvas.Point
import dev.nymph.lyrics.logic.canvas.ViewSize
import dev.nymph.lyrics.logic.canvas.Viewport
import dev.nymph.lyrics.logic.canvas.viewportCenter
import dev.nymph.lyrics.model.Snippet
import dev.nymph.lyrics.model.SnippetId

/** Horizontal gap between the anchor's right edge and the new snippet. */
const val MATERIALIZE_GAP_X: Double = 40.0

/** Vertical step per earlier materialization from the same anchor. */
const val MATERIALIZE_STEP_Y: Double = 60.0

/** The part of an anchor snippet that placement needs. */
data class AnchorRect(val id: SnippetId, val positionX: Double, val positionY: Double, val width: Double)

fun Snippet.toAnchorRect(): AnchorRect = AnchorRect(id, positionX, positionY, width)

/**
 * Where a materialized candidate lands on the canvas. With exactly one anchor snippet: to its
 * right on the same row, one row lower per earlier materialization from it (so new snippets do
 * not stack). Otherwise (typed anchor only, or several anchor snippets): the viewport centre.
 *
 * @param anchors the snippets currently acting as anchors (selected, non-blank, not excluded)
 * @param counts materializations already made per anchor snippet this session
 */
fun materializePosition(
    anchors: List<AnchorRect>,
    counts: Map<SnippetId, Int>,
    viewport: Viewport,
    viewSize: ViewSize,
): Point {
    if (anchors.size != 1) return viewportCenter(viewport, viewSize)
    val anchor = anchors[0]
    val prior = counts[anchor.id] ?: 0
    return Point(anchor.positionX + anchor.width + MATERIALIZE_GAP_X, anchor.positionY + prior * MATERIALIZE_STEP_Y)
}
