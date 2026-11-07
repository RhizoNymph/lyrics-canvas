package dev.nymph.lyrics.app.voice

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
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

    /** "Use word bank as hints" (sent as `meta.vocabulary_hints`); on until set. */
    val vocabularyHints: Flow<Boolean>

    suspend fun setVocabularyHints(enabled: Boolean)
}

/** [VoiceSettingsStore] in Preferences DataStore (the store the app already uses for the token). */
class DataStoreVoiceSettings(private val store: DataStore<Preferences>) : VoiceSettingsStore {
    override val silence: Flow<SilenceTimeout> =
        store.data.map { prefs -> prefs[SILENCE_SECONDS]?.let(SilenceTimeout::fromSeconds) ?: SilenceTimeout.Default }

    override suspend fun setSilence(value: SilenceTimeout) {
        store.edit { it[SILENCE_SECONDS] = value.seconds }
    }

    override val vocabularyHints: Flow<Boolean> = store.data.map { prefs -> prefs[VOCAB_HINTS] ?: true }

    override suspend fun setVocabularyHints(enabled: Boolean) {
        store.edit { it[VOCAB_HINTS] = enabled }
    }

    private companion object {
        val SILENCE_SECONDS = intPreferencesKey("silence_auto_stop_seconds")
        val VOCAB_HINTS = booleanPreferencesKey("voice_vocab_hints")
    }
}
