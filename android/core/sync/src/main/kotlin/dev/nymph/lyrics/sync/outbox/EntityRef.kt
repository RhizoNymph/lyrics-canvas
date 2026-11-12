package dev.nymph.lyrics.sync.outbox

import dev.nymph.lyrics.model.BankWordId
import dev.nymph.lyrics.model.CanvasId
import dev.nymph.lyrics.model.SnippetId
import dev.nymph.lyrics.model.TagId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One versioned server entity: the unit of ordering, of conflicts ("at most one parked conflict
 * per entity") and of the "Not synced" badge. A snippet's placements belong to the snippet.
 */
@Serializable
sealed interface EntityRef {
    @Serializable
    @SerialName("snippet")
    data class Snippet(val id: SnippetId) : EntityRef

    @Serializable
    @SerialName("canvas")
    data class Canvas(val id: CanvasId) : EntityRef

    @Serializable
    @SerialName("tag")
    data class Tag(val id: TagId) : EntityRef

    @Serializable
    @SerialName("word")
    data class Word(val id: BankWordId) : EntityRef
}
