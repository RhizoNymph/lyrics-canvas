package dev.nymph.lyrics.sync.apply

import dev.nymph.lyrics.logic.grid.localSyllabifyLine
import dev.nymph.lyrics.model.BankWord
import dev.nymph.lyrics.model.BankWordId
import dev.nymph.lyrics.model.BatchPositionResponse
import dev.nymph.lyrics.model.Canvas
import dev.nymph.lyrics.model.CanvasId
import dev.nymph.lyrics.model.GridData
import dev.nymph.lyrics.model.GridMode
import dev.nymph.lyrics.model.MergeRequest
import dev.nymph.lyrics.model.MergeResponse
import dev.nymph.lyrics.model.PlacementErrorCode
import dev.nymph.lyrics.model.PlacementId
import dev.nymph.lyrics.model.PositionUpdate
import dev.nymph.lyrics.model.RemovePlacementResponse
import dev.nymph.lyrics.model.Snippet
import dev.nymph.lyrics.model.SnippetCreateRequest
import dev.nymph.lyrics.model.SnippetId
import dev.nymph.lyrics.model.SnippetType
import dev.nymph.lyrics.model.SnippetUpdateRequest
import dev.nymph.lyrics.model.TagErrorCode
import dev.nymph.lyrics.model.TagId
import dev.nymph.lyrics.model.TagLinkOp
import dev.nymph.lyrics.model.TagUpdateRequest
import dev.nymph.lyrics.model.TransferMode
import dev.nymph.lyrics.model.TransferRequest
import dev.nymph.lyrics.model.TransferResponse
import dev.nymph.lyrics.model.UserId
import dev.nymph.lyrics.model.UserTag
import dev.nymph.lyrics.model.ViewportState
import dev.nymph.lyrics.model.WordBankAddResponse
import dev.nymph.lyrics.model.WordBankErrorCode
import dev.nymph.lyrics.model.error.ApiError
import dev.nymph.lyrics.model.error.ApiResult
import dev.nymph.lyrics.sync.outbox.ContentPatch
import dev.nymph.lyrics.sync.outbox.LayoutPatch
import dev.nymph.lyrics.sync.outbox.SnippetVersion
import dev.nymph.lyrics.sync.outbox.SyncOp
import dev.nymph.lyrics.sync.store.LocalData
import dev.nymph.lyrics.sync.store.LocalPlacement
import dev.nymph.lyrics.sync.store.LocalSnippet
import dev.nymph.lyrics.sync.store.LocalTag
import dev.nymph.lyrics.sync.store.LocalWord
import dev.nymph.lyrics.sync.store.Views
import java.util.Locale

/** The server's default tag colour (`DEFAULT_TAG_COLOR`). */
const val DEFAULT_TAG_COLOR: String = "#6366f1"

/** The server's default placement size. */
const val DEFAULT_SNIPPET_WIDTH: Double = 300.0
const val DEFAULT_SNIPPET_HEIGHT: Double = 200.0

/** A local edit's outcome: the store after it (unchanged on failure) and what the repository answers. */
data class Edited<out T>(val data: LocalData, val result: ApiResult<T>)

/**
 * Every offline-capable change, applied to the local copy the way the server would apply it, with
 * its outbox operation appended (coalesced). Pure: ids and timestamps come from [newId] and [now].
 * Failures mirror the server's (same status and `detail.error` code), so the ViewModels' error
 * mapping is unchanged.
 */
class LocalEdits(private val now: () -> String, private val newId: () -> String) {
    private fun <T> ok(data: LocalData, value: T) = Edited(data, ApiResult.Success(value))

    private fun <T> fail(data: LocalData, error: ApiError) = Edited<T>(data, ApiResult.Failure(error))

    private fun LocalData.enqueue(op: SyncOp): LocalData = withOutbox(outbox.enqueue(op))

    private fun notFound(code: String?, what: String) = ApiError.NotFound("$what not found", code)

    // ---- Canvases ----

    fun createCanvas(d: LocalData, title: String): Edited<Canvas> {
        val id = CanvasId(newId())
        val at = now()
        val canvas = Canvas(id, d.account ?: UserId(""), title, false, null, null, at, at, 1)
        return ok(d.withCanvas(canvas).enqueue(SyncOp.CreateCanvas(id, title)), canvas)
    }

