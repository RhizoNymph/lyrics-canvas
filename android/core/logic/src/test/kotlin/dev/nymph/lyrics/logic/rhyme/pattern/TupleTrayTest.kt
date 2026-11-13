package dev.nymph.lyrics.logic.rhyme.pattern

import dev.nymph.lyrics.logic.canvas.Point
import dev.nymph.lyrics.model.SnippetCreateRequest
import dev.nymph.lyrics.model.SnippetType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TupleTrayTest {
    private val a = SlotId("s0")
    private val b = SlotId("s1")
    private val c = SlotId("s2")
    private val order = listOf(a, b, c)

    @Test
    fun `tapping a row picks it, tapping again unpicks, another row replaces`() {
        val one = TupleTray().toggle(a, "dict:steady", "steady")
        assertTrue(one.isPicked(a, "dict:steady"))
        val replaced = one.toggle(a, "dict:ready", "ready")
        assertEquals(mapOf(a to TuplePick("dict:ready", "ready")), replaced.picks)
        assertTrue(replaced.toggle(a, "dict:ready", "ready").isEmpty)
    }

    @Test
    fun `the tray shows picks in slot order with gaps`() {
        val tray = TupleTray().toggle(c, "x", "strong").toggle(a, "y", "steady")
        assertEquals("steady · ___ · strong", tray.display(order))
        assertEquals(listOf(TrayCell.Picked(a, TuplePick("y", "steady")), TrayCell.Gap(b), TrayCell.Picked(c, TuplePick("x", "strong"))), tray.cells(order))
        assertEquals("___ · ___ · ___", TupleTray().display(order))
    }

    @Test
    fun `make snippet text joins the picks in slot order by spaces`() {
        val tray = TupleTray().toggle(c, "x", "strong").toggle(a, "y", "steady").toggle(b, "z", "the weather")
        assertEquals("steady the weather strong", tray.snippetText(order))
        assertEquals("steady strong", tray.toggle(b, "z", "the weather").snippetText(order))
        assertNull(TupleTray().snippetText(order))
    }

    @Test
    fun `make snippet request has the text and no derived_from`() {
        val tray = TupleTray().toggle(a, "y", "steady").toggle(b, "z", "ready")
        assertEquals(
            SnippetCreateRequest(positionX = 10.0, positionY = 20.0, snippetType = SnippetType.FREESTYLE, rawText = "steady ready"),
            makeSnippetRequest(tray, order, Point(10.0, 20.0)),
        )
        assertNull(makeSnippetRequest(TupleTray(), order, Point(0.0, 0.0)))
    }

    @Test
    fun `bank words keep valid picks once each`() {
        val tray = TupleTray().toggle(a, "1", "Steady").toggle(b, "2", "it's 100% heavy!").toggle(c, "3", "steady")
        assertEquals(listOf("Steady"), tray.bankWords(order))
    }

    @Test
    fun `pruning drops picks of slots that are gone or off`() {
        val tray = TupleTray().toggle(a, "1", "x").toggle(b, "2", "y")
        assertEquals(TupleTray(mapOf(a to TuplePick("1", "x"))), tray.prune(listOf(a, c)))
        assertSame(tray, tray.prune(order))
        assertFalse(tray.prune(emptyList()).picks.containsKey(a))
    }
}
