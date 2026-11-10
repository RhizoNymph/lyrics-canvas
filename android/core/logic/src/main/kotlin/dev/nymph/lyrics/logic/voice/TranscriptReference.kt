package dev.nymph.lyrics.logic.voice

import dev.nymph.lyrics.model.voice.Recording
import dev.nymph.lyrics.model.voice.TimedWord

/** A Whisper word and its index into the take's `words`. */
data class IndexedWord(val index: Int, val word: TimedWord)

/** One assembled line of a timed take: its text and the words behind it (none for a blank line). */
data class TimedLine(val whisperText: String, val words: List<IndexedWord>)

/** What the Check transcript screen shows as the read-only Whisper reference. */
sealed interface TranscriptReference {
    /** No usable timings: the lines only, without word interaction. */
    data class Plain(val lines: List<String>) : TranscriptReference

    /** Word timings: every word is tappable and can be highlighted. [words] is the take's word list. */
    data class Timed(val lines: List<TimedLine>, val words: List<TimedWord>) : TranscriptReference
}

/**
 * The reference for [recording]: [TranscriptReference.Timed] when it has timings whose spans keep
 * the contract (one `[start, end)` pair per line, contiguous from 0, covering every word in order);
 * otherwise [TranscriptReference.Plain] from its lines.
 */
fun transcriptReference(recording: Recording): TranscriptReference {
    val lines = recording.referenceLines
    val plain = TranscriptReference.Plain(lines)
    val words = recording.words
    val spans = recording.lineSpans
    if (!recording.hasTimings || words == null || spans == null || spans.size != lines.size) return plain
    var next = 0
    val timed = spans.mapIndexed { i, span ->
        if (span.size != 2) return plain
        val (start, end) = span
        if (start != next || end < start || end > words.size) return plain
        next = end
        TimedLine(lines[i], (start until end).map { IndexedWord(it, words[it]) })
    }
    if (next != words.size) return plain
    return TranscriptReference.Timed(timed, words)
}

/**
 * The index of the word being played at [positionMs]: the word with `start_ms <= position < end_ms`,
 * or null in a gap, before the first word or after the last. [words] are in service order (start
 * times ascending); a binary search finds the last word starting at or before the position.
 */
fun wordIndexAt(words: List<TimedWord>, positionMs: Long): Int? {
    var lo = 0
    var hi = words.size - 1
    var found = -1
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        if (words[mid].startMs <= positionMs) {
            found = mid
            lo = mid + 1
        } else {
            hi = mid - 1
        }
    }
    if (found < 0) return null
    return if (positionMs < words[found].endMs) found else null
}
