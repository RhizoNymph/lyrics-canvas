package dev.nymph.lyrics.sync.engine

import dev.nymph.lyrics.model.BankWord
import dev.nymph.lyrics.model.BankWordId
import dev.nymph.lyrics.model.Canvas
import dev.nymph.lyrics.model.LyricsJson
import dev.nymph.lyrics.model.Snippet
import dev.nymph.lyrics.model.TagErrorCode
import dev.nymph.lyrics.model.TagId
import dev.nymph.lyrics.model.UserTag
import dev.nymph.lyrics.sync.conflict.Conflict
import dev.nymph.lyrics.sync.conflict.ConflictAlert
import dev.nymph.lyrics.sync.conflict.ServerCopy
import dev.nymph.lyrics.sync.conflict.labelOf
import dev.nymph.lyrics.sync.outbox.Batch
import dev.nymph.lyrics.sync.outbox.EntityRef
import dev.nymph.lyrics.sync.outbox.IdRemap
import dev.nymph.lyrics.sync.outbox.OutboxEntry
import dev.nymph.lyrics.sync.outbox.SyncOp
import dev.nymph.lyrics.sync.store.LocalData
import dev.nymph.lyrics.sync.store.LocalPlacement
import dev.nymph.lyrics.sync.store.LocalSnippet
import dev.nymph.lyrics.sync.store.LocalTag
import dev.nymph.lyrics.sync.store.LocalWord
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import java.util.Locale

/**
 * The store after one batch's outcome. [alerts] are the newly parked changes (raised once);
 * [refresh] asks for a snapshot (the result is only known server-side); [transient] stops the pass.
 */
data class Applied(
    val data: LocalData,
    val alerts: List<ConflictAlert> = emptyList(),
    val refresh: Boolean = false,
    val transient: Boolean = false,
)

/**
 * How a server answer updates the local copy and the outbox (pure). A response replaces the local
 * copy of an entity only when no other operation on it is still queued; otherwise the local
 * overlay stays and only the version moves, and every queued operation based on it is rebased.
 */
object Outcomes {
    fun apply(d: LocalData, batch: Batch, outcome: SendOutcome): Applied = when (outcome) {
        is SendOutcome.Ok -> when (val result = outcome.result) {
            is OpResult.WordsAdded -> applyWordsAdded(d, batch, result)
            else -> Applied(succeeded(d.withOutbox(d.outbox.complete(batch.ids)), result, batch.first.op), refresh = result is OpResult.Transferred)
        }
        is SendOutcome.Transient -> Applied(d.withOutbox(d.outbox.fail(batch.ids, outcome.error.message)), transient = true)
        is SendOutcome.Unreadable -> Applied(d.withOutbox(d.outbox.complete(batch.ids)), refresh = true)
        is SendOutcome.VersionConflict -> versionConflict(d, batch, outcome.current)
        is SendOutcome.NotFound -> notFound(d, batch, outcome.code)
        is SendOutcome.Refused -> park(d, batch.entries.map { it to Conflict.Rejected(entityOf(it.op), outcome.error.message) })
    }

    /** The entity a parked operation's badge and review go on. */
    fun entityOf(op: SyncOp): EntityRef = when (op) {
        is SyncOp.Merge -> EntityRef.Snippet(op.mergedId)
        else -> op.touches.first()
    }

    // ---- Success ----

    /**
     * Whether [op]'s answer may move the base of what is still queued on [entity]: only when [op]
     * was based on it (a content change, now the server's state) or created it. A layout answer
     * must not: its version may include someone else's content change, which a rebase would hide.
     */
    private fun rebases(op: SyncOp, entity: EntityRef): Boolean = entity in op.bases || op.creates == entity

