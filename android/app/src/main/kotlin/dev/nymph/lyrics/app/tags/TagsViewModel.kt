package dev.nymph.lyrics.app.tags

import dev.nymph.lyrics.data.repository.TagPresetRepository
import dev.nymph.lyrics.data.repository.TagRepository
import dev.nymph.lyrics.logic.tags.TagFilter
import dev.nymph.lyrics.logic.tags.TagToggle
import dev.nymph.lyrics.logic.tags.tagLinks
import dev.nymph.lyrics.logic.tags.targetsOf
import dev.nymph.lyrics.logic.tags.toTagError
import dev.nymph.lyrics.model.CanvasId
import dev.nymph.lyrics.model.SnippetId
import dev.nymph.lyrics.model.Tag
import dev.nymph.lyrics.model.TagId
import dev.nymph.lyrics.model.UserTag
import dev.nymph.lyrics.model.error.ApiResult
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * The canvas's tag controls: the user's tags and presets, this canvas's filter bar (remembered
 * per canvas) and the picker for snippets. Lives in the canvas back-stack entry next to [port]
 * (the canvas ViewModel), so the editor and the word bank share it.
 */
class TagsViewModel(
    canvasId: CanvasId,
    tags: TagRepository,
    presets: TagPresetRepository,
    filterStore: TagFilterStore,
    port: TagCanvasPort,
) : TagControlsViewModel<SnippetId>(tags, presets, filterStore.forCanvas(canvasId), SnippetTagTarget(tags, port))

/** The canvas filter text of [canvasId]. */
fun TagFilterStore.forCanvas(canvasId: CanvasId): FilterTextStore = object : FilterTextStore {
    override suspend fun load(): String = this@forCanvas.load(canvasId)

    override suspend fun save(text: String) = this@forCanvas.save(canvasId, text)
}

/** Snippets as a [TagTarget]: links and unlinks go one snippet at a time (in parallel) through [tags]. */
class SnippetTagTarget(private val tags: TagRepository, private val port: TagCanvasPort) : TagTarget<SnippetId> {
    override fun currentLinks(ids: Set<SnippetId>): Map<SnippetId, Set<TagId>> = tagLinks(port.currentSnippets(), ids)

    override suspend fun applyToggle(tag: UserTag, toggle: TagToggle): String? {
        val ids = toggle.targetsOf<SnippetId>()
        val results = coroutineScope {
            ids.map { id ->
                async {
                    id to when (toggle) {
                        is TagToggle.Link -> tags.link(id, tag.id)
                        is TagToggle.Unlink -> tags.unlink(id, tag.id)
                    }
                }
            }.awaitAll()
        }
        for ((id, result) in results) {
            if (result !is ApiResult.Success) continue
            when (toggle) {
                is TagToggle.Link -> port.onTagLinked(id, tag.toTag())
                is TagToggle.Unlink -> port.onTagUnlinked(id, tag.id)
            }
        }
        return results.firstNotNullOfOrNull { it.second.errorOrNull() }?.let { toTagError(it).userMessage() }
    }

    override fun setTagFilter(filter: TagFilter) = port.setTagFilter(filter)

    override fun onTagEdited(tag: Tag) = port.onTagEdited(tag)

    override fun onTagDeleted(tag: TagId) = port.onTagDeleted(tag)
}
