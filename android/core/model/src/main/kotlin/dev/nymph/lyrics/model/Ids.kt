package dev.nymph.lyrics.model

import kotlinx.serialization.Serializable

/**
 * The id of something that carries user tags: a snippet or a word bank word. Lets the tag picker
 * and its planning work on either kind.
 */
sealed interface TaggedItemId {
    val value: String
}

/** Server-assigned snippet id (a UUID string); serialises as a bare JSON string. */
@JvmInline
@Serializable
value class SnippetId(override val value: String) : TaggedItemId {
    override fun toString(): String = value
}

/** Server-assigned canvas id. */
@JvmInline
@Serializable
value class CanvasId(val value: String) {
    override fun toString(): String = value
}

/** Server-assigned id of a snippet's placement on one canvas (its layout there). */
@JvmInline
@Serializable
value class PlacementId(val value: String) {
    override fun toString(): String = value
}

/** Server-assigned tag id. */
@JvmInline
@Serializable
value class TagId(val value: String) {
    override fun toString(): String = value
}

/** Server-assigned group id. */
@JvmInline
@Serializable
value class GroupId(val value: String) {
    override fun toString(): String = value
}

/** Server-assigned user id. */
@JvmInline
@Serializable
value class UserId(val value: String) {
    override fun toString(): String = value
}

/** Server-assigned word bank word id. */
@JvmInline
@Serializable
value class BankWordId(override val value: String) : TaggedItemId {
    override fun toString(): String = value
}
