package dev.nymph.lyrics.sync.conflict

import dev.nymph.lyrics.model.PlacementId
import dev.nymph.lyrics.model.SnippetCreateRequest
import dev.nymph.lyrics.model.SnippetId
import dev.nymph.lyrics.model.TagLinkOp
import dev.nymph.lyrics.sync.outbox.ContentPatch
import dev.nymph.lyrics.sync.outbox.EntityRef
import dev.nymph.lyrics.sync.outbox.OpId
import dev.nymph.lyrics.sync.outbox.OpState
import dev.nymph.lyrics.sync.outbox.OutboxEntry
import dev.nymph.lyrics.sync.outbox.SyncOp
import dev.nymph.lyrics.sync.store.LocalData
import dev.nymph.lyrics.sync.store.LocalPlacement
import dev.nymph.lyrics.sync.store.LocalSnippet
import dev.nymph.lyrics.sync.store.LocalTag
import dev.nymph.lyrics.sync.store.LocalWord

/** Horizontal gap between a snippet and its "Save mine as new" copy. */
const val SAVE_AS_NEW_GAP: Double = 40.0

/** Why a resolution could not be applied. */
enum class ResolveError {
    /** The change is no longer parked (resolved elsewhere, or it synced). */
    NOT_PARKED,

    /** The review does not offer that choice for this conflict ([resolutionsFor]). */
    NOT_OFFERED,

    /** "Save mine as new" with no local copy left to save. */
    NOTHING_TO_SAVE,
}

sealed interface Resolved {
    /** [refresh]: fetch a snapshot so the server's state shows (e.g. what a dropped delete removed). */
    data class Done(val data: LocalData, val refresh: Boolean) : Resolved

    data class Refused(val error: ResolveError) : Resolved
}

/**
 * The review's choices, applied to the local copy and the outbox (pure). Nothing is discarded
 * except what the user chose to discard: a dropped create takes the operations that depended on
 * it along (they could never be sent), and later layout changes survive "Keep server's" on content.
 */
class Resolver(private val newId: () -> String, private val now: () -> String) {
    fun resolve(d: LocalData, opId: OpId, resolution: Resolution): Resolved {
        val entry = d.outbox[opId] ?: return Resolved.Refused(ResolveError.NOT_PARKED)
        val conflict = (entry.state as? OpState.Parked)?.conflict ?: return Resolved.Refused(ResolveError.NOT_PARKED)
        if (resolution !in resolutionsFor(conflict)) return Resolved.Refused(ResolveError.NOT_OFFERED)
        return when (resolution) {
            Resolution.KEEP_SERVERS -> Resolved.Done(keepServers(d, entry, conflict), refresh = true)
            Resolution.OVERWRITE_WITH_MINE -> overwrite(d, entry, conflict as Conflict.Changed)
            Resolution.SAVE_MINE_AS_NEW -> saveAsNew(d, entry, conflict)
            Resolution.RETRY_MERGE -> retryMerge(d, entry, conflict as Conflict.MergeChanged)
        }
    }

    // ---- Keep server's ----

    private fun keepServers(d: LocalData, entry: OutboxEntry, conflict: Conflict): LocalData {
        val entities = entry.op.touches
        val dropAll = conflict is Conflict.DeletedOnServer || conflict is Conflict.MergeChanged || entry.op.creates != null
        val entries = d.outbox.entries
        val start = entries.indexOfFirst { it.id == entry.id }
        val drop = LinkedHashSet<OpId>().apply { add(entry.id) }
        val created = HashSet<EntityRef>().apply { entry.op.creates?.let(::add) }
        for (later in entries.drop(start + 1)) {
            val sameEntity = later.op.touches.any { it in entities } && (dropAll || later.op.bases.isNotEmpty())
            val dependsOnDropped = later.op.touches.any { it in created } || later.op.references.any { it in created }
            if (sameEntity || dependsOnDropped) {
                drop += later.id
                later.op.creates?.let(created::add)
            }
        }
        var next = d.withOutbox(d.outbox.drop(drop))
        for (ref in created) next = without(next, ref)
        next = when (conflict) {
            is Conflict.Changed -> withServerCopy(next, conflict.server)
            is Conflict.DeletedOnServer -> without(next, conflict.entity)
            is Conflict.MergeChanged -> conflict.changed.fold(next) { acc, s -> withServerCopy(acc, ServerCopy.OfSnippet(s)) }
            is Conflict.Rejected -> next
        }
        return next
    }

