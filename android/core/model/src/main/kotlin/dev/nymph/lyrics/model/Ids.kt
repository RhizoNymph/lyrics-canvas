package dev.nymph.lyrics.model

import kotlinx.serialization.Serializable

/** Server-assigned snippet id (a UUID string); serialises as a bare JSON string. */
@JvmInline
@Serializable
value class SnippetId(val value: String) {
    override fun toString(): String = value
}

/** Server-assigned canvas id. */
@JvmInline
@Serializable
value class CanvasId(val value: String) {
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
