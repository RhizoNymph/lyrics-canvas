package dev.nymph.lyrics.logic.voice

import dev.nymph.lyrics.model.SnippetId
import dev.nymph.lyrics.model.voice.Recording
import dev.nymph.lyrics.model.voice.RecordingId
import dev.nymph.lyrics.model.voice.SplitMode
import dev.nymph.lyrics.model.voice.TimedWord
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

private val WORDS = listOf(
    TimedWord("one", 0, 300),
    TimedWord("two", 300, 700),
    TimedWord("three", 900, 1_500),
    TimedWord("four", 1_500, 2_000),
)

private fun recording(
    transcript: String = "one two\nthree four",
    lines: List<String> = listOf("one two", "three four"),
    hasTimings: Boolean = true,
    words: List<TimedWord>? = WORDS,
    spans: List<List<Int>>? = listOf(listOf(0, 2), listOf(2, 4)),
) = Recording(
    RecordingId("r1"), SnippetId("s1"), "audio/ogg", 2_000, 10, transcript, SplitMode.TAPS, "2026-11-01T10:00:00Z",
    lines = lines, hasTimings = hasTimings, words = words, lineSpans = spans,
)

class TranscriptReferenceTest {
    @Test
    fun `timed lines carry their words with global indices`() {
        val ref = transcriptReference(recording()) as TranscriptReference.Timed
        assertEquals(listOf("one two", "three four"), ref.lines.map { it.whisperText })
        assertEquals(listOf(listOf(0, 1), listOf(2, 3)), ref.lines.map { line -> line.words.map { it.index } })
        assertEquals(listOf("three", "four"), ref.lines[1].words.map { it.word.text })
        assertEquals(WORDS, ref.words)
    }

    @Test
    fun `a blank line has an empty span and no words`() {
        val ref = transcriptReference(
            recording(
                transcript = "one two\n\nthree four",
                lines = listOf("one two", "", "three four"),
                spans = listOf(listOf(0, 2), listOf(2, 2), listOf(2, 4)),
            ),
        ) as TranscriptReference.Timed
        assertEquals(listOf(2, 0, 2), ref.lines.map { it.words.size })
    }

    @Test
    fun `without timings the lines are plain`() {
        assertEquals(TranscriptReference.Plain(listOf("one two", "three four")), transcriptReference(recording(hasTimings = false)))
        assertEquals(TranscriptReference.Plain(listOf("one two", "three four")), transcriptReference(recording(words = null)))
        assertEquals(TranscriptReference.Plain(listOf("one two", "three four")), transcriptReference(recording(spans = null)))
    }

    @Test
    fun `an old recording falls back to the transcript lines`() {
        assertEquals(
            TranscriptReference.Plain(listOf("a", "b")),
            transcriptReference(recording(transcript = "a\nb", lines = emptyList(), hasTimings = false, words = null, spans = null)),
        )
    }

    @Test
    fun `spans that break the contract fall back to plain lines`() {
        val plain = TranscriptReference.Plain(listOf("one two", "three four"))
        // gap
        assertEquals(plain, transcriptReference(recording(spans = listOf(listOf(0, 1), listOf(2, 4)))))
        // overlap
        assertEquals(plain, transcriptReference(recording(spans = listOf(listOf(0, 3), listOf(2, 4)))))
        // does not cover every word
        assertEquals(plain, transcriptReference(recording(spans = listOf(listOf(0, 2), listOf(2, 3)))))
        // past the words
        assertEquals(plain, transcriptReference(recording(spans = listOf(listOf(0, 2), listOf(2, 5)))))
        // reversed
        assertEquals(plain, transcriptReference(recording(spans = listOf(listOf(0, 2), listOf(4, 2)))))
        // not a pair
        assertEquals(plain, transcriptReference(recording(spans = listOf(listOf(0, 2), listOf(2, 4, 5)))))
        // one span per line
        assertEquals(plain, transcriptReference(recording(spans = listOf(listOf(0, 4)))))
    }
}

class WordIndexAtTest {
    @Test
    fun `the word whose span holds the position`() {
        assertEquals(0, wordIndexAt(WORDS, 0))
        assertEquals(0, wordIndexAt(WORDS, 299))
        assertEquals(1, wordIndexAt(WORDS, 300))
        assertEquals(1, wordIndexAt(WORDS, 699))
        assertEquals(2, wordIndexAt(WORDS, 900))
        assertEquals(3, wordIndexAt(WORDS, 1_500))
        assertEquals(3, wordIndexAt(WORDS, 1_999))
    }

    @Test
    fun `no word in a gap, before the first or after the last`() {
        assertNull(wordIndexAt(WORDS, 700))
        assertNull(wordIndexAt(WORDS, 850))
        assertNull(wordIndexAt(WORDS, -1))
        assertNull(wordIndexAt(WORDS, 2_000))
        assertNull(wordIndexAt(emptyList(), 0))
    }

    @Test
    fun `a zero-length word is never current`() {
        val words = listOf(TimedWord("a", 0, 100), TimedWord("b", 100, 100), TimedWord("c", 100, 200))
        assertEquals(2, wordIndexAt(words, 100))
    }

    @Test
    fun `matches a linear scan over a long take`() {
        val words = (0 until 500).map { i -> TimedWord("w$i", i * 100L, i * 100L + 70) }
        for (pos in -10L..50_100L step 7) {
            val expected = words.indexOfFirst { pos >= it.startMs && pos < it.endMs }.takeIf { it >= 0 }
            assertEquals(expected, wordIndexAt(words, pos), "at $pos")
        }
    }
}
