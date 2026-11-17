package dev.nymph.lyrics.app.rhyme.rhythm

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** The rhyme sheet's "Fit rhythm" switch, one per device (off until turned on). */
interface RhythmFitStore {
    val enabled: Flow<Boolean>

    suspend fun save(enabled: Boolean)
}

/** [RhythmFitStore] in Preferences DataStore (key `rhyme_rhythm_fit`, beside `rhyme_order`). */
class DataStoreRhythmFitStore(private val store: DataStore<Preferences>) : RhythmFitStore {
    override val enabled: Flow<Boolean> = store.data.map { it[KEY] ?: false }.distinctUntilChanged()

    override suspend fun save(enabled: Boolean) {
        store.edit { it[KEY] = enabled }
    }

    private companion object {
        val KEY = booleanPreferencesKey("rhyme_rhythm_fit")
    }
}

/** A [RhythmFitStore] for one process (tests, previews). */
class InMemoryRhythmFitStore(initial: Boolean = false) : RhythmFitStore {
    private val value = MutableStateFlow(initial)

    override val enabled: Flow<Boolean> = value.asStateFlow()

    override suspend fun save(enabled: Boolean) {
        value.value = enabled
    }
}