    private fun without(d: LocalData, ref: EntityRef): LocalData = when (ref) {
        is EntityRef.Snippet -> d.withoutSnippet(ref.id)
        is EntityRef.Canvas -> d.withoutCanvas(ref.id)
        is EntityRef.Tag -> d.withoutTag(ref.id)
        is EntityRef.Word -> d.withoutWord(ref.id)
    }

    /** The server's copy replaces the local one (restored when a local delete is discarded); layout stays local. */
    private fun withServerCopy(d: LocalData, copy: ServerCopy): LocalData = when (copy) {
        is ServerCopy.OfSnippet -> {
            val s = copy.snippet
            val next = d.withSnippet(LocalSnippet.of(s))
            if (d.placementsOf(s.id).isEmpty()) next.withPlacement(LocalPlacement.of(s)) else next
        }
        is ServerCopy.OfCanvas -> d.withCanvas(d.canvas(copy.canvas.id)?.let { copy.canvas.copy(viewportState = it.viewportState) } ?: copy.canvas)
        is ServerCopy.OfTag -> d.withTag(LocalTag.of(copy.tag))
        is ServerCopy.OfWord -> d.withWord(LocalWord.of(copy.word))
    }

    // ---- Overwrite with mine ----

    private fun overwrite(d: LocalData, entry: OutboxEntry, conflict: Conflict.Changed): Resolved {
        val entity = conflict.entity
        val version = conflict.server.version
        val ops = when (val op = entry.op) {
            is SyncOp.DeleteSnippet, is SyncOp.RemoveFromCanvas, is SyncOp.DeleteCanvas, is SyncOp.DeleteTag, is SyncOp.DeleteWord ->
                listOf(op.rebased(entity, version))
            else -> mineAsOps(d, entry.op, conflict.server) ?: return Resolved.Refused(ResolveError.NOTHING_TO_SAVE)
        }
        val start = d.outbox.entries.indexOfFirst { it.id == entry.id }
        val replaced = d.outbox.entries.drop(start + 1).filter { e -> entity in e.op.touches && e.op.bases.isNotEmpty() }.mapTo(HashSet()) { it.id }
        val outbox = d.outbox.insertAfter(entry.id, ops).drop(replaced + entry.id)
        return Resolved.Done(withLocalVersion(d.withOutbox(outbox), entity, version), refresh = false)
    }

    /** The whole local version as operations based on the server's [copy]: content, plus tag diffs. */
    private fun mineAsOps(d: LocalData, op: SyncOp, copy: ServerCopy): List<SyncOp>? {
        val v = copy.version
        return when (copy) {
            is ServerCopy.OfSnippet -> {
                val mine = d.snippet(copy.snippet.id) ?: return null
                val canvas = (op as? SyncOp.EditSnippet)?.canvasId?.takeIf { d.placement(mine.id, it) != null }
                    ?: d.placementsOf(mine.id).firstOrNull()?.canvasId ?: copy.snippet.canvasId
                val patch = checkNotNull(ContentPatch.of(mine.snippetType, mine.gridMode, mine.beatsPerLine, mine.gridData, mine.rawText))
                val server = copy.snippet.tags.mapTo(HashSet()) { it.id }
                listOf(SyncOp.EditSnippet(mine.id, canvas, patch, v)) +
                    mine.tagIds.filter { it !in server }.map { SyncOp.SnippetTag(mine.id, it, true, v) } +
                    server.filter { it !in mine.tagIds }.map { SyncOp.SnippetTag(mine.id, it, false, v) }
            }
            is ServerCopy.OfCanvas -> listOf(SyncOp.RenameCanvas(copy.canvas.id, (d.canvas(copy.canvas.id) ?: return null).title, v))
            is ServerCopy.OfTag -> (d.tag(copy.tag.id) ?: return null).let { listOf(SyncOp.UpdateTag(it.id, it.name, it.color, v)) }
            is ServerCopy.OfWord -> {
                val mine = d.word(copy.word.id) ?: return null
                val server = copy.word.tags.mapTo(HashSet()) { it.id }
                listOfNotNull(SyncOp.RenameWord(mine.id, mine.text, v).takeIf { mine.text != copy.word.text }) +
                    mine.tagIds.filter { it !in server }.map { SyncOp.WordTag(mine.id, it, TagLinkOp.LINK, v) } +
                    server.filter { it !in mine.tagIds }.map { SyncOp.WordTag(mine.id, it, TagLinkOp.UNLINK, v) }
            }
        }
    }

