package dev.nymph.lyrics.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
enum class SnippetType {
    @SerialName("verse") VERSE,
    @SerialName("chorus") CHORUS,
    @SerialName("bridge") BRIDGE,
    @SerialName("hook") HOOK,
    @SerialName("freestyle") FREESTYLE,
}

@Serializable
enum class GridMode {
    @SerialName("auto") AUTO,
    @SerialName("fixed") FIXED,
}

/** Where a rhyme unit came from; also the provenance recorded in [DerivedFrom]. */
@Serializable
enum class Provenance {
    @SerialName("note") NOTE,
    @SerialName("dictionary") DICTIONARY,
    @SerialName("datamuse") DATAMUSE,
    @SerialName("generated") GENERATED,
}

/** The rhyme candidate a materialized snippet was created from (`derived_from`). */
@Serializable
data class DerivedFrom(
    val provenance: Provenance,
    @SerialName("source_unit_id") val sourceUnitId: String,
    val score: Double,
    @SerialName("match_depth") val matchDepth: Int,
    val k: Int,
    @SerialName("anchor_unit_id") val anchorUnitId: String,
    @SerialName("anchor_text") val anchorText: String,
)

/** A snippet as returned by every snippet route and inside [CanvasDetail]. */
@Serializable
data class Snippet(
    val id: SnippetId,
    @SerialName("canvas_id") val canvasId: CanvasId,
    @SerialName("snippet_type") val snippetType: SnippetType,
    @SerialName("grid_mode") val gridMode: GridMode,
    @SerialName("beats_per_line") val beatsPerLine: Int? = null,
    @SerialName("grid_data") val gridData: GridData? = null,
    @SerialName("raw_text") val rawText: String,
    @SerialName("position_x") val positionX: Double,
    @SerialName("position_y") val positionY: Double,
    val width: Double,
    val height: Double,
    @SerialName("z_index") val zIndex: Int,
    @SerialName("group_id") val groupId: GroupId? = null,
    val tags: List<Tag> = emptyList(),
    /** Server-computed phonetics; carried opaquely (the app never reads it). */
    val phonetics: JsonElement? = null,
    @SerialName("derived_from") val derivedFrom: DerivedFrom? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
)

/**
 * Body of `POST /api/canvases/{id}/snippets`. Null fields are omitted and take the server
 * default. A given [gridData] is stored as-is (no re-syllabification).
 */
@Serializable
data class SnippetCreateRequest(
    @SerialName("position_x") val positionX: Double,
    @SerialName("position_y") val positionY: Double,
    @SerialName("snippet_type") val snippetType: SnippetType? = null,
    @SerialName("raw_text") val rawText: String? = null,
    @SerialName("grid_mode") val gridMode: GridMode? = null,
    @SerialName("beats_per_line") val beatsPerLine: Int? = null,
    @SerialName("grid_data") val gridData: GridData? = null,
    @SerialName("group_id") val groupId: GroupId? = null,
    /** Set only by rhyme-view materialization. */
    @SerialName("derived_from") val derivedFrom: DerivedFrom? = null,
)

/**
 * Body of `PUT /api/snippets/{id}`. Null fields are omitted and left unchanged (the server
 * drops nulls). `raw_text` without `grid_data` makes the server re-syllabify.
 */
@Serializable
data class SnippetUpdateRequest(
    @SerialName("snippet_type") val snippetType: SnippetType? = null,
    @SerialName("grid_mode") val gridMode: GridMode? = null,
    @SerialName("beats_per_line") val beatsPerLine: Int? = null,
    @SerialName("grid_data") val gridData: GridData? = null,
    @SerialName("raw_text") val rawText: String? = null,
    @SerialName("position_x") val positionX: Double? = null,
    @SerialName("position_y") val positionY: Double? = null,
    val width: Double? = null,
    val height: Double? = null,
    @SerialName("z_index") val zIndex: Int? = null,
    @SerialName("group_id") val groupId: GroupId? = null,
)

/** One entry of a batch position update. */
@Serializable
data class PositionUpdate(
    val id: SnippetId,
    @SerialName("position_x") val positionX: Double,
    @SerialName("position_y") val positionY: Double,
)

/** Body of `PATCH /api/snippets/batch-position`. */
@Serializable
data class BatchPositionRequest(
    val updates: List<PositionUpdate>,
)

/** Response of `PATCH /api/snippets/batch-position`: rows the server changed. */
@Serializable
data class BatchPositionResponse(
    val updated: Int,
)