    private fun succeeded(d: LocalData, result: OpResult, op: SyncOp): LocalData = when (result) {
        is OpResult.SnippetSaved -> absorbSnippet(d, result.snippet, rebases(op, EntityRef.Snippet(result.snippet.id)))
        is OpResult.SnippetVersioned -> result.version?.let { v -> withVersion(d, EntityRef.Snippet(result.id), v) } ?: d
        is OpResult.CanvasSaved -> absorbCanvas(d, result.canvas, rebases(op, EntityRef.Canvas(result.canvas.id)))
        is OpResult.TagSaved -> absorbTag(d, result.tag)
        is OpResult.TagMatched -> remapTag(d, result.local, result.server)
        is OpResult.WordsSaved -> result.words.fold(d) { acc, w -> absorbWord(acc, w) }
        is OpResult.WordsAdded -> result.response.created.fold(d) { acc, w -> absorbWord(acc, w) }
        is OpResult.Merged -> absorbSnippet(d, result.response.snippet)
        is OpResult.Transferred, OpResult.Done -> d
    }

    /** Word adds need the batch to match `existing` words (another id on the server) to the local ones. */
    fun applyWordsAdded(d: LocalData, batch: Batch, result: OpResult.WordsAdded): Applied {
        var next = d.withOutbox(d.outbox.complete(batch.ids))
        next = result.response.created.fold(next) { acc, w -> absorbWord(acc, w) }
        val remap = HashMap<BankWordId, BankWordId>()
        for (entry in batch.entries) {
            val op = entry.op as? SyncOp.AddWord ?: continue
            if (result.response.created.any { it.id == op.id }) continue
            val server = result.response.existing.firstOrNull { it.text.lowercase(Locale.ROOT) == op.text.lowercase(Locale.ROOT) } ?: continue
            if (server.id == op.id) continue
            remap[op.id] = server.id
            next = next.withoutWord(op.id).withWord(LocalWord.of(server))
        }
        if (remap.isNotEmpty()) next = next.withOutbox(next.outbox.remap(IdRemap(words = remap)))
        return Applied(next)
    }

    private fun withVersion(d: LocalData, entity: EntityRef, version: Int): LocalData {
        val next = when (entity) {
            is EntityRef.Snippet -> d.snippet(entity.id)?.let { d.withSnippet(it.copy(version = version)) } ?: d
            is EntityRef.Canvas -> d.canvas(entity.id)?.let { d.withCanvas(it.copy(version = version)) } ?: d
            is EntityRef.Tag -> d.tag(entity.id)?.let { d.withTag(it.copy(version = version)) } ?: d
            is EntityRef.Word -> d.word(entity.id)?.let { d.withWord(it.copy(version = version)) } ?: d
        }
        return next.withOutbox(next.outbox.rebase(entity, version))
    }

    /**
     * The server's copy, unless something is still queued on it: then only the version moves (and
     * the queue is rebased) when [rebase] says the answer is to a change based on it. Never
     * re-creates something deleted locally meanwhile.
     */
    fun absorbSnippet(d: LocalData, s: Snippet, rebase: Boolean = true): LocalData {
        if (d.snippet(s.id) == null) return d
        val entity = EntityRef.Snippet(s.id)
        if (d.outbox.hasOpsOn(entity)) return if (rebase) withVersion(d, entity, s.version) else d
        val next = d.withSnippet(LocalSnippet.of(s))
        return if (next.placement(s.id, s.canvasId) != null) next.withPlacement(LocalPlacement.of(s)) else next
    }

    fun absorbCanvas(d: LocalData, c: Canvas, rebase: Boolean = true): LocalData {
        if (d.canvas(c.id) == null) return d
        val entity = EntityRef.Canvas(c.id)
        return if (!d.outbox.hasOpsOn(entity)) d.withCanvas(c) else if (rebase) withVersion(d, entity, c.version) else d
    }

    fun absorbTag(d: LocalData, t: UserTag): LocalData {
        if (d.tag(t.id) == null) return d
        val entity = EntityRef.Tag(t.id)
        return if (d.outbox.hasOpsOn(entity)) withVersion(d, entity, t.version) else d.withTag(LocalTag.of(t))
    }

