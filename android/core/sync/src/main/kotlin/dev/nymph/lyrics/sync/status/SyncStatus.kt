package dev.nymph.lyrics.sync.status

import dev.nymph.lyrics.sync.engine.EngineActivity
import dev.nymph.lyrics.sync.outbox.EntityRef
import dev.nymph.lyrics.sync.outbox.Outbox

/** The sync status indicator (canvas list and canvas top bars); tapping it opens the Not-synced list. */
sealed interface SyncStatus {
    /** Nothing waiting. */
    data object Synced : SyncStatus

    /** Sending or refreshing, or changes waiting while the server is reachable. */
    data class Syncing(val pending: Int) : SyncStatus

    /** The server is unreachable; [pending] changes wait for it. */
    data class Offline(val pending: Int) : SyncStatus

    /** [count] changes are parked (conflicts); wins over every other state. */
    data class NotSynced(val count: Int, val pending: Int) : SyncStatus

    /** The indicator's text. */
    val label: String
        get() = when (this) {
            Synced -> "Synced"
            is Syncing -> "Syncing"
            is Offline -> if (pending == 0) "Offline" else "Offline ($pending pending)"
            is NotSynced -> "$count not synced"
        }
}

fun syncStatus(outbox: Outbox, online: Boolean, activity: EngineActivity): SyncStatus {
    val parked = outbox.parked.size
    val pending = outbox.unsentCount
    return when {
        parked > 0 -> SyncStatus.NotSynced(parked, pending)
        !online -> SyncStatus.Offline(pending)
        pending > 0 || activity != EngineActivity.IDLE -> SyncStatus.Syncing(pending)
        else -> SyncStatus.Synced
    }
}

/** Entities with a parked change: they show the "Not synced" badge. */
fun notSyncedEntities(outbox: Outbox): Set<EntityRef> = outbox.parked.flatMapTo(HashSet()) { it.op.touches }
