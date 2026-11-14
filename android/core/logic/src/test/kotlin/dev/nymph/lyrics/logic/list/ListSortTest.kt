package dev.nymph.lyrics.logic.list

import dev.nymph.lyrics.logic.snippet
import dev.nymph.lyrics.model.PlacementId
import dev.nymph.lyrics.model.Snippet
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.Locale

class ListSortTest {
    private val collator = listCollator(Locale.ENGLISH)

    private fun ids(snippets: List<Snippet>) = snippets.map { it.id.value }

    private fun sorted(sort: ListSort, vararg s: Snippet) = ids(sortForList(s.toList(), sort, collator))

    // ---- parse ----

    @Test
    fun `stored values round-trip and anything else is the default`() {
        ListSort.entries.forEach { assertEquals(it, ListSort.parse(it.wire)) }
        assertEquals(ListSort.LAST_EDITED, ListSort.parse(null))
        assertEquals(ListSort.LAST_EDITED, ListSort.parse("CUSTOM"))
        assertEquals(ListSort.LAST_EDITED, ListSort.DEFAULT)
    }

    @Test
    fun `wire names are stable`() {
        assertEquals(listOf("last_edited", "created", "az", "custom"), ListSort.entries.map { it.wire })
    }

    // ---- Last edited ----

    @Test
    fun `last edited is the original list order`() {
        val a = snippet("a", contentUpdatedAt = "2026-09-01T10:00:00Z", createdAt = "2026-08-01T00:00:00Z")
        val b = snippet("b", contentUpdatedAt = "2026-09-03T10:00:00Z", createdAt = "2026-08-01T00:00:00Z")
        val c = snippet("c", contentUpdatedAt = "2026-09-01T10:00:00Z", createdAt = "2026-08-05T00:00:00Z")
        assertEquals(listOf("b", "c", "a"), sorted(ListSort.LAST_EDITED, a, b, c))
        assertEquals(ids(sortForList(listOf(a, b, c))), sorted(ListSort.LAST_EDITED, a, b, c))
    }

    // ---- Created ----

    @Test
    fun `created is newest first, ignoring edits`() {
        val old = snippet("old", createdAt = "2026-01-01T00:00:00Z", contentUpdatedAt = "2026-09-09T00:00:00Z")
        val new = snippet("new", createdAt = "2026-05-01T00:00:00Z", contentUpdatedAt = "2026-05-01T00:00:00Z")
        assertEquals(listOf("new", "old"), sorted(ListSort.CREATED, old, new))
    }

    @Test
    fun `created ties by id and unreadable times go last`() {
        val t = "2026-05-01 10:00:00+00:00"
        val c = snippet("c", createdAt = t)
        val a = snippet("a", createdAt = t)
        val bad = snippet("0", createdAt = "garbage")
        assertEquals(listOf("a", "c", "0"), sorted(ListSort.CREATED, bad, c, a))
    }

    // ---- A-Z ----

    @Test
    fun `a-z ignores case and accents`() {
        val b = snippet("1", rawText = "banana")
        val a = snippet("2", rawText = "Apple")
        val e = snippet("3", rawText = "éclair")
        val z = snippet("4", rawText = "zebra")
        assertEquals(listOf("2", "1", "3", "4"), sorted(ListSort.A_Z, z, e, b, a))
    }

    @Test
    fun `a-z reads the display text, without rest marks`() {
        // "_zoo" displays as "zoo"; as raw text "_" would sort it first.
        val rest = snippet("r", rawText = "_ zoo")
        val m = snippet("m", rawText = "moon")
        assertEquals(listOf("m", "r"), sorted(ListSort.A_Z, rest, m))
    }

    @Test
    fun `a-z ties fall back to id`() {
        val x = snippet("x", rawText = "Same")
        val y = snippet("y", rawText = "same")
        assertEquals(listOf("x", "y"), sorted(ListSort.A_Z, y, x))
        assertEquals(listOf("x", "y"), sorted(ListSort.A_Z, x, y))
    }

    // ---- Custom ----

    private fun ranked(id: String, rank: String, placement: String = "p-$id") =
        snippet(id).copy(listRank = rank, placementId = PlacementId(placement))

    @Test
    fun `custom is list rank ascending in character order`() {
        val a = ranked("a", "a1")
        val b = ranked("b", "Zz")
        val c = ranked("c", "a0V")
        val d = ranked("d", "a0")
        assertEquals(listOf("b", "d", "c", "a"), sorted(ListSort.CUSTOM, a, b, c, d))
    }

    @Test
    fun `custom ties (two devices chose one key) fall back to placement id`() {
        val a = ranked("a", "a0", placement = "p2")
        val b = ranked("b", "a0", placement = "p1")
        assertEquals(listOf("b", "a"), sorted(ListSort.CUSTOM, a, b))
    }

    @Test
    fun `custom never uses the locale for ranks`() {
        // Locale collation would put "a1" before "B0zz..." (case-insensitive); character order does not.
        val lower = ranked("l", "a1")
        val upper = ranked("u", "Y00")
        assertEquals(listOf("u", "l"), sorted(ListSort.CUSTOM, lower, upper))
    }

    // ---- ListOrder with a sort ----

    @Test
    fun `an order remembers its sort and sorts unseen snippets by it`() {
        val a = ranked("a", "a1")
        val b = ranked("b", "a2")
        val order = ListOrder.of(listOf(b, a), ListSort.CUSTOM)
        assertEquals(ListSort.CUSTOM, order.sort)
        assertEquals(listOf("a", "b"), ids(order.arrange(listOf(a, b))))
        val n1 = ranked("n1", "Zx")
        val n2 = ranked("n2", "Zw")
        assertEquals(listOf("n2", "n1", "a", "b"), ids(order.arrange(listOf(a, n1, b, n2))))
    }

    @Test
    fun `the default order is last edited`() {
        assertEquals(ListSort.LAST_EDITED, ListOrder.of(emptyList()).sort)
    }
}
