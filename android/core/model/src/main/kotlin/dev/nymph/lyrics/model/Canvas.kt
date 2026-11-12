package dev.nymph.lyrics.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The persisted canvas viewport (`viewport_state`). The web client stores `{x, y, zoom}`;
 * missing keys fall back to the identity viewport.
 */
@Serializable
data class ViewportState(
    val x: Double = 0.0,
    val y: Double = 0.0,
    val zoom: Double = 1.0,
)

/** A canvas as listed (`GET /api/canvases`) and as returned by create/update. */
@Serializable
data class Canvas(
    val id: CanvasId,
    @SerialName("owner_id") val ownerId: UserId,
    val title: String,
    @SerialName("is_public") val isPublic: Boolean,
    @SerialName("share_token") val shareToken: String? = null,
    @SerialName("viewport_state") val viewportState: ViewportState? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    /** Content version (docs/features/sync_versions.md): +1 per content change, server-side only. */
    val version: Int = 1,
)

/** `GET /api/canvases/{id}`: the canvas plus everything on it (tags are user-wide: `GET /api/tags`). */
@Serializable
data class CanvasDetail(
    val id: CanvasId,
    @SerialName("owner_id") val ownerId: UserId,
    val title: String,
    @SerialName("is_public") val isPublic: Boolean,
    @SerialName("share_token") val shareToken: String? = null,
    @SerialName("viewport_state") val viewportState: ViewportState? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    val snippets: List<Snippet> = emptyList(),
    val groups: List<Group> = emptyList(),
    val version: Int = 1,
) {
    /** The canvas header without its contents. */
    fun toCanvas(): Canvas = Canvas(id, ownerId, title, isPublic, shareToken, viewportState, createdAt, updatedAt, version)
}

/** Body of `POST /api/canvases`. */
@Serializable
data class CanvasCreateRequest(
    val title: String = "Untitled Canvas",
    /** Client-chosen id (offline create); the server creates with it or returns the identical earlier create. */
    val id: CanvasId? = null,
)

/** Body of `PUT /api/canvases/{id}`; null fields are omitted and left unchanged by the server. */
@Serializable
data class CanvasUpdateRequest(
    val title: String? = null,
    @SerialName("is_public") val isPublic: Boolean? = null,
    @SerialName("viewport_state") val viewportState: ViewportState? = null,
    /** Checked only when [title] is present: 409 `version_conflict` when the canvas changed since. */
    @SerialName("base_version") val baseVersion: Int? = null,
)

/** A named group of snippets on a canvas. */
@Serializable
data class Group(
    val id: GroupId,
    @SerialName("canvas_id") val canvasId: CanvasId,
    val name: String,
    @SerialName("position_x") val positionX: Double,
    @SerialName("position_y") val positionY: Double,
    @SerialName("snippet_ids") val snippetIds: List<SnippetId> = emptyList(),
    @SerialName("created_at") val createdAt: String,
)
