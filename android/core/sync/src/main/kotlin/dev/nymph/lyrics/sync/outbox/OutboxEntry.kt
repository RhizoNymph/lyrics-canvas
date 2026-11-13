package dev.nymph.lyrics.sync.outbox

import dev.nymph.lyrics.sync.conflict.Conflict
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** An outbox entry's id: unique within a store, never reused (queue order is list order). */
@JvmInline
@Serializable
value class OpId(val value: Long) : Comparable<OpId> {
    override fun compareTo(other: OpId): Int = value.compareTo(other.value)

    override fun toString(): String = value.toString()
}

/**
 * Where an operation is in its life:
 *
 * ```
 * Pending ──send──> InFlight ──2xx / 404 on a delete──> (removed)
 *    ^                │ ├──network / 5xx / 401──> Failed(attempts) ──backoff / wake──> InFlight
 *    │                │ └──409 version_conflict / 404 on an edit / 4xx──> Parked(conflict)
 *    └──app start (an InFlight from a killed process)
 * Parked ──user: Keep server's──> (removed) | Overwrite / Retry merge──> Pending (rebased) | Save as new──> (removed, a create added)
 * ```
 */
@Serializable
sealed interface OpState {
    @Serializable
    @SerialName("pending")
    data object Pending : OpState

    @Serializable
    @SerialName("in_flight")
    data object InFlight : OpState

    /** A transient failure; retried in order after a backoff. */
    @Serializable
    @SerialName("failed")
    data class Failed(val attempts: Int, val reason: String) : OpState

    /** Waiting for the user (review screen). */
    @Serializable
    @SerialName("parked")
    data class Parked(val conflict: Conflict) : OpState
}

@Serializable
data class OutboxEntry(val id: OpId, val op: SyncOp, val state: OpState = OpState.Pending) {
    val isParked: Boolean get() = state is OpState.Parked
}
