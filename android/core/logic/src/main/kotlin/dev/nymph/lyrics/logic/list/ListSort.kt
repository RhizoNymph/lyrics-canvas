package dev.nymph.lyrics.logic.list

import dev.nymph.lyrics.logic.rests.displayText
import dev.nymph.lyrics.model.Snippet
import java.text.CollationKey
import java.text.Collator
import java.time.Instant
import java.util.Locale

/** How list mode orders a canvas's snippets; remembered per device ([wire] is the stored form). */
enum class ListSort(val wire: String) {
    /** `content_updated_at` desc, `created_at` desc, id asc (the original list order). */
    LAST_EDITED("last_edited"),

    /** Snippet `created_at` desc, id asc. */
    CREATED("created"),

    /** Display text (rest marks removed), case- and accent-insensitive collation, then id asc. */
    A_Z("az"),

    /** The canvas's saved order: `list_rank` asc (character order), then `placement_id` asc. */
    CUSTOM("custom"),
    ;

    companion object {
        /** The default for a device that never chose one. */
        val DEFAULT: ListSort = LAST_EDITED

        /** A stored value, or [DEFAULT] for anything unknown. */
        fun parse(saved: String?): ListSort = entries.firstOrNull { it.wire == saved } ?: DEFAULT
    }
}

/**
 * The A-Z collator: the default locale at base strength (`localeCompare` with
 * `sensitivity: "base"`), so case and accents do not separate words.
 */
fun listCollator(locale: Locale = Locale.getDefault()): Collator =
    Collator.getInstance(locale).apply { strength = Collator.PRIMARY }

/** Newest first; an unreadable timestamp sorts after every readable one. */
private val newestFirst: Comparator<Instant?> = nullsLast(reverseOrder())

/** A snippet with its sort keys computed once per sort. */
private class Keyed(val snippet: Snippet, val content: Instant?, val created: Instant?, val text: CollationKey?)

private fun keyed(snippets: List<Snippet>, sort: ListSort, collator: Collator): List<Keyed> = snippets.map { s ->
    Keyed(
        snippet = s,
        content = if (sort == ListSort.LAST_EDITED) parseServerTimestamp(s.contentUpdatedAt) else null,
        created = if (sort == ListSort.LAST_EDITED || sort == ListSort.CREATED) parseServerTimestamp(s.createdAt) else null,
        text = if (sort == ListSort.A_Z) collator.getCollationKey(displayText(s.rawText)) else null,
    )
}

private fun comparatorFor(sort: ListSort): Comparator<Keyed> = when (sort) {
    ListSort.LAST_EDITED -> compareBy(newestFirst) { k: Keyed -> k.content }
        .thenBy(newestFirst) { it.created }
        .thenBy { it.snippet.id.value }
    ListSort.CREATED -> compareBy(newestFirst) { k: Keyed -> k.created }
        .thenBy { it.snippet.id.value }
    ListSort.A_Z -> Comparator<Keyed> { a, b -> checkNotNull(a.text).compareTo(checkNotNull(b.text)) }
        .thenBy { it.snippet.id.value }
    ListSort.CUSTOM -> compareBy<Keyed> { it.snippet.listRank }
        .thenBy { it.snippet.placementId.value }
}

/**
 * [snippets] in list-mode order for [sort]. Every order is total: ties fall back to the id
 * (Custom: the placement id), so equal keys still give one fixed order.
 */
fun sortForList(snippets: List<Snippet>, sort: ListSort, collator: Collator = listCollator()): List<Snippet> =
    keyed(snippets, sort, collator).sortedWith(comparatorFor(sort)).map { it.snippet }
