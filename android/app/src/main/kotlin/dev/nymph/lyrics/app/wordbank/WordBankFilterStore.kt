package dev.nymph.lyrics.app.wordbank

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.nymph.lyrics.app.tags.FilterTextStore
import kotlinx.coroutines.flow.first

/** DataStore key of the word bank filter (user-wide on this device, separate from every canvas filter). */
const val WORD_BANK_FILTER_KEY: String = "word_bank_filter"

/** The word bank filter bar's last valid text, in the tag filters' Preferences DataStore. */
class DataStoreWordBankFilterStore(private val store: DataStore<Preferences>) : FilterTextStore {
    private val key = stringPreferencesKey(WORD_BANK_FILTER_KEY)

    override suspend fun load(): String = store.data.first()[key] ?: ""

    override suspend fun save(text: String) {
        store.edit { prefs -> if (text.isEmpty()) prefs.remove(key) else prefs[key] = text }
    }
}
