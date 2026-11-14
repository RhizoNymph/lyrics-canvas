package dev.nymph.lyrics.app.rhyme.order

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.nymph.lyrics.model.RhymeOrder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * The "Rhyme" / "Rhyme, then meaning" choice, one per device for the rhyme sheet, the pattern
 * columns and the word bank's rhymes-with view (each follows [order], so a change in one shows in all).
 */
interface RhymeOrderStore {
    /** The chosen order; [RhymeOrder.RHYME] until one was chosen. */
    val order: Flow<RhymeOrder>

    suspend fun save(order: RhymeOrder)
}

/** The stored form: the wire name. */
private fun parseOrder(saved: String?): RhymeOrder = when (saved) {
    "meaning" -> RhymeOrder.MEANING
    else -> RhymeOrder.RHYME
}

private val RhymeOrder.stored: String
    get() = when (this) {
        RhymeOrder.RHYME -> "rhyme"
        RhymeOrder.MEANING -> "meaning"
    }

/** [RhymeOrderStore] in Preferences DataStore (key `rhyme_order`). */
class DataStoreRhymeOrderStore(private val store: DataStore<Preferences>) : RhymeOrderStore {
    override val order: Flow<RhymeOrder> = store.data.map { parseOrder(it[KEY]) }.distinctUntilChanged()

    override suspend fun save(order: RhymeOrder) {
        store.edit { it[KEY] = order.stored }
    }

    private companion object {
        val KEY = stringPreferencesKey("rhyme_order")
    }
}

/** A [RhymeOrderStore] for one process (tests, previews). */
class InMemoryRhymeOrderStore(initial: RhymeOrder = RhymeOrder.RHYME) : RhymeOrderStore {
    private val value = MutableStateFlow(initial)

    override val order: Flow<RhymeOrder> = value.asStateFlow()

    override suspend fun save(order: RhymeOrder) {
        value.value = order
    }
}
