package dev.nymph.lyrics.sync.store

import dev.nymph.lyrics.model.BankWord
import dev.nymph.lyrics.model.BankWordId
import dev.nymph.lyrics.model.Canvas
import dev.nymph.lyrics.model.CanvasId
import dev.nymph.lyrics.model.DerivedFrom
import dev.nymph.lyrics.model.GridData
import dev.nymph.lyrics.model.GridMode
import dev.nymph.lyrics.model.GroupId
import dev.nymph.lyrics.model.PlacementId
import dev.nymph.lyrics.model.SnapshotPlacement
import dev.nymph.lyrics.model.SnapshotSnippet
import dev.nymph.lyrics.model.Snippet
import dev.nymph.lyrics.model.SnippetId
import dev.nymph.lyrics.model.SnippetType
import dev.nymph.lyrics.model.TagId
import dev.nymph.lyrics.model.TagPreset
import dev.nymph.lyrics.model.UserId
import dev.nymph.lyrics.model.UserTag
import dev.nymph.lyrics.sync.outbox.Outbox
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** A snippet's shared content (what every canvas holding it shows); tags by id so renames show everywhere. */
@Serializable
data class LocalSnippet(
    val id: SnippetId,
    @SerialName("snippet_type") val snippetType: SnippetType,
    @SerialName("grid_mode") val gridMode: GridMode,
    @SerialName("beats_per_line") val beatsPerLine: Int? = null,
    @SerialName("grid_data") val gridData: GridData? = null,
    @SerialName("raw_text") val rawText: String,
    @SerialName("tag_ids") val tagIds: List<TagId> = emptyList(),
    val phonetics: JsonElement? = null,
    @SerialName("derived_from") val derivedFrom: DerivedFrom? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("content_updated_at") val contentUpdatedAt: String,
    val version: Int,
) {
    companion object {
        fun of(s: SnapshotSnippet): LocalSnippet = LocalSnippet(
            s.id, s.snippetType, s.gridMode, s.beatsPerLine, s.gridData, s.rawText, s.tagIds, s.phonetics, s.derivedFrom,
            s.createdAt, s.updatedAt, s.contentUpdatedAt, s.version,
        )

        /** The shared part of a snippet response. */
        fun of(s: Snippet): LocalSnippet = LocalSnippet(
            s.id, s.snippetType, s.gridMode, s.beatsPerLine, s.gridData, s.rawText, s.tags.map { it.id }, s.phonetics, s.derivedFrom,
            s.createdAt, s.updatedAt, s.contentUpdatedAt, s.version,
        )
    }
}

/** A snippet's layout on one canvas; at most one per (snippet, canvas). */
@Serializable
data class LocalPlacement(
    @SerialName("snippet_id") val snippetId: SnippetId,
    @SerialName("canvas_id") val canvasId: CanvasId,
    @SerialName("placement_id") val placementId: PlacementId,
    @SerialName("position_x") val positionX: Double,
    @SerialName("position_y") val positionY: Double,
    val width: Double,
    val height: Double,
    @SerialName("z_index") val zIndex: Int,
    @SerialName("group_id") val groupId: GroupId? = null,
) {
    companion object {
        fun of(p: SnapshotPlacement): LocalPlacement =
            LocalPlacement(p.snippetId, p.canvasId, p.placementId, p.positionX, p.positionY, p.width, p.height, p.zIndex, p.groupId)

        /** The placement part of a snippet response (in its canvas's context). */
        fun of(s: Snippet): LocalPlacement =
            LocalPlacement(s.id, s.canvasId, s.placementId, s.positionX, s.positionY, s.width, s.height, s.zIndex, s.groupId)
    }
}

/** A user tag without its counts (computed from the local links). */
@Serializable
data class LocalTag(
    val id: TagId,
    val name: String,
    val color: String,
    @SerialName("created_at") val createdAt: String,
    val version: Int,
) {
    companion object {
        fun of(t: UserTag): LocalTag = LocalTag(t.id, t.name, t.color, t.createdAt, t.version)
    }
}

/** A word bank word; tags by id. */
@Serializable
data class LocalWord(
    val id: BankWordId,
    val text: String,
    @SerialName("tag_ids") val tagIds: List<TagId> = emptyList(),
    @SerialName("created_at") val createdAt: String,
    val version: Int,
) {
    companion object {
        fun of(w: BankWord): LocalWord = LocalWord(w.id, w.text, w.tags.map { it.id }, w.createdAt, w.version)
    }
}

