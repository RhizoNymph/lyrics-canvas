package dev.nymph.lyrics.app.voice.recordings

import dev.nymph.lyrics.model.voice.Recording
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Recordings changed by another screen (Check transcript), for the editor's list below it on the
 * back stack. A broadcast channel, one per process; no state is kept, late subscribers miss nothing
 * they need because their own list load is newer.
 */
class RecordingUpdates {
    private val flow = MutableSharedFlow<Recording>(extraBufferCapacity = 64)

    val updates: SharedFlow<Recording> = flow.asSharedFlow()

    fun publish(recording: Recording) {
        flow.tryEmit(recording)
    }
}
