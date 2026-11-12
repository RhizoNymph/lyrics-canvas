package dev.nymph.lyrics.sync.outbox

import dev.nymph.lyrics.model.BankWordId
import dev.nymph.lyrics.model.CanvasId
import dev.nymph.lyrics.model.MergeMode
import dev.nymph.lyrics.model.SnippetCreateRequest
import dev.nymph.lyrics.model.SnippetId
import dev.nymph.lyrics.model.TagId
import dev.nymph.lyrics.model.TagLinkOp
import dev.nymph.lyrics.model.TransferMode
import dev.nymph.lyrics.model.ViewportState
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Client ids of tags and words the server already had under another id (resolved creates). */
data class IdRemap(val tags: Map<TagId, TagId> = emptyMap(), val words: Map<BankWordId, BankWordId> = emptyMap()) {
    fun tag(id: TagId): TagId = tags[id] ?: id

    fun word(id: BankWordId): BankWordId = words[id] ?: id

    val isEmpty: Boolean get() = tags.isEmpty() && words.isEmpty()
}

/** A snippet id with the version an operation is based on. */
@Serializable
data class SnippetVersion(val id: SnippetId, val version: Int)

/**
 * One local change waiting for the server. Content operations carry the version the local edit
 * started from ([bases]); layout operations (moves, viewport, transfer) never conflict.
 *
 * Ordering facts, used by the scheduler: [touches] are the entities the operation changes (FIFO
 * per entity, and blocked behind a parked conflict on any of them), [creates] / [references] make
 * an operation wait for the create of something it names, [deletes] make a delete wait for every
 * earlier operation that names what it deletes.
 */
@Serializable
sealed interface SyncOp {
    val touches: Set<EntityRef>
    val references: Set<EntityRef> get() = emptySet()
    val creates: EntityRef? get() = null
    val deletes: Set<EntityRef> get() = emptySet()

    /** The base version per entity this operation sends (content operations only). */
    val bases: Map<EntityRef, Int> get() = emptyMap()

    /** The same operation based on [version] of [entity] (after an earlier operation on it succeeded). */
    fun rebased(entity: EntityRef, version: Int): SyncOp = this

    /** The same operation with tag / word ids resolved to the server's. */
    fun remapped(remap: IdRemap): SyncOp = this

    // ---- Canvases ----

    @Serializable
    @SerialName("create_canvas")
    data class CreateCanvas(val id: CanvasId, val title: String) : SyncOp {
        override val touches: Set<EntityRef> get() = setOf(EntityRef.Canvas(id))
        override val creates: EntityRef get() = EntityRef.Canvas(id)
    }

    @Serializable
    @SerialName("rename_canvas")
    data class RenameCanvas(val id: CanvasId, val title: String, val baseVersion: Int) : SyncOp {
        override val touches: Set<EntityRef> get() = setOf(EntityRef.Canvas(id))
        override val bases: Map<EntityRef, Int> get() = mapOf(EntityRef.Canvas(id) to baseVersion)
        override fun rebased(entity: EntityRef, version: Int) = if (entity == EntityRef.Canvas(id)) copy(baseVersion = version) else this
    }

    @Serializable
    @SerialName("save_viewport")
    data class SaveViewport(val id: CanvasId, val viewport: ViewportState) : SyncOp {
        override val touches: Set<EntityRef> get() = setOf(EntityRef.Canvas(id))
    }

    @Serializable
    @SerialName("delete_canvas")
    data class DeleteCanvas(val id: CanvasId, val baseVersion: Int) : SyncOp {
        override val touches: Set<EntityRef> get() = setOf(EntityRef.Canvas(id))
        override val deletes: Set<EntityRef> get() = touches
        override val bases: Map<EntityRef, Int> get() = mapOf(EntityRef.Canvas(id) to baseVersion)
        override fun rebased(entity: EntityRef, version: Int) = if (entity == EntityRef.Canvas(id)) copy(baseVersion = version) else this
    }

    // ---- Snippets ----

    /** [body] is sent with [id] as the client-chosen id. */
    @Serializable
    @SerialName("create_snippet")
    data class CreateSnippet(val id: SnippetId, val canvasId: CanvasId, val body: SnippetCreateRequest) : SyncOp {
        override val touches: Set<EntityRef> get() = setOf(EntityRef.Snippet(id))
        override val creates: EntityRef get() = EntityRef.Snippet(id)
        override val references: Set<EntityRef> get() = setOf(EntityRef.Canvas(canvasId))
    }

