package dev.nymph.lyrics.logic.canvas

import dev.nymph.lyrics.model.ViewportState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ViewportTest {
    private val eps = 1e-9

    private fun assertPoint(expected: Point, actual: Point) {
        assertEquals(expected.x, actual.x, eps)
        assertEquals(expected.y, actual.y, eps)
    }

    @Test
    fun `viewportCenter converts the screen centre to world coordinates`() {
        assertEquals(Point(400.0, 300.0), viewportCenter(Viewport(0.0, 0.0, 1.0), ViewSize(800.0, 600.0)))
        assertEquals(Point(300.0, 100.0), viewportCenter(Viewport(-100.0, 50.0, 2.0), ViewSize(800.0, 600.0)))
    }

    @Test
    fun `world to screen matches the web transform`() {
        val v = Viewport(-100.0, 50.0, 2.0)
        assertEquals(Point((10.0 - 100.0) * 2.0, (20.0 + 50.0) * 2.0), v.worldToScreen(Point(10.0, 20.0)))
    }

    @Test
    fun `screen to world inverts world to screen`() {
        val v = Viewport(37.5, -12.25, 1.7)
        val world = Point(123.0, -456.0)
        assertPoint(world, v.screenToWorld(v.worldToScreen(world)))
        assertPoint(Point(400.0 / 1.7 - 37.5, 300.0 / 1.7 + 12.25), v.screenToWorld(Point(400.0, 300.0)))
    }

    @Test
    fun `zooming about a focal point keeps the world point under it fixed`() {
        val v = Viewport(-40.0, 25.0, 1.0)
        val focal = Point(320.0, 180.0)
        val before = v.screenToWorld(focal)
        val zoomed = v.zoomAbout(focal, 2.5)
        assertEquals(2.5, zoomed.zoom, eps)
        assertPoint(before, zoomed.screenToWorld(focal))
        assertPoint(focal, zoomed.worldToScreen(before))
    }

    @Test
    fun `zoom is clamped to 0_1 through 5 and the focal point still holds`() {
        val v = Viewport(0.0, 0.0, 4.0)
        val focal = Point(100.0, 50.0)
        val up = v.zoomAbout(focal, 10.0)
        assertEquals(MAX_ZOOM, up.zoom, eps)
        assertPoint(v.screenToWorld(focal), up.screenToWorld(focal))
        assertEquals(MIN_ZOOM, v.zoomAbout(focal, 0.001).zoom, eps)
        assertEquals(MIN_ZOOM, clampZoom(0.0), eps)
        assertEquals(1.0, clampZoom(1.0), eps)
        assertEquals(MAX_ZOOM, Viewport.clamped(0.0, 0.0, 99.0).zoom, eps)
    }

    @Test
    fun `scaleAbout multiplies the current zoom (pinch factor)`() {
        val v = Viewport(10.0, 10.0, 2.0)
        val focal = Point(50.0, 60.0)
        val pinched = v.scaleAbout(focal, 1.5)
        assertEquals(3.0, pinched.zoom, eps)
        assertPoint(v.screenToWorld(focal), pinched.screenToWorld(focal))
    }

    @Test
    fun `panning by screen pixels moves the world by pixels over zoom`() {
        val v = Viewport(0.0, 0.0, 2.0)
        val panned = v.panBy(Point(40.0, -20.0))
        assertEquals(Viewport(20.0, -10.0, 2.0), panned)
        val world = Point(5.0, 5.0)
        val moved = panned.worldToScreen(world)
        val original = v.worldToScreen(world)
        assertPoint(Point(original.x + 40.0, original.y - 20.0), moved)
    }

    @Test
    fun `converts to and from the persisted viewport state`() {
        assertEquals(Viewport(1.0, 2.0, 3.0), Viewport.from(ViewportState(1.0, 2.0, 3.0)))
        assertEquals(Viewport(0.0, 0.0, 1.0), Viewport.from(null))
        assertEquals(MAX_ZOOM, Viewport.from(ViewportState(0.0, 0.0, 50.0)).zoom, eps)
        assertEquals(ViewportState(1.0, 2.0, 3.0), Viewport(1.0, 2.0, 3.0).toState())
    }
}