    fun renameCanvas(d: LocalData, id: CanvasId, title: String): Edited<Canvas> {
        val canvas = d.canvas(id) ?: return fail(d, notFound(PlacementErrorCode.CANVAS_NOT_FOUND.wire, "Canvas"))
        val renamed = canvas.copy(title = title, updatedAt = now())
        return ok(d.withCanvas(renamed).enqueue(SyncOp.RenameCanvas(id, title, canvas.version)), renamed)
    }

    /** Layout: last write wins; `updated_at` is left for the server (the list order does not jump on a pan). */
    fun saveViewport(d: LocalData, id: CanvasId, viewport: ViewportState): Edited<Canvas> {
        val canvas = d.canvas(id) ?: return fail(d, notFound(PlacementErrorCode.CANVAS_NOT_FOUND.wire, "Canvas"))
        val moved = canvas.copy(viewportState = viewport)
        return ok(d.withCanvas(moved).enqueue(SyncOp.SaveViewport(id, viewport)), moved)
    }

    fun deleteCanvas(d: LocalData, id: CanvasId): Edited<Unit> {
        val canvas = d.canvas(id) ?: return fail(d, notFound(PlacementErrorCode.CANVAS_NOT_FOUND.wire, "Canvas"))
        return ok(d.withoutCanvas(id).enqueue(SyncOp.DeleteCanvas(id, canvas.version)), Unit)
    }

    // ---- Snippets ----

    /** The server syllabifies text sent without a grid; offline, the local estimate stands in until it syncs. */
    private fun estimatedGrid(text: String): GridData =
        GridData(text.split("\n").filter { it.isNotBlank() }.map { localSyllabifyLine(it.trim()) })

    fun createSnippet(d: LocalData, canvasId: CanvasId, body: SnippetCreateRequest): Edited<Snippet> {
        if (d.canvas(canvasId) == null) return fail(d, notFound(PlacementErrorCode.CANVAS_NOT_FOUND.wire, "Canvas"))
        val id = body.id ?: SnippetId(newId())
        val at = now()
        val text = body.rawText ?: ""
        val content = LocalSnippet(
            id = id,
            snippetType = body.snippetType ?: SnippetType.FREESTYLE,
            gridMode = body.gridMode ?: GridMode.AUTO,
            beatsPerLine = body.beatsPerLine,
            gridData = body.gridData ?: estimatedGrid(text),
            rawText = text,
            derivedFrom = body.derivedFrom,
            createdAt = at,
            updatedAt = at,
            contentUpdatedAt = at,
            version = 1,
        )
        val placement = LocalPlacement(
            id, canvasId, PlacementId(newId()), body.positionX, body.positionY,
            body.width ?: DEFAULT_SNIPPET_WIDTH, body.height ?: DEFAULT_SNIPPET_HEIGHT, 0, body.groupId,
        )
        val next = d.withSnippet(content).withPlacement(placement).enqueue(SyncOp.CreateSnippet(id, canvasId, body.copy(id = null)))
        return ok(next, checkNotNull(Views.snippet(next, id, canvasId)))
    }

    /** Content fields change the shared snippet ([SyncOp.EditSnippet]); layout fields this canvas's placement ([SyncOp.MoveSnippet]). */
    fun updateSnippet(d: LocalData, canvasId: CanvasId, id: SnippetId, body: SnippetUpdateRequest): Edited<Snippet> {
        val content = d.snippet(id) ?: return fail(d, notFound(null, "Snippet"))
        val placement = d.placement(id, canvasId) ?: return fail(d, notFound(PlacementErrorCode.PLACEMENT_NOT_FOUND.wire, "Placement"))
        var next = d
        ContentPatch.of(body)?.let { patch ->
            val at = now()
            val grid = when {
                patch.gridData != null -> patch.gridData
                patch.rawText != null -> estimatedGrid(patch.rawText)
                else -> content.gridData
            }
            val edited = content.copy(
                snippetType = patch.snippetType ?: content.snippetType,
                gridMode = patch.gridMode ?: content.gridMode,
                beatsPerLine = patch.beatsPerLine ?: content.beatsPerLine,
                gridData = grid,
                rawText = patch.rawText ?: content.rawText,
                updatedAt = at,
                contentUpdatedAt = at,
            )
            next = next.withSnippet(edited).enqueue(SyncOp.EditSnippet(id, canvasId, patch, content.version))
        }
        LayoutPatch.of(body)?.let { layout ->
            val moved = placement.copy(
                positionX = layout.positionX ?: placement.positionX,
                positionY = layout.positionY ?: placement.positionY,
                width = layout.width ?: placement.width,
                height = layout.height ?: placement.height,
                zIndex = layout.zIndex ?: placement.zIndex,
                groupId = layout.groupId ?: placement.groupId,
            )
            next = next.withPlacement(moved).enqueue(SyncOp.MoveSnippet(id, canvasId, layout))
        }
        return ok(next, checkNotNull(Views.snippet(next, id, canvasId)))
    }