/**
 * The signed-in user's local copy: the last snapshot with local changes applied on top
 * (optimistically), plus the [outbox] of changes the server has not confirmed. One per account;
 * [account] is null until the store learns who it belongs to.
 *
 * Lists keep the server's order where it matters (canvases by `updated_at`, words newest first);
 * views sort again, so order here is only a tie-break.
 */
@Serializable
data class LocalData(
    val account: UserId? = null,
    val canvases: List<Canvas> = emptyList(),
    val snippets: List<LocalSnippet> = emptyList(),
    val placements: List<LocalPlacement> = emptyList(),
    val tags: List<LocalTag> = emptyList(),
    @SerialName("tag_presets") val presets: List<TagPreset> = emptyList(),
    val words: List<LocalWord> = emptyList(),
    val outbox: Outbox = Outbox.EMPTY,
    /** `server_time` of the last snapshot merged; null before the first one (nothing to show yet). */
    @SerialName("snapshot_at") val snapshotAt: String? = null,
) {
    val hasSnapshot: Boolean get() = snapshotAt != null

    fun canvas(id: CanvasId): Canvas? = canvases.firstOrNull { it.id == id }

    fun snippet(id: SnippetId): LocalSnippet? = snippets.firstOrNull { it.id == id }

    fun placement(snippet: SnippetId, canvas: CanvasId): LocalPlacement? =
        placements.firstOrNull { it.snippetId == snippet && it.canvasId == canvas }

    fun placementsOf(snippet: SnippetId): List<LocalPlacement> = placements.filter { it.snippetId == snippet }

    fun tag(id: TagId): LocalTag? = tags.firstOrNull { it.id == id }

    fun word(id: BankWordId): LocalWord? = words.firstOrNull { it.id == id }

    // ---- Upserts and removals (order kept; new items go first, like the server's newest-first lists) ----

    fun withCanvas(canvas: Canvas): LocalData = copy(canvases = canvases.upsert(canvas) { it.id == canvas.id })

    fun withSnippet(snippet: LocalSnippet): LocalData = copy(snippets = snippets.upsert(snippet) { it.id == snippet.id })

    fun withPlacement(p: LocalPlacement): LocalData =
        copy(placements = placements.upsert(p) { it.snippetId == p.snippetId && it.canvasId == p.canvasId })

    fun withTag(tag: LocalTag): LocalData = copy(tags = tags.upsert(tag) { it.id == tag.id })

    fun withWord(word: LocalWord): LocalData = copy(words = words.upsert(word) { it.id == word.id })

    fun withoutPlacement(snippet: SnippetId, canvas: CanvasId): LocalData =
        copy(placements = placements.filterNot { it.snippetId == snippet && it.canvasId == canvas })

    /** The snippet and all its placements. */
    fun withoutSnippet(id: SnippetId): LocalData =
        copy(snippets = snippets.filterNot { it.id == id }, placements = placements.filterNot { it.snippetId == id })

    /** The canvas, its placements, and the snippets that were only on it (the server's cascade). */
    fun withoutCanvas(id: CanvasId): LocalData {
        val here = placements.filter { it.canvasId == id }.mapTo(HashSet()) { it.snippetId }
        val left = placements.filterNot { it.canvasId == id }
        val stillPlaced = left.mapTo(HashSet()) { it.snippetId }
        return copy(
            canvases = canvases.filterNot { it.id == id },
            placements = left,
            snippets = snippets.filterNot { it.id in here && it.id !in stillPlaced },
        )
    }

    /** The tag, unlinked from every snippet and word. */
    fun withoutTag(id: TagId): LocalData = copy(
        tags = tags.filterNot { it.id == id },
        snippets = snippets.map { s -> if (id in s.tagIds) s.copy(tagIds = s.tagIds - id) else s },
        words = words.map { w -> if (id in w.tagIds) w.copy(tagIds = w.tagIds - id) else w },
    )

    fun withoutWord(id: BankWordId): LocalData = copy(words = words.filterNot { it.id == id })

    fun withOutbox(outbox: Outbox): LocalData = copy(outbox = outbox)
}

/** Replace the first item matching [same] in place, or put [item] first. */
internal inline fun <T> List<T>.upsert(item: T, same: (T) -> Boolean): List<T> {
    val index = indexOfFirst(same)
    return if (index < 0) listOf(item) + this else toMutableList().also { it[index] = item }
}
