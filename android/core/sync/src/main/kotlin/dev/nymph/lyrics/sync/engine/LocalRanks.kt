package dev.nymph.lyrics.sync.engine

import dev.nymph.lyrics.sync.outbox.Batch
import dev.nymph.lyrics.sync.outbox.SyncOp
import dev.nymph.lyrics.sync.store.LocalData

/**
 * The batch as it is sent: a snippet create that names no `list_rank` carries its local
 * placement's key (chosen on top of the canvas's custom order when it was created offline, or
 * moved since), so the server keeps the order the phone showed. Queued operations are unchanged.
 */
fun Batch.withLocalRanks(d: LocalData): Batch = Batch(
    entries.map { entry ->
        val op = entry.op
        if (op !is SyncOp.CreateSnippet || op.body.listRank != null) return@map entry
        val rank = d.placement(op.id, op.canvasId)?.listRank ?: return@map entry
        entry.copy(op = op.copy(body = op.body.copy(listRank = rank)))
    },
)