    fun absorbWord(d: LocalData, w: BankWord): LocalData {
        if (d.word(w.id) == null) return d
        val entity = EntityRef.Word(w.id)
        return if (d.outbox.hasOpsOn(entity)) withVersion(d, entity, w.version) else d.withWord(LocalWord.of(w))
    }

    /** The local tag [local] is the server's [server] (same name): one tag everywhere, operations rewritten. */
    fun remapTag(d: LocalData, local: TagId, server: UserTag): LocalData {
        fun List<TagId>.swap() = map { if (it == local) server.id else it }.distinct()
        val next = d.copy(
            tags = d.tags.filterNot { it.id == local || it.id == server.id } + LocalTag.of(server),
            snippets = d.snippets.map { s -> if (local in s.tagIds) s.copy(tagIds = s.tagIds.swap()) else s },
            words = d.words.map { w -> if (local in w.tagIds) w.copy(tagIds = w.tagIds.swap()) else w },
        )
        return next.withOutbox(next.outbox.remap(IdRemap(tags = mapOf(local to server.id))))
    }

    // ---- Conflicts ----

    private fun park(d: LocalData, parked: List<Pair<OutboxEntry, Conflict>>): Applied {
        var outbox = d.outbox
        for ((entry, conflict) in parked) outbox = outbox.park(entry.id, conflict)
        val next = d.withOutbox(outbox)
        return Applied(next, alerts = parked.map { (entry, conflict) -> ConflictAlert(entry.id, conflict, labelOf(next, conflict.entity)) })
    }

