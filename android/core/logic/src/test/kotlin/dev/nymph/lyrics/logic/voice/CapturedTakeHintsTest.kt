package dev.nymph.lyrics.logic.voice

import dev.nymph.lyrics.model.SnippetId
import dev.nymph.lyrics.model.voice.AudioMime
import dev.nymph.lyrics.model.voice.VoiceTakeTarget
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CapturedTakeHintsTest {
    private val take = CapturedTake(AudioFile("/t.ogg", AudioMime.OGG), 1_000, emptyList())
    private val target = VoiceTakeTarget.Append(SnippetId("s1"))

    @Test
    fun `hints are on unless the recorder turned them off`() {
        assertTrue(take.meta(target).vocabularyHints)
        assertTrue(take.meta(target, vocabularyHints = true).vocabularyHints)
        assertFalse(take.meta(target, vocabularyHints = false).vocabularyHints)
    }
}