    fun removeFromCanvas(d: LocalData, canvasId: CanvasId, id: SnippetId): Edited<RemovePlacementResponse> {
        val content = d.snippet(id)
        if (content == null || d.placement(id, canvasId) == null) {
            return fail(d, notFound(PlacementErrorCode.PLACEMENT_NOT_FOUND.wire, "Placement"))
        }
        val last = d.placementsOf(id).size == 1
        val next = (if (last) d.withoutSnippet(id) else d.withoutPlacement(id, canvasId))
            .enqueue(SyncOp.RemoveFromCanvas(id, canvasId, content.version))
        return ok(next, RemovePlacementResponse(snippetDeleted = last))
    }

    fun deleteEverywhere(d: LocalData, id: SnippetId): Edited<Unit> {
        val content = d.snippet(id) ?: return fail(d, notFound(null, "Snippet"))
        return ok(d.withoutSnippet(id).enqueue(SyncOp.DeleteSnippet(id, content.version)), Unit)
    }

    fun batchPosition(d: LocalData, canvasId: CanvasId, updates: List<PositionUpdate>): Edited<BatchPositionResponse> {
        var next = d
        var updated = 0
        for (u in updates) {
            val placement = next.placement(u.id, canvasId) ?: continue
            val layout = checkNotNull(LayoutPatch.of(positionX = u.positionX, positionY = u.positionY))
            next = next.withPlacement(placement.copy(positionX = u.positionX, positionY = u.positionY)).enqueue(SyncOp.MoveSnippet(u.id, canvasId, layout))
            updated++
        }
        return ok(next, BatchPositionResponse(updated))
    }

    /** The merged snippet replaces the originals on [canvasId]; an original also on another canvas stays there. */
    fun merge(d: LocalData, canvasId: CanvasId, request: MergeRequest): Edited<MergeResponse> {
        val sources = request.snippetIds.map { id ->
            val content = d.snippet(id)
            val placement = d.placement(id, canvasId)
            if (content == null || placement == null) return fail(d, notFound(null, "Snippets"))
            content to placement
        }
        val merged = LocalMerge.merge(
            sources.map { (c, p) -> MergeSource(c.id, c.rawText, c.gridData, c.snippetType, c.gridMode, c.beatsPerLine, p.groupId, p.positionX, p.positionY, c.tagIds) },
            request.mode,
        ) ?: return fail(d, ApiError.Validation(422, "Merge needs at least two distinct snippets"))
        val mergedId = SnippetId(newId())
        val at = now()
        var next = d
        for ((c, _) in sources) {
            next = if (next.placementsOf(c.id).size == 1) next.withoutSnippet(c.id) else next.withoutPlacement(c.id, canvasId)
        }
        next = next
            .withSnippet(
                LocalSnippet(
                    mergedId, merged.snippetType, merged.gridMode, merged.beatsPerLine, merged.grid, merged.rawText, merged.tagIds,
                    createdAt = at, updatedAt = at, contentUpdatedAt = at, version = 1,
                ),
            )
            .withPlacement(
                LocalPlacement(mergedId, canvasId, PlacementId(newId()), merged.positionX, merged.positionY, DEFAULT_SNIPPET_WIDTH, DEFAULT_SNIPPET_HEIGHT, 0, merged.groupId),
            )
            .enqueue(SyncOp.Merge(canvasId, mergedId, request.mode, sources.map { (c, _) -> SnippetVersion(c.id, c.version) }))
        return ok(next, MergeResponse(checkNotNull(Views.snippet(next, mergedId, canvasId)), merged.sourceIds))
    }

