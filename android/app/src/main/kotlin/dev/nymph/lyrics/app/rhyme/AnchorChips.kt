package dev.nymph.lyrics.app.rhyme

import dev.nymph.lyrics.logic.rhyme.anchorSnippets
import dev.nymph.lyrics.logic.rhyme.lastNonEmptyLineIndex
import dev.nymph.lyrics.logic.rhyme.rangeText
import dev.nymph.lyrics.model.AnchorRange
import dev.nymph.lyrics.model.GridLine
import dev.nymph.lyrics.model.RhymeUnit
import dev.nymph.lyrics.model.Snippet
import dev.nymph.lyrics.model.SnippetId

const val CHIP_TEXT_MAX: Int = 28

/** One selected, non-blank snippet as shown in the sheet's anchor chips (AnchorChips.tsx). */
data class AnchorChip(
    val snippetId: SnippetId,
    /** The picked word range's text, else the last non-empty line, truncated. */
    val label: String,
    /** True when anchored on a word range ("part"). */
    val isPart: Boolean,
    /** The echoed rhyme key of the anchored text, when the server returned one. */
    val rhymeKey: String?,
    val excluded: Boolean,
    val expanded: Boolean,
    val awaitingEnd: Boolean,
    val range: AnchorRange?,
    /** The snippet's beat-grid lines, for the expanded syllable strips. */
    val lines: List<GridLine>,
    val hint: String,
)

fun truncateChip(text: String): String = if (text.length > CHIP_TEXT_MAX) text.take(CHIP_TEXT_MAX - 1) + "…" else text

fun chipLabel(snippet: Snippet, range: AnchorRange?): String {
    if (range != null) {
        val picked = rangeText(snippet, range)
        if (!picked.isNullOrEmpty()) return truncateChip(picked)
    }
    val index = lastNonEmptyLineIndex(snippet)
    val text = if (index == null) snippet.rawText else snippet.gridData?.lines?.getOrNull(index)?.rawText ?: snippet.rawText
    return truncateChip(text.trim())
}

fun chipHint(snippet: Snippet, range: AnchorRange?, awaitingEnd: Boolean): String = when {
    range == null -> "Tap a start word, then an end word on the same line."
    awaitingEnd -> "Now tap the end word (or the start word again to clear)."
    else -> "Anchored on \"${rangeText(snippet, range) ?: ""}\"."
}

fun anchorChips(
    view: RhymeCanvasView?,
    excluded: Set<SnippetId>,
    ranges: Map<SnippetId, AnchorRange>,
    anchors: List<RhymeUnit>,
    expanded: Set<SnippetId>,
    awaitingEnd: Set<SnippetId>,
): List<AnchorChip> {
    if (view == null) return emptyList()
    return anchorSnippets(view.selectedIds, view.snippets, emptySet()).map { snippet ->
        val range = ranges[snippet.id]
        val waiting = snippet.id in awaitingEnd
        AnchorChip(
            snippetId = snippet.id,
            label = chipLabel(snippet, range),
            isPart = range != null,
            rhymeKey = anchors.firstOrNull { it.snippetId == snippet.id }?.rhymeKey?.joinToString(" "),
            excluded = snippet.id in excluded,
            expanded = snippet.id in expanded,
            awaitingEnd = waiting,
            range = range,
            lines = snippet.gridData?.lines.orEmpty(),
            hint = chipHint(snippet, range, waiting),
        )
    }
}
