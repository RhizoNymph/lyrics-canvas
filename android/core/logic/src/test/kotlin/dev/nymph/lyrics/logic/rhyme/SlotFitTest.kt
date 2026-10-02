package dev.nymph.lyrics.logic.rhyme

import dev.nymph.lyrics.logic.snippet
import dev.nymph.lyrics.model.AnchorRange
import dev.nymph.lyrics.model.GridCell
import dev.nymph.lyrics.model.GridData
import dev.nymph.lyrics.model.GridLine
import dev.nymph.lyrics.model.Slot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SlotFitTest {
    private data class C(val s: Int? = null, val rest: Boolean = false)

    private fun line(cells: List<C>) = GridLine(
        cells.map { if (it.rest) GridCell(rest = true) else GridCell(syllable = "x", stress = it.s) },
        cells.count { !it.rest },
        "x",
    )

    @Test
    fun `slotFromGridLine uses one character per cell, rests as 0`() {
        assertEquals(Slot(4, "1002"), slotFromGridLine(line(listOf(C(1), C(0), C(rest = true), C(2)))))
    }

    @Test
    fun `slotFromGridLine treats missing stress as 0`() {
        assertEquals(Slot(2, "01"), slotFromGridLine(line(listOf(C(), C(1)))))
    }

    @Test
    fun `slotFromGridLine returns null for an empty line`() {
        assertNull(slotFromGridLine(line(emptyList())))
    }

    private val threeLines = snippet(
        "s",
        gridData = GridData(listOf(line(listOf(C(1), C(0))), line(listOf(C(0), C(1), C(0))), line(emptyList()))),
    )

    @Test
    fun `slotFromSnippet picks the requested line`() {
        assertEquals(Slot(2, "10"), slotFromSnippet(threeLines, 0))
    }

    @Test
    fun `null line index means the last non-empty line`() {
        assertEquals(1, lastNonEmptyLineIndex(threeLines))
        assertEquals(Slot(3, "010"), slotFromSnippet(threeLines, null))
    }

    @Test
    fun `slotFromSnippet returns null with no grid data or an out-of-range line`() {
        assertNull(slotFromSnippet(snippet("n", gridData = null), null))
        assertNull(slotFromSnippet(threeLines, 9))
    }

    @Test
    fun `fits requires equal length`() {
        assertFalse(fits("10", Slot(3, "100")))
        assertTrue(fits("100", Slot(3, "100")))
    }

    @Test
    fun `fits rejects a stressed candidate syllable on a slot 0`() {
        assertFalse(fits("01", Slot(2, "10")))
        assertFalse(fits("21", Slot(2, "10")))
    }

    @Test
    fun `fits allows unstressed candidate syllables anywhere and stress on slot 1 or 2`() {
        assertTrue(fits("00", Slot(2, "10")))
        assertTrue(fits("10", Slot(2, "20")))
        assertTrue(fits("11", Slot(2, "11")))
    }

    @Test
    fun `parseManualSlot accepts a valid count and stress string`() {
        assertEquals(ManualSlotResult.Ok(Slot(3, "010")), parseManualSlot("3", "010"))
    }

    @Test
    fun `parseManualSlot rejects stress strings outside 012 and mismatched counts`() {
        assertEquals(ManualSlotResult.Invalid(ManualSlotError.STRESS), parseManualSlot("2", "13"))
        assertEquals(ManualSlotResult.Invalid(ManualSlotError.STRESS), parseManualSlot("2", ""))
        assertEquals(ManualSlotResult.Invalid(ManualSlotError.COUNT), parseManualSlot("3", "10"))
        assertEquals(ManualSlotResult.Invalid(ManualSlotError.COUNT), parseManualSlot("x", "10"))
        assertEquals(ManualSlotResult.Invalid(ManualSlotError.STRESS), parseManualSlot("0", ""))
    }

    private val hinge = snippet(
        "h",
        gridData = GridData(
            listOf(
                GridLine(
                    listOf(
                        GridCell(syllable = "or", stress = 1, wordIndex = 0),
                        GridCell(syllable = "ange", stress = 0, wordIndex = 0),
                        GridCell(rest = true),
                        GridCell(syllable = "door", stress = 1, wordIndex = 1),
                        GridCell(syllable = "hinge", stress = 1, wordIndex = 2),
                        GridCell(rest = true),
                    ),
                    4,
                    "orange door hinge",
                ),
            ),
        ),
    )

    @Test
    fun `slotFromRange derives the slot from the cells of the selected words, interior rests included`() {
        assertEquals(Slot(2, "11"), slotFromRange(hinge, AnchorRange(0, 1, 3)))
        assertEquals(Slot(4, "1001"), slotFromRange(hinge, AnchorRange(0, 0, 2)))
    }

    @Test
    fun `slotFromRange returns null when the range has no cells or the line is missing`() {
        assertNull(slotFromRange(hinge, AnchorRange(3, 0, 1)))
        assertNull(slotFromRange(hinge, AnchorRange(0, 7, 8)))
    }
}
