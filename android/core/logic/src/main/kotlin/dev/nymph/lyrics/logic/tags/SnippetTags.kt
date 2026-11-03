package dev.nymph.lyrics.logic.tags

import dev.nymph.lyrics.model.Snippet
import dev.nymph.lyrics.model.SnippetId
import dev.nymph.lyrics.model.Tag
import dev.nymph.lyrics.model.TagId

/** The snippet after the server confirmed linking [tag] (appended once). */
fun Snippet.withTagLinked(tag: Tag): Snippet = if (tags.any { it.id == tag.id }) this else copy(tags = tags + tag)

fun Snippet.withTagUnlinked(tag: TagId): Snippet = if (tags.none { it.id == tag }) this else copy(tags = tags.filter { it.id != tag })

/** Every snippet carrying [tag]'s id shows its new name and colour. */
fun List<Snippet>.withTagEdited(tag: Tag): List<Snippet> = map { s ->
    if (s.tags.none { it.id == tag.id }) s else s.copy(tags = s.tags.map { if (it.id == tag.id) tag else it })
}

fun List<Snippet>.withTagDeleted(tag: TagId): List<Snippet> = map { it.withTagUnlinked(tag) }

/** The tag ids of each of [ids] that still exists in [byId] (in [ids] order). */
fun tagLinks(byId: Map<SnippetId, Snippet>, ids: Collection<SnippetId>): Map<SnippetId, Set<TagId>> =
    ids.mapNotNull { id -> byId[id]?.let { s -> id to s.tags.mapTo(LinkedHashSet()) { it.id } } }.toMap(LinkedHashMap())
