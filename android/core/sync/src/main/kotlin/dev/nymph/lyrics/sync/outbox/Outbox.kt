package dev.nymph.lyrics.sync.outbox

import dev.nymph.lyrics.sync.conflict.Conflict
import kotlinx.serialization.Serializable

/** The most word operations one bulk request carries (the server's batch limit). */
const val MAX_WORD_BATCH: Int = 500

/** Operations sent together in one request: one entry, or same-kind word operations on different words. */
data class Batch(val entries: List<OutboxEntry>) {
    init {
        require(entries.isNotEmpty()) { "a batch has at least one operation" }
    }

    val first: OutboxEntry get() = entries.first()
    val ids: Set<OpId> get() = entries.mapTo(LinkedHashSet()) { it.id }
}

/**
 * The ordered, persistent list of local changes not yet confirmed by the server. Immutable: every
 * change returns a new outbox. Completed operations are removed; ids keep increasing.
 */
@Serializable
@ConsistentCopyVisibility
data class Outbox private constructor(val entries: List<OutboxEntry>, val nextId: Long) {
    val isEmpty: Boolean get() = entries.isEmpty()

    /** Pending, in flight or failed (not parked): what still syncs on its own. */
    val unsentCount: Int get() = entries.count { !it.isParked }

    val parked: List<OutboxEntry> get() = entries.filter { it.isParked }

    operator fun get(id: OpId): OutboxEntry? = entries.firstOrNull { it.id == id }

    /** Every entity some operation still changes (the snapshot keeps their local copies). */
    fun touchedEntities(): Set<EntityRef> = entries.flatMapTo(HashSet()) { it.op.touches }

    /** Entities created by an operation still in the outbox. */
    fun pendingCreates(): Set<EntityRef> = entries.mapNotNullTo(HashSet()) { it.op.creates }

    /** The parked conflict on [entity], if any (at most one, see [enqueue]). */
    fun parkedOn(entity: EntityRef): OutboxEntry? = entries.firstOrNull { it.isParked && entity in it.op.touches }

    fun hasOpsOn(entity: EntityRef): Boolean = entries.any { entity in it.op.touches }

    /**
     * Append [op], coalescing it into an earlier one when nothing in between could tell:
     * content edits of one snippet (or a canvas title, a tag, a word text) merge into the latest
     * pending or parked edit of the same entity, which keeps its base version; layout updates of
     * one (snippet, canvas) and a canvas viewport keep the last value. Content and layout skip
     * each other; anything else on the entity (a create, delete, link, merge, transfer) stops the
     * search. In-flight and failed operations are never changed (the server may have applied them).
     */
    fun enqueue(op: SyncOp): Outbox {
        val index = coalesceTarget(op)
        if (index != null) {
            val target = entries[index]
            return copy(entries = entries.toMutableList().also { it[index] = target.copy(op = coalesce(target.op, op)) })
        }
        return copy(entries = entries + OutboxEntry(OpId(nextId), op), nextId = nextId + 1)
    }

    private fun coalesceTarget(op: SyncOp): Int? {
        val entity = op.touches.singleOrNull() ?: return null
        for (i in entries.indices.reversed()) {
            val entry = entries[i]
            if (entity !in entry.op.touches) continue
            when (coalesceStep(entry, op)) {
                Step.Merge -> return i
                Step.Skip -> continue
                Step.Stop -> return null
            }
        }
        return null
    }

    private enum class Step { Merge, Skip, Stop }

    private fun coalesceStep(entry: OutboxEntry, op: SyncOp): Step {
        val earlier = entry.op
        val mutable = entry.state is OpState.Pending || entry.state is OpState.Parked
        val layoutMutable = entry.state is OpState.Pending
        return when (op) {
            is SyncOp.EditSnippet -> when (earlier) {
                is SyncOp.EditSnippet -> if (mutable) Step.Merge else Step.Stop
                is SyncOp.MoveSnippet -> Step.Skip
                else -> Step.Stop
            }
            is SyncOp.MoveSnippet -> when (earlier) {
                is SyncOp.MoveSnippet -> when {
                    earlier.canvasId != op.canvasId -> Step.Skip
                    layoutMutable -> Step.Merge
                    else -> Step.Stop
                }
                is SyncOp.EditSnippet, is SyncOp.SnippetTag -> Step.Skip
                else -> Step.Stop
            }
            is SyncOp.RenameCanvas -> when (earlier) {
                is SyncOp.RenameCanvas -> if (mutable) Step.Merge else Step.Stop
                is SyncOp.SaveViewport -> Step.Skip
                else -> Step.Stop
            }
            is SyncOp.SaveViewport -> when (earlier) {
                is SyncOp.SaveViewport -> if (layoutMutable) Step.Merge else Step.Stop
                is SyncOp.RenameCanvas -> Step.Skip
                else -> Step.Stop
            }
            is SyncOp.UpdateTag -> if (earlier is SyncOp.UpdateTag && mutable) Step.Merge else Step.Stop
            is SyncOp.RenameWord -> if (earlier is SyncOp.RenameWord && mutable) Step.Merge else Step.Stop
            else -> Step.Stop
        }
    }

