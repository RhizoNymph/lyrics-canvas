package dev.nymph.lyrics.logic.list

import dev.nymph.lyrics.model.Snippet
import dev.nymph.lyrics.model.SnippetId

/** What dropping a dragged list row changes on the canvas's custom order. */
sealed interface ReorderPlan {
    /** One placement takes a key between its new visible neighbours (one layout PUT). */
    data class Move(val id: SnippetId, val rank: ListRank) : ReorderPlan

    /**
     * Every placement on the canvas takes a fresh key (`keysBetween(null, null, n)`), top to
     * bottom: the shown order, with the moved row at its drop point, becomes the custom order
     * (one batch-position call). Used outside Custom, and in Custom when the neighbours leave no
     * key between them (equal keys from two devices, a key at the length limit).
     */
    data class Rerank(val ranks: List<Pair<SnippetId, ListRank>>) : ReorderPlan
}

/**
 * The plan for dropping [moved] so it is the [toIndex]-th of the shown rows once it has been
 * taken out of them (0 = top). Null when nothing changes (dropped where it was, or not shown).
 *
 * @param order the list's captured order; its sort decides between [ReorderPlan.Move] (Custom)
 *   and [ReorderPlan.Rerank] (any other sort adopts the shown order)
 * @param all every snippet on the canvas, including those the filter or search hides
 * @param shown the rows on screen, in their order (`order.arrange(visible)`)
 */
fun planReorder(order: ListOrder, all: List<Snippet>, shown: List<Snippet>, moved: SnippetId, toIndex: Int): ReorderPlan? {
    val from = shown.indexOfFirst { it.id == moved }
    if (from < 0) return null
    val rest = shown.filter { it.id != moved }
    val at = toIndex.coerceIn(0, rest.size)
    if (at == from) return null
    val above = rest.getOrNull(at - 1)
    val below = rest.getOrNull(at)
    if (order.sort == ListSort.CUSTOM) {
        moveBetween(moved, above, below)?.let { return it }
    }
    return rerank(order, all, moved, above, below)
}

private fun moveBetween(moved: SnippetId, above: Snippet?, below: Snippet?): ReorderPlan.Move? {
    val a = above?.let { ListRank.parse(it.listRank) ?: return null }
    val b = below?.let { ListRank.parse(it.listRank) ?: return null }
    return keyBetween(a, b).getOrNull()?.let { ReorderPlan.Move(moved, it) }
}

private fun rerank(order: ListOrder, all: List<Snippet>, moved: SnippetId, above: Snippet?, below: Snippet?): ReorderPlan.Rerank? {
    val movedSnippet = all.firstOrNull { it.id == moved } ?: return null
    val full = order.arrange(all).filter { it.id != moved }.toMutableList()
    val at = when {
        above != null -> full.indexOfFirst { it.id == above.id } + 1
        below != null -> full.indexOfFirst { it.id == below.id }.coerceAtLeast(0)
        else -> 0
    }
    full.add(at, movedSnippet)
    // From scratch the keys are a0, a1, ... and grow one digit per 62^k: they always fit.
    val keys = checkNotNull(keysBetween(null, null, full.size).getOrNull()) { "fresh keys for ${full.size} placements" }
    return ReorderPlan.Rerank(full.map { it.id }.zip(keys))
}