    private fun <T> decode(strategy: DeserializationStrategy<T>, element: JsonElement?): T? {
        if (element == null) return null
        return try {
            LyricsJson.decodeFromJsonElement(strategy, element)
        } catch (e: SerializationException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    private fun copyOf(entity: EntityRef, current: JsonElement?): ServerCopy? = when (entity) {
        is EntityRef.Snippet -> decode(Snippet.serializer(), current)?.let(ServerCopy::OfSnippet)
        is EntityRef.Canvas -> decode(Canvas.serializer(), current)?.let(ServerCopy::OfCanvas)
        is EntityRef.Tag -> decode(UserTag.serializer(), current)?.let(ServerCopy::OfTag)
        is EntityRef.Word -> decode(BankWord.serializer(), current)?.let(ServerCopy::OfWord)
    }

    private fun versionConflict(d: LocalData, batch: Batch, current: JsonElement?): Applied {
        val op = batch.first.op
        return when (op) {
            is SyncOp.Merge -> park(
                d,
                listOf(batch.first to Conflict.MergeChanged(EntityRef.Snippet(op.mergedId), changed = decode(ListSerializer(Snippet.serializer()), current).orEmpty())),
            )
            is SyncOp.DeleteWord, is SyncOp.WordTag, is SyncOp.AddWord -> wordConflict(d, batch, current)
            else -> {
                val entity = entityOf(op)
                val copy = copyOf(entity, current)
                when {
                    copy == null -> park(d, listOf(batch.first to Conflict.Rejected(entity, "it was also changed on the server")))
                    alreadyApplied(op, copy) -> Applied(succeeded(d.withOutbox(d.outbox.complete(batch.ids)), resultOf(copy), op))
                    else -> park(d, listOf(batch.first to Conflict.Changed(entity, copy)))
                }
            }
        }
    }

    /** All or nothing on the server: the words in `current` are parked, the rest go again. */
    private fun wordConflict(d: LocalData, batch: Batch, current: JsonElement?): Applied {
        val words = decode(ListSerializer(BankWord.serializer()), current)
            ?: return park(d, batch.entries.map { it to Conflict.Rejected(entityOf(it.op), "it was also changed on the server") })
        val byId = words.associateBy { it.id }
        val conflicting = batch.entries.mapNotNull { e -> (entityOf(e.op) as? EntityRef.Word)?.let { ref -> byId[ref.id]?.let { e to it } } }
        val parkedIds = conflicting.mapTo(HashSet()) { it.first.id }
        val requeued = d.withOutbox(d.outbox.requeue(batch.ids - parkedIds))
        return park(requeued, conflicting.map { (e, w) -> e to Conflict.Changed(EntityRef.Word(w.id), ServerCopy.OfWord(w)) })
    }

    /** A retry of a change the server applied while its answer was lost conflicts with itself: no conflict. */
    private fun alreadyApplied(op: SyncOp, copy: ServerCopy): Boolean = when {
        op is SyncOp.EditSnippet && copy is ServerCopy.OfSnippet -> copy.snippet.let { s ->
            val p = op.patch
            (p.rawText == null || p.rawText == s.rawText) && (p.snippetType == null || p.snippetType == s.snippetType) &&
                (p.gridMode == null || p.gridMode == s.gridMode) && (p.beatsPerLine == null || p.beatsPerLine == s.beatsPerLine) &&
                (p.gridData == null || p.gridData == s.gridData)
        }
        op is SyncOp.SnippetTag && copy is ServerCopy.OfSnippet -> copy.snippet.tags.any { it.id == op.tagId } == op.link
        op is SyncOp.RenameCanvas && copy is ServerCopy.OfCanvas -> copy.canvas.title == op.title
        op is SyncOp.UpdateTag && copy is ServerCopy.OfTag ->
            (op.name == null || op.name == copy.tag.name) && (op.color == null || op.color.equals(copy.tag.color, ignoreCase = true))
        op is SyncOp.RenameWord && copy is ServerCopy.OfWord -> copy.word.text == op.text
        else -> false
    }

    private fun resultOf(copy: ServerCopy): OpResult = when (copy) {
        is ServerCopy.OfSnippet -> OpResult.SnippetSaved(copy.snippet)
        is ServerCopy.OfCanvas -> OpResult.CanvasSaved(copy.canvas)
        is ServerCopy.OfTag -> OpResult.TagSaved(copy.tag)
        is ServerCopy.OfWord -> OpResult.WordsSaved(listOf(copy.word))
    }

    // ---- 404 ----

    private fun notFound(d: LocalData, batch: Batch, code: String?): Applied {
        val done = { Applied(d.withOutbox(d.outbox.complete(batch.ids))) }
        val tagGone = TagErrorCode.parse(code) == TagErrorCode.TAG_NOT_FOUND
        return when (val op = batch.first.op) {
            is SyncOp.DeleteCanvas, is SyncOp.DeleteSnippet, is SyncOp.RemoveFromCanvas, is SyncOp.DeleteTag, is SyncOp.DeleteWord -> done()
            is SyncOp.SnippetTag -> when {
                tagGone -> Applied(d.withOutbox(d.outbox.complete(batch.ids)).withoutTag(op.tagId), refresh = true)
                !op.link -> done()
                else -> park(d, listOf(batch.first to Conflict.DeletedOnServer(EntityRef.Snippet(op.snippetId))))
            }
            is SyncOp.WordTag -> if (tagGone) {
                Applied(d.withOutbox(d.outbox.complete(batch.ids)).withoutTag(op.tagId), refresh = true)
            } else {
                park(d, batch.entries.map { it to Conflict.DeletedOnServer(entityOf(it.op)) })
            }
            is SyncOp.Merge -> park(d, listOf(batch.first to Conflict.MergeChanged(EntityRef.Snippet(op.mergedId), missing = op.sources.map { it.id })))
            is SyncOp.Transfer -> park(d, listOf(batch.first to Conflict.Rejected(entityOf(op), "the canvas or a snippet is no longer on the server")))
            is SyncOp.CreateSnippet -> park(d, listOf(batch.first to Conflict.Rejected(entityOf(op), "its canvas was deleted on the server")))
            is SyncOp.AddWord -> park(d, batch.entries.map { it to Conflict.Rejected(entityOf(it.op), "a tag it uses was deleted on the server") })
            else -> park(d, batch.entries.map { it to Conflict.DeletedOnServer(entityOf(it.op)) })
        }
    }
}
