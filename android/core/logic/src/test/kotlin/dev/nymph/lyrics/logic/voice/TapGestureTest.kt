package dev.nymph.lyrics.logic.voice

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TapGestureTest {
    private val start = TapState.Initial

    @Test
    fun `a down records its time as pending, not yet a break`() {
        val step = start.down(1_000)
        assertFalse(step.stop)
        assertEquals(1_000L, step.state.pending)
        assertEquals(emptyList<Long>(), step.state.breaks)
        assertEquals(1, step.state.lineNumber)
    }

    @Test
    fun `the pending break commits at the first tap's down time once the window has passed`() {
        val pending = start.down(1_000).state
        assertSame(pending, pending.tick(1_299))
        val committed = pending.tick(1_300)
        assertEquals(listOf(1_000L), committed.breaks)
        assertNull(committed.pending)
        assertEquals(2, committed.lineNumber)
    }

    @Test
    fun `a second down within the window is a double tap - stop and the pending break is dropped`() {
        val first = start.down(1_000).state.tick(1_300).down(5_000).state
        val second = first.down(5_250)
        assertTrue(second.stop)
        assertEquals(listOf(1_000L), second.state.breaks)
        assertNull(second.state.pending)
    }

    @Test
    fun `a second down exactly at the window edge is a new tap, committing the first`() {
        val step = start.down(1_000).state.down(1_300)
        assertFalse(step.stop)
        assertEquals(listOf(1_000L), step.state.breaks)
        assertEquals(1_300L, step.state.pending)
    }

    @Test
    fun `a late tick does not turn a slow second tap into a double tap`() {
        // No tick ran between the downs (sampling lag): the times alone decide.
        val step = start.down(1_000).state.down(1_450)
        assertFalse(step.stop)
        assertEquals(listOf(1_000L), step.state.breaks)
        assertEquals(1_450L, step.state.pending)
    }

    @Test
    fun `finish commits a pending tap (Stop button, silence stop)`() {
        assertEquals(listOf(1_000L, 2_000L), start.down(1_000).state.down(2_000).state.finish())
        assertEquals(emptyList<Long>(), start.finish())
    }

    @Test
    fun `tick without a pending tap is a no-op`() {
        assertSame(start, start.tick(10_000))
    }

    @Test
    fun `a custom window is honoured`() {
        assertTrue(start.down(0).state.down(450, windowMs = 500).stop)
        assertFalse(start.down(0).state.down(450, windowMs = 400).stop)
    }

    @Test
    fun `three quick taps - the first two stop, the third never arrives in a state`() {
        val step = start.down(100).state.down(200)
        assertTrue(step.stop)
        assertEquals(emptyList<Long>(), step.state.finish())
    }
}