    private fun withLocalVersion(d: LocalData, entity: EntityRef, version: Int): LocalData = when (entity) {
        is EntityRef.Snippet -> d.snippet(entity.id)?.let { d.withSnippet(it.copy(version = version)) } ?: d
        is EntityRef.Canvas -> d.canvas(entity.id)?.let { d.withCanvas(it.copy(version = version)) } ?: d
        is EntityRef.Tag -> d.tag(entity.id)?.let { d.withTag(it.copy(version = version)) } ?: d
        is EntityRef.Word -> d.word(entity.id)?.let { d.withWord(it.copy(version = version)) } ?: d
    }

    // ---- Save mine as new (snippets) ----

    private fun saveAsNew(d: LocalData, entry: OutboxEntry, conflict: Conflict): Resolved {
        val ref = conflict.entity as? EntityRef.Snippet ?: return Resolved.Refused(ResolveError.NOT_OFFERED)
        val mine = d.snippet(ref.id) ?: return Resolved.Refused(ResolveError.NOTHING_TO_SAVE)
        val opCanvas = when (val op = entry.op) {
            is SyncOp.EditSnippet -> op.canvasId
            is SyncOp.MoveSnippet -> op.canvasId
            else -> null
        }
        val placement = opCanvas?.let { d.placement(mine.id, it) } ?: d.placementsOf(mine.id).firstOrNull()
            ?: return Resolved.Refused(ResolveError.NOTHING_TO_SAVE)
        val id = SnippetId(newId())
        val at = now()
        val x = placement.positionX + placement.width + SAVE_AS_NEW_GAP
        val y = placement.positionY
        val copy = mine.copy(id = id, createdAt = at, updatedAt = at, contentUpdatedAt = at, version = 1, phonetics = null)
        val newPlacement = LocalPlacement(id, placement.canvasId, PlacementId(newId()), x, y, placement.width, placement.height, placement.zIndex, null)
        val body = SnippetCreateRequest(
            positionX = x,
            positionY = y,
            snippetType = mine.snippetType,
            rawText = mine.rawText,
            gridMode = mine.gridMode,
            beatsPerLine = mine.beatsPerLine,
            gridData = mine.gridData,
            width = placement.width,
            height = placement.height,
        )
        val ops = listOf(SyncOp.CreateSnippet(id, placement.canvasId, body)) + mine.tagIds.map { SyncOp.SnippetTag(id, it, true, 1) }
        val withCopy = d.withSnippet(copy).withPlacement(newPlacement).let { it.withOutbox(it.outbox.insertAfter(entry.id, ops)) }
        return Resolved.Done(keepServers(withCopy, entry, conflict), refresh = true)
    }

    // ---- Retry merge ----

    private fun retryMerge(d: LocalData, entry: OutboxEntry, conflict: Conflict.MergeChanged): Resolved {
        val op = entry.op as? SyncOp.Merge ?: return Resolved.Refused(ResolveError.NOT_OFFERED)
        val rebased = conflict.changed.fold(op as SyncOp) { acc, s -> acc.rebased(EntityRef.Snippet(s.id), s.version) }
        return Resolved.Done(d.withOutbox(d.outbox.replace(entry.id, rebased, OpState.Pending)), refresh = false)
    }
}
