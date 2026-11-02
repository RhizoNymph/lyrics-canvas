package dev.nymph.lyrics.app.voice

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import dev.nymph.lyrics.logic.voice.SilenceTimeout
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Per-device voice capture settings. */
interface VoiceSettingsStore {
    /** Silence auto-stop; [SilenceTimeout.Default] (off) until set. */
    val silence: Flow<SilenceTimeout>

    suspend fun setSilence(value: SilenceTimeout)
}

/** [VoiceSettingsStore] in Preferences DataStore (the store the app already uses for the token). */
class DataStoreVoiceSettings(private val store: DataStore<Preferences>) : VoiceSettingsStore {
    override val silence: Flow<SilenceTimeout> =
        store.data.map { prefs -> prefs[SILENCE_SECONDS]?.let(SilenceTimeout::fromSeconds) ?: SilenceTimeout.Default }

    override suspend fun setSilence(value: SilenceTimeout) {
        store.edit { it[SILENCE_SECONDS] = value.seconds }
    }

    private companion object {
        val SILENCE_SECONDS = intPreferencesKey("silence_auto_stop_seconds")
    }
}
