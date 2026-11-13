package dev.nymph.lyrics.logic.rhyme.pattern

import dev.nymph.lyrics.logic.rhyme.pattern.PatternFx.board
import dev.nymph.lyrics.logic.rhyme.pattern.PatternFx.slot
import dev.nymph.lyrics.logic.rhyme.pattern.PatternFx.span
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WordActionsTest {
    private fun labels(actions: List<WordAction>): List<String> = actions.map { a ->
        val name = when (a) {
            is WordAction.AddTo -> "add ${a.letter}"
            is WordAction.NewSlot -> "new"
            is WordAction.Remove -> "remove ${a.letter}"
        }
        if (a.refusal == null) name else "$name (refused)"
    }

    @Test
    fun `a free word offers every slot, a new slot, and no remove`() {
        // Line 0 word 4 ("when") sits between s0 and s1: adding it to s2 would put s2 before s1.
        val actions = wordActions(board(), 0, 4)
        assertEquals(listOf("add A", "add B", "add C (refused)", "new"), labels(actions))
        val refused = actions[2].refusal as SlotEditRefusal.CrossesOrder
        assertEquals(SlotId("s1"), refused.other)
    }

    @Test
    fun `a word in a slot skips that slot and offers remove`() {
        // "heavy" (s0 on line 1) can move to s1 (s0 then has no line-1 member), not to s2 (it would precede s1's "weather").
        val actions = wordActions(board(), 1, 1)
        assertEquals(listOf("add B", "add C (refused)", "new", "remove A"), labels(actions))
    }

    @Test
    fun `new slot is refused at eight slots`() {
        val eight = board((0 until 8).map { slot("s$it", span(0, it)) })
        val new = wordActions(eight, 1, 0).single { it is WordAction.NewSlot }
        assertEquals(SlotEditRefusal.TooManySlots, new.refusal)
    }

    @Test
    fun `every offered action applies when it is not refused`() {
        val b = board()
        for (line in 0 until 3) {
            for (word in 0 until b.line(line)!!.wordCount) {
                wordActions(b, line, word).filter { it.refusal == null }.forEach { action ->
                    assertTrue(applySlotEdit(b, action.edit) is SlotEditResult.Applied, "$action")
                }
            }
        }
    }

    @Test
    fun `a missing word has no actions`() {
        assertTrue(wordActions(board(), 0, 99).isEmpty())
        assertNull(board().slotAt(0, 0))
    }
}
