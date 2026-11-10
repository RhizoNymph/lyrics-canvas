package dev.nymph.lyrics.logic.placements

import dev.nymph.lyrics.model.Canvas
import dev.nymph.lyrics.model.CanvasId
import dev.nymph.lyrics.model.UserId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CanvasPickerTest {
    private fun canvas(id: String, title: String, updated: String) =
        Canvas(CanvasId(id), UserId("u"), title, isPublic = false, createdAt = "2026-01-01", updatedAt = updated)

    private val current = canvas("c1", "Current", "2026-11-09 10:00:00+00:00")
    private val old = canvas("c2", "Old hooks", "2026-09-01 10:00:00+00:00")
    private val recent = canvas("c3", "Recent verses", "2026-11-01 10:00:00+00:00")
    private val tie = canvas("c0", "Tied", "2026-11-01 10:00:00+00:00")

    @Test
    fun `every other canvas, newest updated first, ties by id`() {
        assertEquals(listOf(tie, recent, old), pickerCanvases(listOf(old, current, recent, tie), CanvasId("c1"), ""))
    }

    @Test
    fun `the query matches titles case-insensitively after a trim`() {
        assertEquals(listOf(old), pickerCanvases(listOf(old, current, recent), CanvasId("c1"), "  HOOK "))
        assertEquals(emptyList<Canvas>(), pickerCanvases(listOf(old, current, recent), CanvasId("c1"), "current"))
    }
}