    private fun coalesce(earlier: SyncOp, later: SyncOp): SyncOp = when {
        earlier is SyncOp.EditSnippet && later is SyncOp.EditSnippet -> earlier.copy(patch = earlier.patch.then(later.patch))
        earlier is SyncOp.MoveSnippet && later is SyncOp.MoveSnippet -> earlier.copy(layout = earlier.layout.then(later.layout))
        earlier is SyncOp.RenameCanvas && later is SyncOp.RenameCanvas -> earlier.copy(title = later.title)
        earlier is SyncOp.SaveViewport && later is SyncOp.SaveViewport -> later
        earlier is SyncOp.UpdateTag && later is SyncOp.UpdateTag -> earlier.copy(name = later.name ?: earlier.name, color = later.color ?: earlier.color)
        earlier is SyncOp.RenameWord && later is SyncOp.RenameWord -> earlier.copy(text = later.text)
        else -> error("not coalescable: ${earlier::class.simpleName} then ${later::class.simpleName}")
    }

    /**
     * The next request to send: the first pending or failed operation not waiting for an earlier
     * one ([waitsFor]), with the eligible same-kind word operations that follow it batched in.
     * Null when nothing can be sent (empty, or everything waits behind in-flight / parked work).
     */
    fun nextBatch(): Batch? {
        val firstIndex = entries.indices.firstOrNull { isSendable(it) } ?: return null
        val first = entries[firstIndex]
        val key = batchKey(first.op) ?: return Batch(listOf(first))
        val batch = mutableListOf(first)
        for (i in firstIndex + 1 until entries.size) {
            if (batch.size >= MAX_WORD_BATCH) break
            val entry = entries[i]
            if (batchKey(entry.op) == key && isSendable(i, ignoring = batch.mapTo(HashSet()) { it.id })) batch += entry
        }
        return Batch(batch)
    }

    private fun isSendable(index: Int, ignoring: Set<OpId> = emptySet()): Boolean {
        val entry = entries[index]
        if (entry.state !is OpState.Pending && entry.state !is OpState.Failed) return false
        return (0 until index).none { j -> entries[j].id !in ignoring && waitsFor(entry.op, entries[j].op) }
    }

    fun markInFlight(ids: Set<OpId>): Outbox = mapEntries(ids) { it.copy(state = OpState.InFlight) }

    /** Remove completed operations. */
    fun complete(ids: Set<OpId>): Outbox = copy(entries = entries.filter { it.id !in ids })

    /** A transient failure: back to the queue, in place, with the attempt counted. */
    fun fail(ids: Set<OpId>, reason: String): Outbox = mapEntries(ids) { entry ->
        val attempts = (entry.state as? OpState.Failed)?.attempts ?: 0
        entry.copy(state = OpState.Failed(attempts + 1, reason))
    }

    /** Back to pending without counting an attempt (e.g. a batch the server refused for another word's sake). */
    fun requeue(ids: Set<OpId>): Outbox = mapEntries(ids) { it.copy(state = OpState.Pending) }

    fun park(id: OpId, conflict: Conflict): Outbox = mapEntries(setOf(id)) { it.copy(state = OpState.Parked(conflict)) }

    /** Every remaining operation based on [entity] now starts from [version] (an earlier change on it succeeded). */
    fun rebase(entity: EntityRef, version: Int): Outbox = copy(
        entries = entries.map { if (entity in it.op.bases) it.copy(op = it.op.rebased(entity, version)) else it },
    )

    fun remap(remap: IdRemap): Outbox = if (remap.isEmpty) this else copy(entries = entries.map { it.copy(op = it.op.remapped(remap)) })

    /** Drop operations without sending them (resolved conflicts and what depended on them). */
    fun drop(ids: Set<OpId>): Outbox = complete(ids)

    fun replace(id: OpId, op: SyncOp, state: OpState = OpState.Pending): Outbox = mapEntries(setOf(id)) { it.copy(op = op, state = state) }

    /** Insert [ops] right after [id] (or at the end when it is gone), in order, as pending. */
    fun insertAfter(id: OpId, ops: List<SyncOp>): Outbox {
        if (ops.isEmpty()) return this
        val at = entries.indexOfFirst { it.id == id }.let { if (it < 0) entries.size else it + 1 }
        val added = ops.mapIndexed { i, op -> OutboxEntry(OpId(nextId + i), op) }
        return copy(entries = entries.take(at) + added + entries.drop(at), nextId = nextId + ops.size)
    }

    /** After a restart nothing is in flight any more: those operations are sent again (creates are idempotent). */
    fun recovered(): Outbox = copy(entries = entries.map { if (it.state == OpState.InFlight) it.copy(state = OpState.Pending) else it })

    private inline fun mapEntries(ids: Set<OpId>, change: (OutboxEntry) -> OutboxEntry): Outbox =
        copy(entries = entries.map { if (it.id in ids) change(it) else it })

    companion object {
        val EMPTY: Outbox = Outbox(emptyList(), 1)

        /** For tests and decoding: an outbox holding [entries], ids continuing after the largest. */
        fun of(entries: List<OutboxEntry>): Outbox = Outbox(entries, (entries.maxOfOrNull { it.id.value } ?: 0) + 1)
    }
}

/**
 * Whether [later] must wait for [earlier] (still in the outbox): they change the same entity
 * (FIFO, which also holds everything behind a parked conflict), [later] names what [earlier]
 * creates, or [later] deletes something [earlier] names.
 */
fun waitsFor(later: SyncOp, earlier: SyncOp): Boolean =
    later.touches.any { it in earlier.touches } ||
        (earlier.creates?.let { it in later.references } == true) ||
        later.deletes.any { it in earlier.references }

/** Word operations of one kind (and the same tag change) are sent as one bulk request. */
private fun batchKey(op: SyncOp): Any? = when (op) {
    is SyncOp.AddWord -> "add" to op.tagIds.toSet()
    is SyncOp.DeleteWord -> "delete"
    is SyncOp.WordTag -> Triple("tag", op.tagId, op.op)
    else -> null
}
