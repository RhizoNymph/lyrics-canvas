package dev.nymph.lyrics.logic.canvas

import dev.nymph.lyrics.model.ViewportState

/** A point in world or screen coordinates (the type does not say which; the function names do). */
data class Point(val x: Double, val y: Double)

/** The canvas element's size in screen pixels. */
data class ViewSize(val width: Double, val height: Double)

const val MIN_ZOOM: Double = 0.1
const val MAX_ZOOM: Double = 5.0

fun clampZoom(zoom: Double): Double = zoom.coerceIn(MIN_ZOOM, MAX_ZOOM)

/**
 * The canvas camera, matching the web client: `screen = (world + (x, y)) * zoom`, so
 * `world = screen / zoom - (x, y)`. [x] and [y] are a world-space offset, not pixels.
 */
data class Viewport(val x: Double, val y: Double, val zoom: Double) {
    init {
        require(zoom > 0.0 && zoom.isFinite()) { "zoom must be positive and finite, was $zoom" }
    }

    fun worldToScreen(world: Point): Point = Point((world.x + x) * zoom, (world.y + y) * zoom)

    fun screenToWorld(screen: Point): Point = Point(screen.x / zoom - x, screen.y / zoom - y)

    /**
     * Set the zoom (clamped to [MIN_ZOOM]..[MAX_ZOOM]) while keeping the world point under the
     * screen point [focal] (e.g. the pinch centroid) where it is.
     */
    fun zoomAbout(focal: Point, newZoom: Double): Viewport {
        val z = clampZoom(newZoom)
        val world = screenToWorld(focal)
        return Viewport(focal.x / z - world.x, focal.y / z - world.y, z)
    }

    /** Multiply the zoom by [factor] (a pinch gesture's scale change) about [focal]. */
    fun scaleAbout(focal: Point, factor: Double): Viewport = zoomAbout(focal, zoom * factor)

    /** Move the content by [screenDelta] pixels (a drag): the world offset changes by delta / zoom. */
    fun panBy(screenDelta: Point): Viewport = copy(x = x + screenDelta.x / zoom, y = y + screenDelta.y / zoom)

    fun toState(): ViewportState = ViewportState(x, y, zoom)

    companion object {
        val IDENTITY: Viewport = Viewport(0.0, 0.0, 1.0)

        fun clamped(x: Double, y: Double, zoom: Double): Viewport =
            Viewport(x, y, if (zoom.isNaN()) 1.0 else clampZoom(zoom))

        /** The saved canvas viewport, clamped; the identity viewport when none was saved. */
        fun from(state: ViewportState?): Viewport = state?.let { clamped(it.x, it.y, it.zoom) } ?: IDENTITY
    }
}

/** The world point currently at the centre of the canvas element. */
fun viewportCenter(viewport: Viewport, viewSize: ViewSize): Point = Point(
    viewSize.width / (2 * viewport.zoom) - viewport.x,
    viewSize.height / (2 * viewport.zoom) - viewport.y,
)
