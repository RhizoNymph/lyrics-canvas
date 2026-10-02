package dev.nymph.lyrics.logic.grid

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class DropIndexTest {
    @Test
    fun `gapFromX snaps to the nearest cell boundary`() {
        assertEquals(0, gapFromX(0.0, 60.0, 4))
        assertEquals(0, gapFromX(29.0, 60.0, 4))
        assertEquals(1, gapFromX(31.0, 60.0, 4))
        assertEquals(3, gapFromX(150.0, 60.0, 4)) // 2.5 rounds to 3
    }

    @Test
    fun `gapFromX clamps to 0 through cellCount`() {
        assertEquals(0, gapFromX(-50.0, 60.0, 4))
        assertEquals(4, gapFromX(1000.0, 60.0, 4))
    }

    @Test
    fun `gapFromX handles a zero or negative cell width without NaN`() {
        assertEquals(0, gapFromX(10.0, 0.0, 4))
    }

    // chunk is cells [2,4) of 6 cells
    @Test
    fun `moveTargetFromGap maps a gap before the chunk directly`() {
        assertEquals(0, moveTargetFromGap(0, 2, 4))
        assertEquals(1, moveTargetFromGap(1, 2, 4))
    }

    @Test
    fun `moveTargetFromGap maps a gap after the chunk into removed-chunk coordinates`() {
        assertEquals(3, moveTargetFromGap(5, 2, 4))
        assertEquals(4, moveTargetFromGap(6, 2, 4))
    }

    @Test
    fun `moveTargetFromGap returns null when the drop would not move the chunk`() {
        assertNull(moveTargetFromGap(2, 2, 4))
        assertNull(moveTargetFromGap(3, 2, 4))
        assertNull(moveTargetFromGap(4, 2, 4))
    }
}
