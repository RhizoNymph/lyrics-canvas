package dev.nymph.lyrics.logic.placements

import dev.nymph.lyrics.model.Canvas
import dev.nymph.lyrics.model.CanvasId

/**
 * The transfer picker's rows: the user's canvases other than [current] whose title contains [query]
 * (trimmed, ignoring case), newest `updated_at` first, ties by id. The server's timestamps share
 * one format and offset, so they compare as strings.
 */
fun pickerCanvases(all: List<Canvas>, current: CanvasId, query: String): List<Canvas> {
    val needle = query.trim().lowercase()
    return all
        .filter { it.id != current && (needle.isEmpty() || needle in it.title.lowercase()) }
        .sortedWith(compareByDescending<Canvas> { it.updatedAt }.thenBy { it.id.value })
}
