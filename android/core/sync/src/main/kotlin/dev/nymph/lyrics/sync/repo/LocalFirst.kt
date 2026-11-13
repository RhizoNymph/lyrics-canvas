package dev.nymph.lyrics.sync.repo

import dev.nymph.lyrics.model.error.ApiError
import dev.nymph.lyrics.model.error.ApiResult
import dev.nymph.lyrics.sync.apply.Edited
import dev.nymph.lyrics.sync.engine.SyncTrigger
import dev.nymph.lyrics.sync.engine.Wake
import dev.nymph.lyrics.sync.outbox.EntityRef
import dev.nymph.lyrics.sync.store.LocalData
import dev.nymph.lyrics.sync.store.LocalStore
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** How long a first read waits for the first snapshot before answering with the failure. */
const val FIRST_SNAPSHOT_TIMEOUT_MS: Long = 30_000

/** How long a server-only call waits for an entity's queued changes to reach the server first. */
const val SETTLE_TIMEOUT_MS: Long = 15_000

/** Server-only calls answer [ApiError.NeedsConnection] at once while the server is unreachable. */
class OnlineGate(private val online: StateFlow<Boolean>) {
    val isOnline: Boolean get() = online.value

    suspend fun <T> call(block: suspend () -> ApiResult<T>): ApiResult<T> =
        if (online.value) block() else ApiResult.Failure(ApiError.NeedsConnection)

    companion object {
        /** Always online (tests, and builds without a health monitor). */
        fun always(): OnlineGate = OnlineGate(kotlinx.coroutines.flow.MutableStateFlow(true))
    }
}

/** The shared plumbing of the local-first repositories. */
class LocalFirst(val store: LocalStore, val trigger: SyncTrigger) {
    /** Apply a local edit and its outbox operation in one store update; a success wakes the engine. */
    suspend fun <T> edit(change: (LocalData) -> Edited<T>): ApiResult<T> {
        val result = store.update { d -> change(d).let { it.data to it.result } }
        if (result is ApiResult.Success) trigger.wake(Wake.EDIT)
        return result
    }

    /**
     * The copy for a read. A fresh install has nothing to show until the first snapshot: wait for
     * it, and answer its failure (e.g. offline) when there is no copy at all.
     */
    suspend fun <T> read(view: (LocalData) -> ApiResult<T>): ApiResult<T> {
        val data = store.ensureLoaded()
        if (!data.hasSnapshot) {
            val error = withTimeoutOrNull(FIRST_SNAPSHOT_TIMEOUT_MS) { trigger.awaitSnapshot() }
            if (error != null && !store.data.value.hasSnapshot) return ApiResult.Failure(error)
        }
        return view(store.data.value)
    }

    /**
     * Wait until no queued change to [entities] is left (a server-only call that builds on them,
     * like a voice take appended to a snippet edited a moment ago). False on a parked conflict or
     * a timeout.
     */
    suspend fun awaitSettled(entities: Set<EntityRef>): Boolean {
        fun settled(d: LocalData) = entities.none { d.outbox.hasOpsOn(it) }
        if (settled(store.ensureLoaded())) return true
        trigger.wake(Wake.EDIT)
        val outcome = withTimeoutOrNull(SETTLE_TIMEOUT_MS) {
            store.data.first { d -> settled(d) || entities.any { d.outbox.parkedOn(it) != null } }
        } ?: return false
        return settled(outcome)
    }
}
