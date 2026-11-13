package dev.nymph.lyrics.logic.rhyme.pattern

import dev.nymph.lyrics.logic.rhyme.pattern.PatternFx.board
import dev.nymph.lyrics.logic.rhyme.pattern.PatternFx.slot
import dev.nymph.lyrics.logic.rhyme.pattern.PatternFx.span
import dev.nymph.lyrics.model.SlotSpec
import dev.nymph.lyrics.model.SpanRef
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SlotEditTest {
    private val start = board()
    private val s0 = SlotId("s0")
    private val s1 = SlotId("s1")
    private val s2 = SlotId("s2")

    /** Every board the reducer produces keeps the invariants. */
    private fun assertValid(b: SlotBoard) {
        assertNull(orderViolation(b.lines, b.slots))
        assertEquals(b.slots.size, b.slots.map { it.id }.toSet().size)
        assertTrue(b.slots.all { it.members.isNotEmpty() })
    }

    @Test
    fun `detect builds the board in server order with default_on`() {
        val b = board(listOf(slot("s0", span(0, 3), span(1, 1)), slot("s1", span(0, 7), span(2, 5), on = false)))
        assertEquals(listOf("s0{0:3-4,1:1-2}", "s1{0:7-8,2:5-6}off"), b.shape())
        assertEquals(3, b.linesWithWords)
        assertEquals(listOf("s0"), b.onSlots.map { it.id.value })
    }

    @Test
    fun `detect drops a slot that would cross an earlier one, or repeats a line`() {
        val crossing = slot("s1", span(0, 1), span(1, 5))
        val twoOnOneLine = slot("s2", span(2, 1), span(2, 3))
        val b = board(listOf(slot("s0", span(0, 3), span(1, 1)), crossing, twoOnOneLine))
        assertEquals(listOf("s0{0:3-4,1:1-2}"), b.shape())
    }

    @Test
    fun `adding a word on a line where the slot has a member replaces it`() {
        val b = start.applied(SlotEdit.AddWord(s0, 0, 2))
        assertEquals("s0{0:2-3,1:1-2,2:0-1}", b.shape()[0])
        assertValid(b)
    }

    @Test
    fun `adding a word on a new line extends the slot`() {
        val b = board(listOf(slot("s0", span(0, 3), span(1, 1)))).applied(SlotEdit.AddWord(s0, 2, 0))
        assertEquals(listOf("s0{0:3-4,1:1-2,2:0-1}"), b.shape())
        assertEquals(3, b.slots[0].coverage)
        assertTrue(b.isFull(b.slots[0]))
    }

    @Test
    fun `adding a word already in another slot moves it`() {
        // "on" (line 0 word 7) belongs to s2; move it into s1, whose line-0 member it replaces.
        val b = start.applied(SlotEdit.AddWord(s1, 0, 7))
        assertEquals(listOf("s0{0:3-4,1:1-2,2:0-1}", "s1{0:7-8,1:4-5,2:4-5}", "s2{1:5-6,2:5-6}"), b.shape())
        assertValid(b)
    }

    @Test
    fun `moving a slot's only member away drops that slot`() {
        val b = board(listOf(slot("s0", span(0, 3), span(1, 1)), slot("s1", span(2, 4)))).applied(SlotEdit.AddWord(s0, 2, 4))
        assertEquals(listOf("s0{0:3-4,1:1-2,2:4-5}"), b.shape())
    }

    @Test
    fun `adding the word that is already the member changes nothing`() {
        assertEquals(SlotEditResult.Unchanged, applySlotEdit(start, SlotEdit.AddWord(s0, 0, 3)))
    }

    @Test
    fun `an add that would cross slot order is refused`() {
        // s2's line-0 member "on" (7) is after s1's "pressure" (6); moving s0 to word 7 would put it after s1.
        val r = applySlotEdit(board(listOf(slot("s0", span(0, 3)), slot("s1", span(0, 6)))), SlotEdit.AddWord(s0, 0, 7))
        assertEquals(SlotEditResult.Refused(SlotEditRefusal.CrossesOrder(0, s0, s1)), r)
    }

    @Test
    fun `adding to a missing slot or a missing word is refused`() {
        assertEquals(SlotEditResult.Refused(SlotEditRefusal.NoSuchSlot(SlotId("zz"))), applySlotEdit(start, SlotEdit.AddWord(SlotId("zz"), 0, 1)))
        assertEquals(SlotEditResult.Refused(SlotEditRefusal.NoSuchWord(0, 8)), applySlotEdit(start, SlotEdit.AddWord(s0, 0, 8)))
        assertEquals(SlotEditResult.Refused(SlotEditRefusal.NoSuchWord(7, 0)), applySlotEdit(start, SlotEdit.AddWord(s0, 7, 0)))
    }

    @Test
    fun `remove takes the member out and drops a slot left empty`() {
        val b = start.applied(SlotEdit.RemoveWord(1, 4))
        assertEquals("s1{0:6-7,2:4-5}", b.shape()[1])
        val single = board(listOf(slot("s0", span(0, 3)), slot("s1", span(0, 6), span(1, 4))))
        assertEquals(listOf("s1{0:6-7,1:4-5}"), single.applied(SlotEdit.RemoveWord(0, 3)).shape())
    }

    @Test
    fun `remove works on any word of a two-word member`() {
        val b = board(listOf(slot("s0", span(1, 3, 5), span(0, 6)))).applied(SlotEdit.RemoveWord(1, 3))
        assertEquals(listOf("s0{0:6-7}"), b.shape())
    }

    @Test
    fun `removing a word in no slot is refused`() {
        assertEquals(SlotEditResult.Refused(SlotEditRefusal.NotInSlot), applySlotEdit(start, SlotEdit.RemoveWord(0, 0)))
    }

    @Test
    fun `new slots get fresh u ids that are never reused`() {
        val a = start.applied(SlotEdit.NewSlot(0, 1))
        assertEquals(SlotId("u0"), a.slots.first { it.id.value.startsWith("u") }.id)
        val b = a.applied(SlotEdit.Drop(SlotId("u0"))).applied(SlotEdit.NewSlot(1, 2))
        assertEquals(listOf("u1"), b.slots.filter { it.id.value.startsWith("u") }.map { it.id.value })
        assertTrue(b.slot(SlotId("u1"))!!.on)
        assertValid(b)
    }

    @Test
    fun `a new slot is placed in word order on its line`() {
        // Line 0: steady(3) s0, when(4) new, pressure(6) s1, on(7) s2.
        val b = start.applied(SlotEdit.NewSlot(0, 4))
        assertEquals(listOf("s0", "u0", "s1", "s2"), b.slots.map { it.id.value })
        // Line 0 word 1 ("keep") comes before every member.
        assertEquals(listOf("u0", "s0", "s1", "s2"), start.applied(SlotEdit.NewSlot(0, 1)).slots.map { it.id.value })
        assertValid(b)
    }

    @Test
    fun `among slots without a member on the line a new slot goes by relative position`() {
        // s0 lives on line 1 only (early), s1 on line 2 only (late); a new slot on line 0 at word 4 of 8 sits between.
        val b = board(listOf(slot("s0", span(1, 0)), slot("s1", span(2, 5)))).applied(SlotEdit.NewSlot(0, 4))
        assertEquals(listOf("s0", "u0", "s1"), b.slots.map { it.id.value })
    }

    @Test
    fun `a new slot from a word in a slot moves the word`() {
        // "long" leaves s2; with no line-2 member left, s2 (mean position 0.85) sorts after the new slot (5/6).
        val b = start.applied(SlotEdit.NewSlot(2, 5))
        assertEquals(listOf("s0{0:3-4,1:1-2,2:0-1}", "s1{0:6-7,1:4-5,2:4-5}", "u0{2:5-6}", "s2{0:7-8,1:5-6}"), b.shape())
        assertValid(b)
    }

    @Test
    fun `at most eight slots`() {
        val eight = board((0 until 8).map { slot("s$it", span(0, it)) })
        assertEquals(8, eight.slots.size)
        assertEquals(SlotEditResult.Refused(SlotEditRefusal.TooManySlots), applySlotEdit(eight, SlotEdit.NewSlot(1, 0)))
        // Moving a slot's only word into a new slot keeps the count.
        assertEquals(8, eight.applied(SlotEdit.NewSlot(0, 2)).slots.size)
    }

    @Test
    fun `drop and toggle`() {
        val dropped = start.applied(SlotEdit.Drop(s1))
        assertEquals(listOf("s0", "s2"), dropped.slots.map { it.id.value })
        val off = start.applied(SlotEdit.Toggle(s2))
        assertEquals(listOf("s0", "s1"), off.onSlots.map { it.id.value })
        assertEquals(start, off.applied(SlotEdit.Toggle(s2)))
        assertEquals(SlotEditResult.Refused(SlotEditRefusal.NoSuchSlot(SlotId("x"))), applySlotEdit(start, SlotEdit.Drop(SlotId("x"))))
        assertEquals(SlotEditResult.Refused(SlotEditRefusal.NoSuchSlot(SlotId("x"))), applySlotEdit(start, SlotEdit.Toggle(SlotId("x"))))
    }

    @Test
    fun `specs send the on slots in order with members in line order and no k`() {
        val b = board(listOf(slot("s0", span(2, 0), span(0, 3)), slot("s1", span(0, 6), on = false), slot("s2", span(1, 5))))
        assertEquals(
            listOf(
                SlotSpec("s0", listOf(SpanRef(0, 3, 4), SpanRef(2, 0, 1)), null),
                SlotSpec("s2", listOf(SpanRef(1, 5, 6)), null),
            ),
            b.specs(),
        )
    }

    @Test
    fun `random edit sequences keep the invariants`() {
        val random = java.util.Random(7)
        var b = start
        repeat(500) {
            val line = random.nextInt(3)
            val word = random.nextInt(b.line(line)!!.wordCount)
            val edit = when (random.nextInt(5)) {
                0 -> SlotEdit.NewSlot(line, word)
                1 -> SlotEdit.RemoveWord(line, word)
                2 -> if (b.slots.isEmpty()) SlotEdit.NewSlot(line, word) else SlotEdit.Drop(b.slots[random.nextInt(b.slots.size)].id)
                3 -> if (b.slots.isEmpty()) SlotEdit.NewSlot(line, word) else SlotEdit.Toggle(b.slots[random.nextInt(b.slots.size)].id)
                else -> if (b.slots.isEmpty()) SlotEdit.NewSlot(line, word) else SlotEdit.AddWord(b.slots[random.nextInt(b.slots.size)].id, line, word)
            }
            val r = applySlotEdit(b, edit)
            if (r is SlotEditResult.Applied) b = r.board
            assertValid(b)
            assertTrue(b.slots.size <= 8)
        }
    }

    @Test
    fun `letters go by position`() {
        assertEquals(listOf("A", "B", "H"), listOf(0, 1, 7).map { slotLetter(it) })
    }
}
