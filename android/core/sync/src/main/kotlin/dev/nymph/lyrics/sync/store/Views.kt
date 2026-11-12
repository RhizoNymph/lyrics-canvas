package dev.nymph.lyrics.sync.store

import dev.nymph.lyrics.logic.list.parseServerTimestamp
import dev.nymph.lyrics.logic.placements.sortCanvasRefs
import dev.nymph.lyrics.model.BankWord
import dev.nymph.lyrics.model.Canvas
import dev.nymph.lyrics.model.CanvasDetail
import dev.nymph.lyrics.model.CanvasId
import dev.nymph.lyrics.model.CanvasRef
import dev.nymph.lyrics.model.Snippet
import dev.nymph.lyrics.model.SnippetId
import dev.nymph.lyrics.model.Tag
import dev.nymph.lyrics.model.TagId
import dev.nymph.lyrics.model.TagPreset
import dev.nymph.lyrics.model.UserTag
import java.time.Instant
import java.util.Locale

/**
 * What the screens read, assembled from [LocalData] in the server's response shapes and orders,
 * so the ViewModels cannot tell a local copy from a server answer.
 */
object Views {
    private val EPOCH: Instant = Instant.EPOCH

    private fun instant(text: String): Instant = parseServerTimestamp(text) ?: EPOCH

    /** `GET /api/canvases`: `updated_at` desc. */
    fun canvasList(d: LocalData): List<Canvas> = d.canvases.sortedByDescending { instant(it.updatedAt) }

    /** `GET /api/canvases/{id}`: the canvas and its snippets (bottom to top); null when it is not in the copy. */
    fun canvasDetail(d: LocalData, id: CanvasId): CanvasDetail? {
        val canvas = d.canvas(id) ?: return null
        val tags = tagIndex(d)
        val refs = canvasRefIndex(d)
        val byId = d.snippets.associateBy { it.id }
        val snippets = d.placements
            .filter { it.canvasId == id }
            .mapNotNull { p -> byId[p.snippetId]?.let { assemble(it, p, tags, refs.getValue(it.id)) } }
            .sortedWith(compareBy({ it.zIndex }, { it.createdAt }, { it.id.value }))
        return CanvasDetail(
            canvas.id, canvas.ownerId, canvas.title, canvas.isPublic, canvas.shareToken, canvas.viewportState,
            canvas.createdAt, canvas.updatedAt, snippets, emptyList(), canvas.version,
        )
    }

    /** The snippet [id] in [canvasId]'s context, or null when it is not placed there. */
    fun snippet(d: LocalData, id: SnippetId, canvasId: CanvasId): Snippet? {
        val content = d.snippet(id) ?: return null
        val placement = d.placement(id, canvasId) ?: return null
        return assemble(content, placement, tagIndex(d), canvasRefIndex(d)[id].orEmpty())
    }

    /** The snippet on its first canvas (by the server's canvas order), for screens with no canvas context. */
    fun snippetAnywhere(d: LocalData, id: SnippetId): Snippet? {
        val refs = canvasRefIndex(d)[id] ?: return null
        val first = refs.firstOrNull() ?: return null
        return snippet(d, id, first.id)
    }

    /** `GET /api/tags`: lowercase name, counts from the local links. */
    fun tagList(d: LocalData): List<UserTag> {
        val snippetCounts = HashMap<TagId, Int>()
        d.snippets.forEach { s -> s.tagIds.forEach { snippetCounts.merge(it, 1, Int::plus) } }
        val wordCounts = HashMap<TagId, Int>()
        d.words.forEach { w -> w.tagIds.forEach { wordCounts.merge(it, 1, Int::plus) } }
        return d.tags
            .map { UserTag(it.id, it.name, it.color, snippetCounts[it.id] ?: 0, it.createdAt, wordCounts[it.id] ?: 0, it.version) }
            .sortedBy { it.name.lowercase(Locale.ROOT) }
    }

    fun tag(d: LocalData, id: TagId): UserTag? = tagList(d).firstOrNull { it.id == id }

    /** `GET /api/tag-presets`: lowercase name. */
    fun presets(d: LocalData): List<TagPreset> = d.presets.sortedBy { it.name.lowercase(Locale.ROOT) }

    /** `GET /api/word-bank`: newest first. */
    fun words(d: LocalData): List<BankWord> {
        val tags = tagIndex(d)
        return d.words
            .sortedWith(compareByDescending<LocalWord> { instant(it.createdAt) }.thenBy { it.id.value })
            .map { word(it, tags) }
    }

    fun word(d: LocalData, w: LocalWord): BankWord = word(w, tagIndex(d))

    private fun word(w: LocalWord, tags: Map<TagId, Tag>): BankWord =
        BankWord(w.id, w.text, embedded(w.tagIds, tags), w.createdAt, w.version)

    private fun tagIndex(d: LocalData): Map<TagId, Tag> = d.tags.associate { it.id to Tag(it.id, it.name, it.color) }

    /** Tags sorted by lowercase name, as the server embeds them; links to unknown tags are skipped. */
    private fun embedded(ids: List<TagId>, tags: Map<TagId, Tag>): List<Tag> =
        ids.mapNotNull(tags::get).sortedBy { it.name.lowercase(Locale.ROOT) }

    /** Every snippet's `canvases`, in the server's order. */
    private fun canvasRefIndex(d: LocalData): Map<SnippetId, List<CanvasRef>> {
        val titles = d.canvases.associate { it.id to it.title }
        return d.placements
            .groupBy { it.snippetId }
            .mapValues { (_, ps) -> sortCanvasRefs(ps.mapNotNull { p -> titles[p.canvasId]?.let { CanvasRef(p.canvasId, it) } }) }
    }

    private fun assemble(s: LocalSnippet, p: LocalPlacement, tags: Map<TagId, Tag>, canvases: List<CanvasRef>): Snippet = Snippet(
        id = s.id,
        canvasId = p.canvasId,
        placementId = p.placementId,
        snippetType = s.snippetType,
        gridMode = s.gridMode,
        beatsPerLine = s.beatsPerLine,
        gridData = s.gridData,
        rawText = s.rawText,
        positionX = p.positionX,
        positionY = p.positionY,
        width = p.width,
        height = p.height,
        zIndex = p.zIndex,
        groupId = p.groupId,
        tags = embedded(s.tagIds, tags),
        phonetics = s.phonetics,
        derivedFrom = s.derivedFrom,
        createdAt = s.createdAt,
        updatedAt = s.updatedAt,
        contentUpdatedAt = s.contentUpdatedAt,
        canvases = canvases,
        version = s.version,
    )
}
