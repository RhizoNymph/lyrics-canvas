package dev.nymph.lyrics.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A tag as embedded in a snippet's `tags` (the backend's `SnippetTagResponse`). Tags are
 * user-wide: one list per user, shared by every canvas (docs/features/tag_expressions.md).
 */
@Serializable
data class Tag(
    val id: TagId,
    val name: String,
    /** `#rrggbb`. */
    val color: String,
)

/** A tag as listed by `GET /api/tags` and returned by create / update (`TagResponse`). */
@Serializable
data class UserTag(
    val id: TagId,
    val name: String,
    val color: String,
    /** Snippets (on any canvas) carrying the tag. */
    @SerialName("snippet_count") val snippetCount: Int = 0,
    @SerialName("created_at") val createdAt: String,
    /** Word bank words carrying the tag. */
    @SerialName("word_count") val wordCount: Int = 0,
) {
    /** The embedded form a snippet carries. */
    fun toTag(): Tag = Tag(id, name, color)
}

/** Body of `POST /api/tags`. */
@Serializable
data class TagCreateRequest(
    val name: String,
    val color: String? = null,
)

/** Body of `PUT /api/tags/{id}`; null fields are omitted and left unchanged. */
@Serializable
data class TagUpdateRequest(
    val name: String? = null,
    val color: String? = null,
)

/** Response of `POST /api/snippets/{id}/tags?tag_id=`. */
@Serializable
data class TagLinkResponse(
    val ok: Boolean = true,
)

/** Server-assigned preset id. */
@JvmInline
@Serializable
value class TagPresetId(val value: String) {
    override fun toString(): String = value
}

/** A saved, named filter expression (`TagPresetResponse`); [expression] is stored normalized and never empty. */
@Serializable
data class TagPreset(
    val id: TagPresetId,
    val name: String,
    val expression: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
)

/** Body of `POST /api/tag-presets`. */
@Serializable
data class TagPresetCreateRequest(
    val name: String,
    val expression: String,
)

/** Body of `PUT /api/tag-presets/{id}`; null fields are omitted and left unchanged. */
@Serializable
data class TagPresetUpdateRequest(
    val name: String? = null,
    val expression: String? = null,
)

/**
 * The `error` codes the tag, preset and rhyme routes put in `{"detail": {"error": code, ...}}`.
 * Unknown codes parse to null.
 */
enum class TagErrorCode(val wire: String) {
    /** 409: another of the user's tags has this name (case-insensitively). */
    TAG_NAME_TAKEN("tag_name_taken"),

    /** 404: the tag is missing or not the caller's. */
    TAG_NOT_FOUND("tag_not_found"),

    /** 409: another of the user's presets has this name (case-insensitively). */
    PRESET_NAME_TAKEN("preset_name_taken"),

    /** 404: the preset is missing or not the caller's. */
    PRESET_NOT_FOUND("preset_not_found"),

    /** 422: the expression does not parse; `detail.expression_error` says where. */
    INVALID_TAG_EXPRESSION("invalid_tag_expression"),

    /** 422: a preset needs a non-empty expression. */
    EMPTY_PRESET("empty_preset"),

    /** 422: blank, longer than 100 characters, or with control characters. */
    INVALID_TAG_NAME("invalid_tag_name"),

    /** 422: not `#rrggbb`. */
    INVALID_COLOR("invalid_color"),

    /** 422: like [INVALID_TAG_NAME], for a preset. */
    INVALID_PRESET_NAME("invalid_preset_name"),

    /** 409: renaming the tag would push one of the user's presets past the expression length limit. */
    PRESET_EXPRESSION_TOO_LONG("preset_expression_too_long"),
    ;

    companion object {
        fun parse(code: String?): TagErrorCode? = entries.firstOrNull { it.wire == code }
    }
}
