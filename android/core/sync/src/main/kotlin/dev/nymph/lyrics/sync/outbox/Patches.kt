package dev.nymph.lyrics.sync.outbox

import dev.nymph.lyrics.model.GridData
import dev.nymph.lyrics.model.GridMode
import dev.nymph.lyrics.model.GroupId
import dev.nymph.lyrics.model.SnippetType
import dev.nymph.lyrics.model.SnippetUpdateRequest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The content fields of a snippet PUT (null = unchanged). Built only through [of], which refuses
 * an empty patch, so an [EditSnippet][SyncOp.EditSnippet] always changes something.
 */
@Serializable
@ConsistentCopyVisibility
data class ContentPatch private constructor(
    @SerialName("snippet_type") val snippetType: SnippetType? = null,
    @SerialName("grid_mode") val gridMode: GridMode? = null,
    @SerialName("beats_per_line") val beatsPerLine: Int? = null,
    @SerialName("grid_data") val gridData: GridData? = null,
    @SerialName("raw_text") val rawText: String? = null,
) {
    /**
     * This patch followed by [later] as one PUT: later fields win. Text sent without a grid makes
     * the server re-syllabify, so an earlier grid is dropped when [later] changes only the text.
     */
    fun then(later: ContentPatch): ContentPatch = ContentPatch(
        snippetType = later.snippetType ?: snippetType,
        gridMode = later.gridMode ?: gridMode,
        beatsPerLine = later.beatsPerLine ?: beatsPerLine,
        gridData = if (later.rawText != null && later.gridData == null) null else later.gridData ?: gridData,
        rawText = later.rawText ?: rawText,
    )

    companion object {
        fun of(
            snippetType: SnippetType? = null,
            gridMode: GridMode? = null,
            beatsPerLine: Int? = null,
            gridData: GridData? = null,
            rawText: String? = null,
        ): ContentPatch? = ContentPatch(snippetType, gridMode, beatsPerLine, gridData, rawText).takeIf {
            snippetType != null || gridMode != null || beatsPerLine != null || gridData != null || rawText != null
        }

        /** The content part of a snippet PUT, or null when it has none. */
        fun of(body: SnippetUpdateRequest): ContentPatch? = of(body.snippetType, body.gridMode, body.beatsPerLine, body.gridData, body.rawText)
    }
}

/** The layout fields of a snippet PUT on one canvas (null = unchanged); never empty, built by [of]. */
@Serializable
@ConsistentCopyVisibility
data class LayoutPatch private constructor(
    @SerialName("position_x") val positionX: Double? = null,
    @SerialName("position_y") val positionY: Double? = null,
    val width: Double? = null,
    val height: Double? = null,
    @SerialName("z_index") val zIndex: Int? = null,
    @SerialName("group_id") val groupId: GroupId? = null,
) {
    /** Last write wins, field by field. */
    fun then(later: LayoutPatch): LayoutPatch = LayoutPatch(
        positionX = later.positionX ?: positionX,
        positionY = later.positionY ?: positionY,
        width = later.width ?: width,
        height = later.height ?: height,
        zIndex = later.zIndex ?: zIndex,
        groupId = later.groupId ?: groupId,
    )

    companion object {
        fun of(
            positionX: Double? = null,
            positionY: Double? = null,
            width: Double? = null,
            height: Double? = null,
            zIndex: Int? = null,
            groupId: GroupId? = null,
        ): LayoutPatch? = LayoutPatch(positionX, positionY, width, height, zIndex, groupId).takeIf {
            positionX != null || positionY != null || width != null || height != null || zIndex != null || groupId != null
        }

        fun of(body: SnippetUpdateRequest): LayoutPatch? = of(body.positionX, body.positionY, body.width, body.height, body.zIndex, body.groupId)
    }
}
