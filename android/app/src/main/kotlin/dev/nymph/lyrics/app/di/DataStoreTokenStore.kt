package dev.nymph.lyrics.app.di

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.nymph.lyrics.data.TokenStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** [TokenStore] persisted in Preferences DataStore so a sign-in survives process death. */
class DataStoreTokenStore(private val store: DataStore<Preferences>) : TokenStore {
    override val tokens: Flow<String?> = store.data.map { it[TOKEN] }

    override suspend fun token(): String? = tokens.first()

    override suspend fun save(token: String) {
        store.edit { it[TOKEN] = token }
    }

    override suspend fun clear() {
        store.edit { it.remove(TOKEN) }
    }

    private companion object {
        val TOKEN = stringPreferencesKey("auth_token")
    }
}
