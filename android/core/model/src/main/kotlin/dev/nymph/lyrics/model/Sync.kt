package dev.nymph.lyrics.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * `GET /api/sync/snapshot`: everything the app shows, for the caller only
 * (docs/features/sync_versions.md). Each snippet appears once; its per-canvas layout is in
 * [placements].
 */
@Serializable
data class SyncSnapshot(
    @SerialName("server_time") val serverTime: String,
    val canvases: List<Canvas> = emptyList(),
    val placements: List<SnapshotPlacement> = emptyList(),
    val snippets: List<SnapshotSnippet> = emptyList(),
    val tags: List<UserTag> = emptyList(),
    @SerialName("tag_presets") val tagPresets: List<TagPreset> = emptyList(),
    @SerialName("word_bank") val wordBank: List<BankWord> = emptyList(),
)

/** One snippet's layout on one canvas. */
@Serializable
data class SnapshotPlacement(
    @SerialName("snippet_id") val snippetId: SnippetId,
    @SerialName("canvas_id") val canvasId: CanvasId,
    @SerialName("placement_id") val placementId: PlacementId,
    @SerialName("position_x") val positionX: Double,
    @SerialName("position_y") val positionY: Double,
    val width: Double,
    val height: Double,
    @SerialName("z_index") val zIndex: Int,
    @SerialName("group_id") val groupId: GroupId? = null,
)

/** A snippet's shared content (the [Snippet] fields that are not per canvas), with its tag ids. */
@Serializable
data class SnapshotSnippet(
    val id: SnippetId,
    @SerialName("snippet_type") val snippetType: SnippetType,
    @SerialName("grid_mode") val gridMode: GridMode,
    @SerialName("beats_per_line") val beatsPerLine: Int? = null,
    @SerialName("grid_data") val gridData: GridData? = null,
    @SerialName("raw_text") val rawText: String,
    val phonetics: JsonElement? = null,
    @SerialName("derived_from") val derivedFrom: DerivedFrom? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("content_updated_at") val contentUpdatedAt: String,
    val version: Int = 1,
    @SerialName("tag_ids") val tagIds: List<TagId> = emptyList(),
)

/** The structured error codes of optimistic concurrency and client-chosen ids (`detail.error`). */
enum class SyncErrorCode(val wire: String) {
    /** 409: `base_version` is not the current version; `detail.current` is the server's copy. Nothing changed. */
    VERSION_CONFLICT("version_conflict"),

    /** 409: a create's client id exists for another user or with a different payload. */
    ID_CONFLICT("id_conflict"),
    ;

    companion object {
        fun parse(code: String?): SyncErrorCode? = entries.firstOrNull { it.wire == code }
    }
}
