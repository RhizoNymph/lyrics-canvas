package dev.nymph.lyrics.app.tags

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.nymph.lyrics.model.CanvasId
import kotlinx.coroutines.flow.first

/** The canvas filter bar's last valid text, remembered per canvas on this device. */
interface TagFilterStore {
    /** The remembered text, or "" when none. */
    suspend fun load(canvasId: CanvasId): String

    /** Remember [text]; "" forgets it. */
    suspend fun save(canvasId: CanvasId, text: String)
}

/** [TagFilterStore] in Preferences DataStore, one key per canvas. */
class DataStoreTagFilterStore(private val store: DataStore<Preferences>) : TagFilterStore {
    override suspend fun load(canvasId: CanvasId): String = store.data.first()[key(canvasId)] ?: ""

    override suspend fun save(canvasId: CanvasId, text: String) {
        store.edit { prefs -> if (text.isEmpty()) prefs.remove(key(canvasId)) else prefs[key(canvasId)] = text }
    }

    private fun key(canvasId: CanvasId) = stringPreferencesKey("filter:${canvasId.value}")
}