    /** A content PUT; [canvasId] is only the response's context (a canvas the snippet is on). */
    @Serializable
    @SerialName("edit_snippet")
    data class EditSnippet(val id: SnippetId, val canvasId: CanvasId, val patch: ContentPatch, val baseVersion: Int) : SyncOp {
        override val touches: Set<EntityRef> get() = setOf(EntityRef.Snippet(id))
        override val references: Set<EntityRef> get() = setOf(EntityRef.Canvas(canvasId))
        override val bases: Map<EntityRef, Int> get() = mapOf(EntityRef.Snippet(id) to baseVersion)
        override fun rebased(entity: EntityRef, version: Int) = if (entity == EntityRef.Snippet(id)) copy(baseVersion = version) else this
    }

    /** A layout-only PUT on one placement (last write wins). */
    @Serializable
    @SerialName("move_snippet")
    data class MoveSnippet(val id: SnippetId, val canvasId: CanvasId, val layout: LayoutPatch) : SyncOp {
        override val touches: Set<EntityRef> get() = setOf(EntityRef.Snippet(id))
        override val references: Set<EntityRef> get() = setOf(EntityRef.Canvas(canvasId))
    }

    /** Remove from one canvas; the server checks [baseVersion] only when it is the last placement. */
    @Serializable
    @SerialName("remove_from_canvas")
    data class RemoveFromCanvas(val id: SnippetId, val canvasId: CanvasId, val baseVersion: Int) : SyncOp {
        override val touches: Set<EntityRef> get() = setOf(EntityRef.Snippet(id))
        override val references: Set<EntityRef> get() = setOf(EntityRef.Canvas(canvasId))
        override val bases: Map<EntityRef, Int> get() = mapOf(EntityRef.Snippet(id) to baseVersion)
        override fun rebased(entity: EntityRef, version: Int) = if (entity == EntityRef.Snippet(id)) copy(baseVersion = version) else this
    }

    @Serializable
    @SerialName("delete_snippet")
    data class DeleteSnippet(val id: SnippetId, val baseVersion: Int) : SyncOp {
        override val touches: Set<EntityRef> get() = setOf(EntityRef.Snippet(id))
        override val deletes: Set<EntityRef> get() = touches
        override val bases: Map<EntityRef, Int> get() = mapOf(EntityRef.Snippet(id) to baseVersion)
        override fun rebased(entity: EntityRef, version: Int) = if (entity == EntityRef.Snippet(id)) copy(baseVersion = version) else this
    }

    /** Link ([link] true) or unlink a tag: snippet content, checked against the snippet's version. */
    @Serializable
    @SerialName("snippet_tag")
    data class SnippetTag(val snippetId: SnippetId, val tagId: TagId, val link: Boolean, val baseVersion: Int) : SyncOp {
        override val touches: Set<EntityRef> get() = setOf(EntityRef.Snippet(snippetId))
        override val references: Set<EntityRef> get() = setOf(EntityRef.Tag(tagId))
        override val bases: Map<EntityRef, Int> get() = mapOf(EntityRef.Snippet(snippetId) to baseVersion)
        override fun rebased(entity: EntityRef, version: Int) = if (entity == EntityRef.Snippet(snippetId)) copy(baseVersion = version) else this
        override fun remapped(remap: IdRemap) = copy(tagId = remap.tag(tagId))
    }

    /** Server-side merge into [mergedId] (client id); [sources] carry their base versions. */
    @Serializable
    @SerialName("merge")
    data class Merge(val canvasId: CanvasId, val mergedId: SnippetId, val mode: MergeMode, val sources: List<SnippetVersion>) : SyncOp {
        override val touches: Set<EntityRef> get() = sources.mapTo(LinkedHashSet()) { EntityRef.Snippet(it.id) } + EntityRef.Snippet(mergedId)
        override val creates: EntityRef get() = EntityRef.Snippet(mergedId)
        override val references: Set<EntityRef> get() = setOf(EntityRef.Canvas(canvasId))
        override val deletes: Set<EntityRef> get() = sources.mapTo(LinkedHashSet()) { EntityRef.Snippet(it.id) }
        override val bases: Map<EntityRef, Int> get() = sources.associate { EntityRef.Snippet(it.id) to it.version }
        override fun rebased(entity: EntityRef, version: Int) =
            copy(sources = sources.map { if (entity == EntityRef.Snippet(it.id)) it.copy(version = version) else it })
    }

