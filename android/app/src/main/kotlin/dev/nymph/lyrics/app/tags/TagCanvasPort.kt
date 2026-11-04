package dev.nymph.lyrics.app.tags

import dev.nymph.lyrics.logic.tags.TagFilter
import dev.nymph.lyrics.model.Snippet
import dev.nymph.lyrics.model.SnippetId
import dev.nymph.lyrics.model.Tag
import dev.nymph.lyrics.model.TagId

/**
 * The canvas as seen by the tag picker and the filter bar. Implemented by the canvas ViewModel;
 * every change reported here is one the server already confirmed.
 */
interface TagCanvasPort {
    /** The canvas's snippets by id (empty until loaded). */
    fun currentSnippets(): Map<SnippetId, Snippet>

    /** The filter in force on the canvas and the list (the filter bar's last valid expression). */
    fun setTagFilter(filter: TagFilter)

    fun onTagLinked(snippet: SnippetId, tag: Tag)

    fun onTagUnlinked(snippet: SnippetId, tag: TagId)

    /** A tag was renamed or recoloured. */
    fun onTagEdited(tag: Tag)

    fun onTagDeleted(tag: TagId)
}
