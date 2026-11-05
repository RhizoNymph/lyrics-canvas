package dev.nymph.lyrics.app.tags

import dev.nymph.lyrics.logic.tags.TagFilter
import dev.nymph.lyrics.logic.tags.TagToggle
import dev.nymph.lyrics.model.Tag
import dev.nymph.lyrics.model.TagId
import dev.nymph.lyrics.model.TaggedItemId
import dev.nymph.lyrics.model.UserTag

/**
 * The list a [TagControlsViewModel] filters and tags: the canvas's snippets or the word bank's
 * words. Every change it applies is one the server confirmed.
 */
interface TagTarget<K : TaggedItemId> {
    /** Tag ids of each of [ids] that is still in the list. */
    fun currentLinks(ids: Set<K>): Map<K, Set<TagId>>

    /**
     * Send [toggle] for [tag] to the server and apply whatever it confirmed. Returns the user text
     * of the first failure, or null when everything succeeded.
     */
    suspend fun applyToggle(tag: UserTag, toggle: TagToggle): String?

    /** The filter in force (the bar's last valid expression). */
    fun setTagFilter(filter: TagFilter)

    /** A tag was renamed or recoloured. */
    fun onTagEdited(tag: Tag)

    fun onTagDeleted(tag: TagId)
}

/** Where a filter bar's last valid text is remembered on this device. */
interface FilterTextStore {
    /** The remembered text, or "" when none. */
    suspend fun load(): String

    /** Remember [text]; "" forgets it. */
    suspend fun save(text: String)
}

/**
 * Hears about tag creates, edits and deletes the user confirmed on one screen, so another screen
 * showing the same user-wide tags (the canvas under the word bank) can follow.
 */
interface TagChangeListener {
    fun tagCreated(tag: UserTag)

    /** [before] is the tag as this screen knew it. */
    fun tagEdited(before: UserTag, after: UserTag)

    fun tagDeleted(tag: TagId)

    data object None : TagChangeListener {
        override fun tagCreated(tag: UserTag) = Unit

        override fun tagEdited(before: UserTag, after: UserTag) = Unit

        override fun tagDeleted(tag: TagId) = Unit
    }
}

/** Forwards to another screen's tag controls ([TagControlsViewModel.tagEditedElsewhere] and friends). */
class ForwardTagChanges(private val other: TagControlsViewModel<*>) : TagChangeListener {
    override fun tagCreated(tag: UserTag) = other.tagCreatedElsewhere(tag)

    override fun tagEdited(before: UserTag, after: UserTag) = other.tagEditedElsewhere(before, after)

    override fun tagDeleted(tag: TagId) = other.tagDeletedElsewhere(tag)
}
