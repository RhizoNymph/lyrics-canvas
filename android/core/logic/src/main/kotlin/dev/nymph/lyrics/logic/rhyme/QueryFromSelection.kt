package dev.nymph.lyrics.logic.rhyme

import dev.nymph.lyrics.logic.jsTrim
import dev.nymph.lyrics.model.AnchorRange
import dev.nymph.lyrics.model.AnchorRef
import dev.nymph.lyrics.model.Snippet
import dev.nymph.lyrics.model.SnippetId
import dev.nymph.lyrics.model.TextAnchorRef

/** Selected snippets that exist, in id order for deterministic requests. */
fun selectedSnippets(selectedIds: Set<SnippetId>, snippets: Map<SnippetId, Snippet>): List<Snippet> =
    selectedIds.sortedBy { it.value }.mapNotNull { snippets[it] }

/** Selected snippets that act as anchors: present, non-blank, and not excluded via a chip. */
fun anchorSnippets(selectedIds: Set<SnippetId>, snippets: Map<SnippetId, Snippet>, excluded: Set<SnippetId>): List<Snippet> =
    selectedSnippets(selectedIds, snippets).filter { jsTrim(it.rawText).isNotEmpty() && it.id !in excluded }

/**
 * The `anchors` of a rhyme request: one per anchor snippet (its last non-empty line, or the word
 * range picked for it in [ranges]), then the trimmed [typedText] if any.
 */
fun anchorsFromSelection(
    selectedIds: Set<SnippetId>,
    snippets: Map<SnippetId, Snippet>,
    typedText: String,
    excluded: Set<SnippetId> = emptySet(),
    ranges: Map<SnippetId, AnchorRange> = emptyMap(),
): List<AnchorRef> {
    val anchors: MutableList<AnchorRef> = anchorSnippets(selectedIds, snippets, excluded)
        .mapTo(mutableListOf()) { anchorRefFor(it, ranges[it.id]) }
    val text = jsTrim(typedText)
    if (text.isNotEmpty()) anchors += TextAnchorRef(text)
    return anchors
}
