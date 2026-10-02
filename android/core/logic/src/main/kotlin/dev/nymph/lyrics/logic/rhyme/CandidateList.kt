package dev.nymph.lyrics.logic.rhyme

import dev.nymph.lyrics.model.RhymeCandidate
import dev.nymph.lyrics.model.RhymeLimits

/** Loaded candidates minus dismissed ids, limited to the pages loaded so far. */
fun listRows(candidates: List<RhymeCandidate>, offset: Int, dismissed: Set<String>): List<RhymeCandidate> =
    candidates.filter { it.id !in dismissed }.take((offset + RhymeLimits.PAGE_SIZE).coerceAtLeast(0))

/** 1-based "Showing start–end" bounds. */
data class ShownRange(val start: Int, val end: Int)

/** 0–0 when the list is empty, else 1 to the row count. */
@Suppress("UNUSED_PARAMETER")
fun shownRange(rowCount: Int, total: Int): ShownRange = if (rowCount == 0) ShownRange(0, 0) else ShownRange(1, rowCount)

fun canLoadMore(loading: Boolean, offset: Int, total: Int): Boolean = !loading && offset + RhymeLimits.PAGE_SIZE < total