    /**
     * The server's transfer: new target placements keep position and size, stack above the
     * target's top card in source z order (ties by id), ungrouped; a move then removes the sources.
     */
    fun transfer(d: LocalData, sourceCanvasId: CanvasId, request: TransferRequest): Edited<TransferResponse> {
        val target = request.targetCanvasId
        if (target == sourceCanvasId) return fail(d, ApiError.Validation(422, "The target canvas is the source canvas", PlacementErrorCode.SAME_CANVAS.wire))
        if (d.canvas(sourceCanvasId) == null || d.canvas(target) == null) {
            return fail(d, notFound(PlacementErrorCode.CANVAS_NOT_FOUND.wire, "Canvas"))
        }
        val found = request.snippetIds.map { id -> d.placement(id, sourceCanvasId) ?: return fail(d, notFound(PlacementErrorCode.PLACEMENT_NOT_FOUND.wire, "Placement")) }
        val onTarget = request.snippetIds.filterTo(HashSet()) { d.placement(it, target) != null }
        val toPlace = found.filter { it.snippetId !in onTarget }
        val base = (d.placements.filter { it.canvasId == target }.maxOfOrNull { it.zIndex } ?: -1) + 1
        var next = d
        toPlace.sortedWith(compareBy({ it.zIndex }, { it.snippetId.value })).forEachIndexed { rank, p ->
            next = next.withPlacement(p.copy(canvasId = target, placementId = PlacementId(newId()), zIndex = base + rank, groupId = null))
        }
        val removed = request.mode == TransferMode.MOVE
        if (removed) request.snippetIds.forEach { next = next.withoutPlacement(it, sourceCanvasId) }
        next = next.enqueue(SyncOp.Transfer(sourceCanvasId, target, request.snippetIds, request.mode))
        val placed = request.snippetIds.filter { it !in onTarget }
        return ok(next, TransferResponse(target, placed, request.snippetIds.filter { it in onTarget }, removed))
    }

    // ---- Tags ----

    private fun nameTaken(d: LocalData, name: String, except: TagId? = null): Boolean =
        d.tags.any { it.id != except && it.name.lowercase(Locale.ROOT) == name.lowercase(Locale.ROOT) }

    private fun tagNameTaken(name: String) = ApiError.Server(409, "A tag named \"$name\" already exists", TagErrorCode.TAG_NAME_TAKEN.wire)

    fun createTag(d: LocalData, name: String, color: String?): Edited<UserTag> {
        if (nameTaken(d, name)) return fail(d, tagNameTaken(name))
        val id = TagId(newId())
        val next = d.withTag(LocalTag(id, name, color?.lowercase(Locale.ROOT) ?: DEFAULT_TAG_COLOR, now(), 1)).enqueue(SyncOp.CreateTag(id, name, color))
        return ok(next, checkNotNull(Views.tag(next, id)))
    }

    fun updateTag(d: LocalData, id: TagId, body: TagUpdateRequest): Edited<UserTag> {
        val tag = d.tag(id) ?: return fail(d, notFound(TagErrorCode.TAG_NOT_FOUND.wire, "Tag"))
        val name = body.name
        if (name != null && nameTaken(d, name, except = id)) return fail(d, tagNameTaken(name))
        if (body.name == null && body.color == null) return ok(d, checkNotNull(Views.tag(d, id)))
        val next = d.withTag(tag.copy(name = body.name ?: tag.name, color = body.color?.lowercase(Locale.ROOT) ?: tag.color))
            .enqueue(SyncOp.UpdateTag(id, body.name, body.color, tag.version))
        return ok(next, checkNotNull(Views.tag(next, id)))
    }

    fun deleteTag(d: LocalData, id: TagId): Edited<Unit> {
        val tag = d.tag(id) ?: return fail(d, notFound(TagErrorCode.TAG_NOT_FOUND.wire, "Tag"))
        return ok(d.withoutTag(id).enqueue(SyncOp.DeleteTag(id, tag.version)), Unit)
    }

