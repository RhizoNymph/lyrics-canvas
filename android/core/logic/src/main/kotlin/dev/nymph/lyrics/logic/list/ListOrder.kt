package dev.nymph.lyrics.logic.list

import dev.nymph.lyrics.model.Snippet
import dev.nymph.lyrics.model.SnippetId
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeParseException

/**
 * A server timestamp as an [Instant], or null when it cannot be read.
 *
 * Accepts ISO-8601 (`2026-09-01T10:00:00Z`, `...+02:00`) and the backend's `str(datetime)` form
 * (`2026-09-01 10:00:00.250000+00:00`, fraction omitted when zero). A time without an offset, or a
 * bare date (midnight), is taken as UTC.
 */
fun parseServerTimestamp(text: String): Instant? {
    val iso = if (text.length > 10 && text[10] == ' ') text.replaceRange(10, 11, "T") else text
    return parseOrNull { OffsetDateTime.parse(iso).toInstant() }
        ?: parseOrNull { LocalDateTime.parse(iso).toInstant(ZoneOffset.UTC) }
        ?: parseOrNull { LocalDate.parse(iso).atStartOfDay().toInstant(ZoneOffset.UTC) }
}

private inline fun parseOrNull(parse: () -> Instant): Instant? = try {
    parse()
} catch (_: DateTimeParseException) {
    null
}

/** A snippet with its parsed sort keys (each timestamp is parsed once per sort). */
private class ListKey(val snippet: Snippet) {
    val content: Instant? = parseServerTimestamp(snippet.contentUpdatedAt)
    val created: Instant? = parseServerTimestamp(snippet.createdAt)
}

/** Newest first; an unreadable timestamp sorts after every readable one. */
private val newestFirst: Comparator<Instant?> = nullsLast(reverseOrder())

private val listKeyOrder: Comparator<ListKey> =
    compareBy(newestFirst) { k: ListKey -> k.content }
        .thenBy(newestFirst) { it.created }
        .thenBy { it.snippet.id.value }

/**
 * List-mode order: last content edit (`content_updated_at`) first, then newest `created_at`, then
 * id ascending, so equal timestamps still give one fixed order.
 */
fun sortForList(snippets: List<Snippet>): List<Snippet> =
    snippets.map(::ListKey).sortedWith(listKeyOrder).map { it.snippet }

/**
 * A list-mode order captured at one moment (when the list is shown, or after an edit), so rows do
 * not jump while the user is looking at them. Only built by [of].
 */
@ConsistentCopyVisibility
data class ListOrder private constructor(val ids: List<SnippetId>) {
    /**
     * The current [snippets] in this order: snippets this order has not seen (merged, created,
     * materialized since) come first, sorted by [sortForList]; then the captured ids still present.
     */
    fun arrange(snippets: List<Snippet>): List<Snippet> {
        val byId = snippets.associateBy { it.id }
        val known = ids.toHashSet()
        val unseen = sortForList(snippets.filter { it.id !in known })
        return unseen + ids.mapNotNull(byId::get)
    }

    companion object {
        fun of(snippets: List<Snippet>): ListOrder = ListOrder(sortForList(snippets).map { it.id })
    }
}
