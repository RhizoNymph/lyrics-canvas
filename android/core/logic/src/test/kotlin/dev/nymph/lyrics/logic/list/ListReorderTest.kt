package dev.nymph.lyrics.logic.list

import dev.nymph.lyrics.logic.snippet
import dev.nymph.lyrics.model.Snippet
import dev.nymph.lyrics.model.SnippetId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ListReorderTest {
    private fun r(text: String) = checkNotNull(ListRank.parse(text))

    private fun ranked(id: String, rank: String, edited: String = "2026-09-01T00:00:00Z") =
        snippet(id, contentUpdatedAt = edited).copy(listRank = rank)

    private val a = ranked("a", "a0", edited = "2026-09-04T00:00:00Z")
    private val b = ranked("b", "a1", edited = "2026-09-03T00:00:00Z")
    private val c = ranked("c", "a2", edited = "2026-09-02T00:00:00Z")
    private val d = ranked("d", "a3", edited = "2026-09-01T00:00:00Z")
    private val all = listOf(a, b, c, d)

    private fun custom(snippets: List<Snippet> = all) = ListOrder.of(snippets, ListSort.CUSTOM)

    private fun ids(plan: ReorderPlan.Rerank) = plan.ranks.map { it.first.value }

    // ---- Custom: one key between the visible neighbours ----

    @Test
    fun `custom drop between two rows takes a key between them`() {
        // a b c d: drag d between a and b.
        val plan = planReorder(custom(), all, all, d.id, 1)
        assertEquals(ReorderPlan.Move(d.id, r("a0V")), plan)
    }

    @Test
    fun `custom drop on top or at the bottom opens one end`() {
        assertEquals(ReorderPlan.Move(c.id, r("Zz")), planReorder(custom(), all, all, c.id, 0))
        assertEquals(ReorderPlan.Move(a.id, r("a4")), planReorder(custom(), all, all, a.id, 3))
    }

    @Test
    fun `custom drop ignores hidden rows`() {
        // b is filtered out: shown a c d; drop d between a and c -> key between a0 and a2.
        val shown = listOf(a, c, d)
        val plan = planReorder(custom(), all, shown, d.id, 1) as ReorderPlan.Move
        assertEquals(d.id, plan.id)
        assertTrue(plan.rank > r("a0") && plan.rank < r("a2"))
    }

    @Test
    fun `custom drop between equal keys re-ranks everything in the shown order`() {
        val x = ranked("x", "a1")
        val y = ranked("y", "a1")
        val z = ranked("z", "a2")
        val snippets = listOf(x, y, z)
        val plan = planReorder(custom(snippets), snippets, custom(snippets).arrange(snippets), z.id, 1)
        plan as ReorderPlan.Rerank
        assertEquals(listOf("x", "z", "y"), ids(plan))
        assertEquals(listOf("a0", "a1", "a2"), plan.ranks.map { it.second.value })
    }

    @Test
    fun `custom drop next to an invalid stored key re-ranks`() {
        val bad = ranked("bad", "a00")
        val snippets = listOf(a, bad, c)
        val order = custom(snippets)
        val shown = order.arrange(snippets)
        val plan = planReorder(order, snippets, shown, c.id, shown.indexOfFirst { it.id == bad.id })
        assertTrue(plan is ReorderPlan.Rerank)
    }

    // ---- Another sort: adopt it as the custom order ----

    @Test
    fun `a drop in another sort re-ranks every placement in that sort with the row moved`() {
        // Last edited: a b c d (a newest). Drag a below c.
        val order = ListOrder.of(all, ListSort.LAST_EDITED)
        val plan = planReorder(order, all, order.arrange(all), a.id, 2) as ReorderPlan.Rerank
        assertEquals(listOf("b", "c", "a", "d"), ids(plan))
        assertEquals(listOf("a0", "a1", "a2", "a3"), plan.ranks.map { it.second.value })
    }

    @Test
    fun `hidden placements keep their place in the adopted order`() {
        val order = ListOrder.of(all, ListSort.LAST_EDITED)
        // b and c hidden: shown a d; drop d on top (above a).
        val plan = planReorder(order, all, listOf(a, d), d.id, 0) as ReorderPlan.Rerank
        assertEquals(listOf("d", "a", "b", "c"), ids(plan))
    }

    @Test
    fun `the moved row goes right after its visible upper neighbour`() {
        val order = ListOrder.of(all, ListSort.LAST_EDITED)
        // c hidden: shown a b d; drop a between b and d -> after b, so before the hidden c.
        val plan = planReorder(order, all, listOf(a, b, d), a.id, 1) as ReorderPlan.Rerank
        assertEquals(listOf("b", "a", "c", "d"), ids(plan))
    }

    @Test
    fun `the adopted order is the captured one, not a fresh sort`() {
        val order = ListOrder.of(all, ListSort.LAST_EDITED)
        // d was edited after the capture: it stays where it was shown.
        val dEdited = d.copy(contentUpdatedAt = "2026-10-01T00:00:00Z")
        val now = listOf(a, b, c, dEdited)
        val plan = planReorder(order, now, order.arrange(now), b.id, 0) as ReorderPlan.Rerank
        assertEquals(listOf("b", "a", "c", "d"), ids(plan))
    }

    // ---- No-ops ----

    @Test
    fun `dropping where it was changes nothing`() {
        assertNull(planReorder(custom(), all, all, b.id, 1))
        assertNull(planReorder(ListOrder.of(all), all, all, a.id, 0))
    }

    @Test
    fun `a row that is not shown cannot be dropped`() {
        assertNull(planReorder(custom(), all, listOf(a, b), SnippetId("c"), 0))
    }

    @Test
    fun `the target index is clamped`() {
        assertEquals(ReorderPlan.Move(a.id, r("a4")), planReorder(custom(), all, all, a.id, 99))
        assertEquals(ReorderPlan.Move(d.id, r("Zz")), planReorder(custom(), all, all, d.id, -5))
    }

    // ---- topRank ----

    @Test
    fun `top rank is above the smallest valid key`() {
        assertEquals(r("a0"), topRank(emptyList()))
        assertEquals(r("Zz"), topRank(listOf("a2", "a0", "a1")))
        assertEquals(r("Zz"), topRank(listOf("a0", "not a key", "a00")))
        assertEquals(listOf("Zx", "Zy", "Zz"), topRanks(listOf("a0", "a5"), 3)?.map { it.value })
    }
}
