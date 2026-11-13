package dev.nymph.lyrics.sync

import dev.nymph.lyrics.sync.conflict.ConflictAlert
import dev.nymph.lyrics.sync.conflict.Resolution
import dev.nymph.lyrics.sync.conflict.ResolveError
import dev.nymph.lyrics.sync.conflict.Resolved
import dev.nymph.lyrics.sync.conflict.Resolver
import dev.nymph.lyrics.sync.conflict.ReviewItem
import dev.nymph.lyrics.sync.conflict.reviewItem
import dev.nymph.lyrics.sync.conflict.reviewItems
import dev.nymph.lyrics.sync.engine.EngineActivity
import dev.nymph.lyrics.sync.engine.SyncTrigger
import dev.nymph.lyrics.sync.engine.Wake
import dev.nymph.lyrics.sync.outbox.EntityRef
import dev.nymph.lyrics.sync.outbox.OpId
import dev.nymph.lyrics.sync.status.SyncStatus
import dev.nymph.lyrics.sync.status.notSyncedEntities
import dev.nymph.lyrics.sync.status.syncStatus
import dev.nymph.lyrics.sync.store.LocalStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map

/** What the screens read about sync, and the review's actions. */
class SyncCenter(
    private val store: LocalStore,
    private val trigger: SyncTrigger,
    private val resolver: Resolver,
    /** Reachability (the health monitor: not down). */
    val online: StateFlow<Boolean>,
    activity: Flow<EngineActivity>,
    /** Newly parked changes, each once. */
    val alerts: Flow<ConflictAlert> = emptyFlow(),
) {
    val status: Flow<SyncStatus> = combine(store.data, online, activity) { d, o, a -> syncStatus(d.outbox, o, a) }.distinctUntilChanged()

    /** Entities that show the "Not synced" badge. */
    val notSynced: Flow<Set<EntityRef>> = store.data.map { notSyncedEntities(it.outbox) }.distinctUntilChanged()

    val reviewItems: Flow<List<ReviewItem>> = store.data.map(::reviewItems).distinctUntilChanged()

    fun reviewItem(opId: OpId): Flow<ReviewItem?> = store.data.map { reviewItem(it, opId) }.distinctUntilChanged()

    /** Every change not yet confirmed (pending, failed or parked): what a sign-out would discard. */
    val unconfirmed: Flow<Int> = store.data.map { it.outbox.entries.size }.distinctUntilChanged()

    /** Null when applied. */
    suspend fun resolve(opId: OpId, resolution: Resolution): ResolveError? {
        val outcome = store.update { d ->
            when (val r = resolver.resolve(d, opId, resolution)) {
                is Resolved.Done -> r.data to r
                is Resolved.Refused -> d to r
            }
        }
        return when (outcome) {
            is Resolved.Done -> {
                trigger.wake(if (outcome.refresh) Wake.REFRESH else Wake.EDIT)
                null
            }
            is Resolved.Refused -> outcome.error
        }
    }

    /** Send now (the status indicator's retry). */
    fun syncNow() = trigger.wake(Wake.REFRESH)
}
