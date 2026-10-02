package dev.nymph.lyrics.logic.grid

import dev.nymph.lyrics.logic.jsSlice
import dev.nymph.lyrics.logic.jsSplitWhitespace
import dev.nymph.lyrics.logic.jsTrim
import dev.nymph.lyrics.model.GridCell
import dev.nymph.lyrics.model.GridLine
import kotlin.math.ceil
import kotlin.math.max

private val NON_LETTER = Regex("[^a-z]")
private val VOWEL_GROUP = Regex("[aeiouy]+")

/** Count vowel groups in a word as a rough syllable estimate (silent-e adjusted). */
fun estimateSyllableCount(word: String): Int {
    val clean = word.lowercase().replace(NON_LETTER, "")
    if (clean.isEmpty()) return 1
    var count = VOWEL_GROUP.findAll(clean).count()
    if (count == 0) return 1
    if (clean.length > 2 && clean.endsWith('e') && count > 1) count--
    return max(count, 1)
}

/** Split a word into roughly `estimateSyllableCount(word)` character chunks for a live expanding grid. */
fun splitWord(word: String): List<String> {
    val n = estimateSyllableCount(word)
    if (n <= 1) return listOf(word)
    val partLen = ceil(word.length.toDouble() / n).toInt()
    return (0 until n).map { i ->
        val start = i * partLen
        val end = if (i == n - 1) word.length else (i + 1) * partLen
        word.jsSlice(start, end)
    }
}

/** Instant local syllabification of one line; the API result replaces it once it arrives. */
fun localSyllabifyLine(text: String): GridLine {
    val rawText = jsTrim(text)
    if (rawText.isEmpty()) return GridLine(emptyList(), 0, "")
    val words = jsSplitWhitespace(rawText)
    val cells = words.flatMapIndexed { wi, word ->
        splitWord(word).map { syllable -> GridCell(syllable = syllable, wordIndex = wi, word = word) }
    }
    return GridLine(cells, cells.size, rawText)
}
