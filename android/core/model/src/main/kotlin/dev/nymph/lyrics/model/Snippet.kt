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

/**
 * A snippet as returned by every snippet route and inside [CanvasDetail], in the context of one
 * canvas: [canvasId], the position, size, [zIndex] and [groupId] come from the snippet's placement
 * on that canvas ([placementId]); text, type, grid, tags and phonetics are shared by every canvas
 * holding it ([canvases], which includes [canvasId]).
 */
@Serializable
data class Snippet(
    val id: SnippetId,
    @SerialName("canvas_id") val canvasId: CanvasId,
    @SerialName("placement_id") val placementId: PlacementId,
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
    /** Last content edit (text, type or grid); unlike [updatedAt], moves and resizes leave it alone. */
    @SerialName("content_updated_at") val contentUpdatedAt: String,
    /** Every canvas holding this snippet, the current one included; sorted by lowercase title, then id. */
    val canvases: List<CanvasRef>,
    /** Content version (docs/features/sync_versions.md): +1 per content change, server-side only. */
    val version: Int = 1,
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
    /** Client-chosen id (offline create); creates are idempotent by id. */
    val id: SnippetId? = null,
    val width: Double? = null,
    val height: Double? = null,
)

/**
 * Body of `PUT /api/snippets/{id}?canvas_id={cid}`. Null fields are omitted and left unchanged (the
 * server drops nulls). `raw_text` without `grid_data` makes the server re-syllabify. Content fields
 * change the shared snippet; layout fields (position, size, z, group) change its placement on `cid`.
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
    /** Checked only when a content field is present: 409 `version_conflict` when the snippet changed since. */
    @SerialName("base_version") val baseVersion: Int? = null,
)

/** One entry of a batch position update. */
@Serializable
data class PositionUpdate(
    val id: SnippetId,
    @SerialName("position_x") val positionX: Double,
    @SerialName("position_y") val positionY: Double,
)

/** Body of `PATCH /api/snippets/batch-position`: [canvasId] names the placements every update moves. */
@Serializable
data class BatchPositionRequest(
    @SerialName("canvas_id") val canvasId: CanvasId,
    val updates: List<PositionUpdate>,
)

/** Response of `PATCH /api/snippets/batch-position`: rows the server changed. */
@Serializable
data class BatchPositionResponse(
    val updated: Int,
)