    /** Link or unlink one tag on one snippet; already in that state is a success that sends nothing. */
    fun setSnippetTag(d: LocalData, snippetId: SnippetId, tagId: TagId, link: Boolean): Edited<Unit> {
        val content = d.snippet(snippetId) ?: return fail(d, notFound(null, "Snippet"))
        if (d.tag(tagId) == null) return fail(d, notFound(TagErrorCode.TAG_NOT_FOUND.wire, "Tag"))
        if ((tagId in content.tagIds) == link) return ok(d, Unit)
        val tagIds = if (link) content.tagIds + tagId else content.tagIds - tagId
        return ok(d.withSnippet(content.copy(tagIds = tagIds)).enqueue(SyncOp.SnippetTag(snippetId, tagId, link, content.version)), Unit)
    }

    // ---- Word bank ----

    private fun sameText(a: String, b: String) = a.lowercase(Locale.ROOT) == b.lowercase(Locale.ROOT)

    /**
     * Add [words] (validated by the caller), linking [tagIds] to each new and already-banked word
     * (the server's rule); a word already banked is `existing` with its stored spelling.
     */
    fun addWords(d: LocalData, words: List<String>, tagIds: List<TagId>): Edited<WordBankAddResponse> {
        if (tagIds.any { d.tag(it) == null }) return fail(d, notFound(TagErrorCode.TAG_NOT_FOUND.wire, "Tag"))
        var next = d
        val created = mutableListOf<BankWord>()
        val existing = mutableListOf<BankWord>()
        for (text in words) {
            val known: LocalWord? = next.words.firstOrNull { sameText(it.text, text) }
            if (known != null) {
                var word: LocalWord = known
                for (tag in tagIds.filter { it !in known.tagIds }) {
                    next = next.enqueue(SyncOp.WordTag(word.id, tag, TagLinkOp.LINK, word.version))
                    word = word.copy(tagIds = word.tagIds + tag)
                }
                next = next.withWord(word)
                existing += Views.word(next, word)
            } else {
                val word = LocalWord(BankWordId(newId()), text, tagIds.distinct(), now(), 1)
                next = next.withWord(word).enqueue(SyncOp.AddWord(word.id, text, word.tagIds))
                created += Views.word(next, word)
            }
        }
        return ok(next, WordBankAddResponse(created, existing))
    }

    fun renameWord(d: LocalData, id: BankWordId, text: String): Edited<BankWord> {
        val word = d.word(id) ?: return fail(d, notFound(WordBankErrorCode.WORD_NOT_FOUND.wire, "Word"))
        if (d.words.any { it.id != id && sameText(it.text, text) }) {
            return fail(d, ApiError.Server(409, "\"$text\" is already in your word bank", WordBankErrorCode.WORD_EXISTS.wire))
        }
        if (word.text == text) return ok(d, Views.word(d, word))
        val renamed = word.copy(text = text)
        val next = d.withWord(renamed).enqueue(SyncOp.RenameWord(id, text, word.version))
        return ok(next, Views.word(next, renamed))
    }

    /** Foreign or unknown ids are ignored (the server's rule). */
    fun deleteWords(d: LocalData, ids: List<BankWordId>): Edited<Unit> {
        var next = d
        for (id in ids.distinct()) {
            val word = next.word(id) ?: continue
            next = next.withoutWord(id).enqueue(SyncOp.DeleteWord(id, word.version))
        }
        return ok(next, Unit)
    }

    /** Link or unlink [tagId] on every word of [ids]; the updated words. */
    fun setWordTag(d: LocalData, ids: List<BankWordId>, tagId: TagId, op: TagLinkOp): Edited<List<BankWord>> {
        if (d.tag(tagId) == null) return fail(d, notFound(TagErrorCode.TAG_NOT_FOUND.wire, "Tag"))
        var next = d
        val out = mutableListOf<LocalWord>()
        for (id in ids.distinct()) {
            val word = next.word(id) ?: continue
            val has = tagId in word.tagIds
            val changed = when (op) {
                TagLinkOp.LINK -> if (has) word else word.copy(tagIds = word.tagIds + tagId)
                TagLinkOp.UNLINK -> if (has) word.copy(tagIds = word.tagIds - tagId) else word
            }
            if (changed != word) next = next.withWord(changed).enqueue(SyncOp.WordTag(id, tagId, op, word.version))
            out += changed
        }
        return ok(next, out.map { Views.word(next, it) })
    }
}
