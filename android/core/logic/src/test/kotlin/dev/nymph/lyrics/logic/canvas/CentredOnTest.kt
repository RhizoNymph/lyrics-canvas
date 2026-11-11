package dev.nymph.lyrics.logic.canvas

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CentredOnTest {
    @Test
    fun `the world point lands at the view centre and the zoom is kept`() {
        val size = ViewSize(800.0, 600.0)
        val v = centredOn(Viewport(13.0, -7.0, 2.0), Point(500.0, 250.0), size)
        assertEquals(2.0, v.zoom)
        assertEquals(Point(400.0, 300.0), v.worldToScreen(Point(500.0, 250.0)))
        assertEquals(Point(500.0, 250.0), viewportCenter(v, size))
    }
}
