package dev.nymph.lyrics.app.rhyme.pattern

import dev.nymph.lyrics.logic.rhyme.pattern.SlotBoard
import dev.nymph.lyrics.logic.rhyme.pattern.SlotEdit
import dev.nymph.lyrics.logic.rhyme.pattern.SlotEditResult
import dev.nymph.lyrics.logic.rhyme.pattern.SlotId
import dev.nymph.lyrics.logic.rhyme.pattern.TupleTray
import dev.nymph.lyrics.logic.rhyme.pattern.applySlotEdit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PatternLegendTest {
    private val detect = PatternFx.detect()
    private val board = SlotBoard.fromDetect(detect)
    private val info = detect.slots.associateBy { SlotId(it.id) }

    @Test
    fun `chips carry letter, last member key, coverage and on state`() {
        val chips = slotChips(board, info)
        assertEquals(listOf("A", "B", "C"), chips.map { it.letter })
        assertEquals("EH 3", chips[0].key)
        assertEquals("3/3", chips[0].coverageText)
        assertTrue(chips[0].full)
        assertFalse(chips[2].on)
        assertTrue(chips[2].dimmed)
        assertEquals("A · EH 3", columnTitle(chips[0]))
    }

    @Test
    fun `an edited slot has no key until the server recomputes it`() {
        val edited = (applySlotEdit(board, SlotEdit.RemoveWord(3, 0)) as SlotEditResult.Applied).board
        val chip = slotChips(edited, info)[0]
        assertNull(chip.key)
        assertEquals("2/3", chip.coverageText)
        assertTrue(chip.dimmed)
        assertEquals("A", columnTitle(chip))
    }

    @Test
    fun `line words mark slot members and the open picker`() {
        val chips = slotChips(board, info)
        val words = lineWords(PatternFx.lines(), board, chips, WordRef(1, 3))
        val steady = words[0][3]
        assertEquals("steady", steady.text)
        assertEquals(SlotId("s0"), steady.slot?.id)
        assertTrue(steady.selected)
        assertNull(words[0][0].slot)
        assertEquals(SlotId("s2"), words[2][5].slot?.id)
    }

    @Test
    fun `match words colour the hit words`() {
        val chips = slotChips(board, info)
        val words = matchWords(PatternFx.lineMatch("m"), chips)
        assertEquals(listOf("ready", "for", "the", "weather"), words.map { it.first })
        assertEquals(listOf("s0", null, null, "s1"), words.map { it.second?.id?.value })
        assertEquals("2 of 3 slots", slotsHitText(PatternFx.lineMatch("m"), 3))
    }

    @Test
    fun `bank text joins the valid picks with commas`() {
        val order = listOf(SlotId("s0"), SlotId("s1"))
        val tray = TupleTray().toggle(SlotId("s0"), "1", "ready").toggle(SlotId("s1"), "2", "the weather")
        assertEquals("ready, the weather", bankText(tray, order))
        assertNull(bankText(TupleTray(), order))
    }

    @Test
    fun `refusal and failure texts`() {
        assertEquals("A pattern has at most 8 slots.", refusalText(dev.nymph.lyrics.logic.rhyme.pattern.SlotEditRefusal.TooManySlots, board))
        val offline = dev.nymph.lyrics.logic.rhyme.pattern.PatternFailure(
            dev.nymph.lyrics.logic.rhyme.pattern.PatternOperation.SUGGEST,
            dev.nymph.lyrics.logic.rhyme.pattern.PatternError.NeedsConnection,
        )
        assertEquals("Suggestions failed: needs connection.", failureText(offline))
    }
}