    /** Move to / also show on another canvas: layout only. */
    @Serializable
    @SerialName("transfer")
    data class Transfer(val sourceCanvasId: CanvasId, val targetCanvasId: CanvasId, val snippetIds: List<SnippetId>, val mode: TransferMode) : SyncOp {
        override val touches: Set<EntityRef> get() = snippetIds.mapTo(LinkedHashSet()) { EntityRef.Snippet(it) }
        override val references: Set<EntityRef> get() = setOf(EntityRef.Canvas(sourceCanvasId), EntityRef.Canvas(targetCanvasId))
    }

    // ---- Tags ----

    @Serializable
    @SerialName("create_tag")
    data class CreateTag(val id: TagId, val name: String, val color: String? = null) : SyncOp {
        override val touches: Set<EntityRef> get() = setOf(EntityRef.Tag(id))
        override val creates: EntityRef get() = EntityRef.Tag(id)
    }

    @Serializable
    @SerialName("update_tag")
    data class UpdateTag(val id: TagId, val name: String? = null, val color: String? = null, val baseVersion: Int) : SyncOp {
        override val touches: Set<EntityRef> get() = setOf(EntityRef.Tag(id))
        override val bases: Map<EntityRef, Int> get() = mapOf(EntityRef.Tag(id) to baseVersion)
        override fun rebased(entity: EntityRef, version: Int) = if (entity == EntityRef.Tag(id)) copy(baseVersion = version) else this
        override fun remapped(remap: IdRemap) = copy(id = remap.tag(id))
    }

    @Serializable
    @SerialName("delete_tag")
    data class DeleteTag(val id: TagId, val baseVersion: Int) : SyncOp {
        override val touches: Set<EntityRef> get() = setOf(EntityRef.Tag(id))
        override val deletes: Set<EntityRef> get() = touches
        override val bases: Map<EntityRef, Int> get() = mapOf(EntityRef.Tag(id) to baseVersion)
        override fun rebased(entity: EntityRef, version: Int) = if (entity == EntityRef.Tag(id)) copy(baseVersion = version) else this
        override fun remapped(remap: IdRemap) = copy(id = remap.tag(id))
    }

    // ---- Word bank (one operation per word; the sender batches them) ----

    @Serializable
    @SerialName("add_word")
    data class AddWord(val id: BankWordId, val text: String, val tagIds: List<TagId> = emptyList()) : SyncOp {
        override val touches: Set<EntityRef> get() = setOf(EntityRef.Word(id))
        override val creates: EntityRef get() = EntityRef.Word(id)
        override val references: Set<EntityRef> get() = tagIds.mapTo(LinkedHashSet()) { EntityRef.Tag(it) }
        override fun remapped(remap: IdRemap) = copy(tagIds = tagIds.map(remap::tag).distinct())
    }

    @Serializable
    @SerialName("rename_word")
    data class RenameWord(val id: BankWordId, val text: String, val baseVersion: Int) : SyncOp {
        override val touches: Set<EntityRef> get() = setOf(EntityRef.Word(id))
        override val bases: Map<EntityRef, Int> get() = mapOf(EntityRef.Word(id) to baseVersion)
        override fun rebased(entity: EntityRef, version: Int) = if (entity == EntityRef.Word(id)) copy(baseVersion = version) else this
        override fun remapped(remap: IdRemap) = copy(id = remap.word(id))
    }

    @Serializable
    @SerialName("delete_word")
    data class DeleteWord(val id: BankWordId, val baseVersion: Int) : SyncOp {
        override val touches: Set<EntityRef> get() = setOf(EntityRef.Word(id))
        override val deletes: Set<EntityRef> get() = touches
        override val bases: Map<EntityRef, Int> get() = mapOf(EntityRef.Word(id) to baseVersion)
        override fun rebased(entity: EntityRef, version: Int) = if (entity == EntityRef.Word(id)) copy(baseVersion = version) else this
        override fun remapped(remap: IdRemap) = copy(id = remap.word(id))
    }

    @Serializable
    @SerialName("word_tag")
    data class WordTag(val id: BankWordId, val tagId: TagId, val op: TagLinkOp, val baseVersion: Int) : SyncOp {
        override val touches: Set<EntityRef> get() = setOf(EntityRef.Word(id))
        override val references: Set<EntityRef> get() = setOf(EntityRef.Tag(tagId))
        override val bases: Map<EntityRef, Int> get() = mapOf(EntityRef.Word(id) to baseVersion)
        override fun rebased(entity: EntityRef, version: Int) = if (entity == EntityRef.Word(id)) copy(baseVersion = version) else this
        override fun remapped(remap: IdRemap) = copy(id = remap.word(id), tagId = remap.tag(tagId))
    }
}
