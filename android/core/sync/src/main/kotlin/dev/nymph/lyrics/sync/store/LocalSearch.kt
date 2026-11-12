package dev.nymph.lyrics.sync.store

import dev.nymph.lyrics.logic.list.parseServerTimestamp
import dev.nymph.lyrics.logic.placements.sortCanvasRefs
import dev.nymph.lyrics.logic.rests.displayText
import dev.nymph.lyrics.logic.search.SearchQuery
import dev.nymph.lyrics.model.CanvasRef
import dev.nymph.lyrics.model.CanvasSearchHit
import dev.nymph.lyrics.model.SearchLimits
import dev.nymph.lyrics.model.SearchResponse
import dev.nymph.lyrics.model.SnippetSearchHit
import java.text.Normalizer
import java.time.Instant
import java.util.Locale

/** The longest excerpt, in code points (the server's `EXCERPT_MAX`). */
const val EXCERPT_MAX: Int = 160

/** Code points kept before the match when a long line is cut (the server's `LEAD`). */
const val EXCERPT_LEAD: Int = 40

private const val ELLIPSIS = "…"

/**
 * `GET /api/search` on the local copy (docs/features/global_search.md): the same matching rule
 * ([SearchQuery]), orders, limits and excerpts as the server, so search works offline.
 */
object LocalSearch {
    fun search(d: LocalData, raw: String, limit: Int = SearchLimits.DEFAULT): SearchResponse {
        val query = SearchQuery.parse(raw)
        if (query.isEmpty) return SearchResponse()
        val max = limit.coerceIn(1, SearchLimits.MAX)
        val canvases = d.canvases
            .filter { c -> fold(c.title).let { title -> query.terms.all { title.contains(it) } } }
            .sortedByDescending { instant(it.updatedAt) }
            .take(max)
            .map { CanvasSearchHit(it.id, it.title, it.updatedAt) }
        val titles = d.canvases.associate { it.id to it.title }
        val refs = d.placements.groupBy { it.snippetId }
        val snippets = d.snippets
            .filter { query.matches(it.rawText) }
            .sortedByDescending { instant(it.contentUpdatedAt) }
            .take(max)
            .map { s ->
                val on = refs[s.id].orEmpty().mapNotNull { p -> titles[p.canvasId]?.let { CanvasRef(p.canvasId, it) } }
                SnippetSearchHit(s.id, s.snippetType, s.contentUpdatedAt, excerpt(s.rawText, query.terms), sortCanvasRefs(on))
            }
        return SearchResponse(canvases, snippets)
    }

    /** The first display line containing the first term (else the first non-empty one), windowed. */
    fun excerpt(rawText: String, terms: List<String>): String {
        val lines = displayText(rawText).split("\n").map { Normalizer.normalize(it, Normalizer.Form.NFC) }
        val first = terms.firstOrNull().orEmpty()
        if (first.isNotEmpty()) {
            for (line in lines) {
                val at = line.lowercase(Locale.ROOT).indexOf(first)
                if (at >= 0) return window(line, line.codePointCount(0, minOf(at, line.length)))
            }
        }
        return lines.firstOrNull { it.isNotEmpty() }?.let { window(it, 0) } ?: ""
    }

    /** The server's `_window`, in code points. */
    private fun window(line: String, at: Int): String {
        val cps = line.codePoints().toArray()
        fun slice(from: Int, to: Int) = String(cps, from, to - from)
        if (cps.size <= EXCERPT_MAX) return line
        val start = maxOf(0, at - EXCERPT_LEAD)
        if (start == 0) return slice(0, EXCERPT_MAX - 1) + ELLIPSIS
        val body = EXCERPT_MAX - 2
        if (start + body + 1 >= cps.size) return ELLIPSIS + slice(cps.size - (EXCERPT_MAX - 1), cps.size)
        return ELLIPSIS + slice(start, start + body) + ELLIPSIS
    }

    private fun fold(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFC).lowercase(Locale.ROOT)

    private fun instant(text: String): Instant = parseServerTimestamp(text) ?: Instant.EPOCH
}
