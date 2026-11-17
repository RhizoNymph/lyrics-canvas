package dev.nymph.lyrics.logic.score

import dev.nymph.lyrics.model.GridLine
import java.text.Normalizer
import java.util.Locale

private const val APOSTROPHE = '\''
private const val RIGHT_SINGLE_QUOTE = '’'
private const val FALLBACK_PREFIX = "#"

private fun kept(cp: Int): Boolean {
    if (cp == APOSTROPHE.code) return true
    return when (Character.getType(cp).toByte()) {
        Character.UPPERCASE_LETTER, Character.LOWERCASE_LETTER, Character.TITLECASE_LETTER,
        Character.MODIFIER_LETTER, Character.OTHER_LETTER, Character.DECIMAL_DIGIT_NUMBER,
        -> true
        else -> false
    }
}

/** NFC, per-code-point lowercase, `’` -> `'`, keep letters (L*), decimal digits (Nd) and `'` (spec section 10). */
fun normalizeWord(text: String): String {
    val nfc = Normalizer.normalize(text, Normalizer.Form.NFC)
    val lowered = StringBuilder()
    var i = 0
    while (i < nfc.length) {
        val cp = nfc.codePointAt(i)
        lowered.append(String(Character.toChars(cp)).lowercase(Locale.ROOT))
        i += Character.charCount(cp)
    }
    val replaced = lowered.toString().replace(RIGHT_SINGLE_QUOTE, APOSTROPHE)
    val out = StringBuilder()
    replaced.codePoints().forEach { cp -> if (kept(cp)) out.appendCodePoint(cp) }
    return out.toString()
}

/** The normalized word, or `#` + NFC(spelling) when that is empty (punctuation-only words match themselves). */
fun wordKey(spelling: String): String =
    normalizeWord(spelling).ifEmpty { FALLBACK_PREFIX + Normalizer.normalize(spelling, Normalizer.Form.NFC) }

internal fun wordKeys(line: GridLine): List<String> = wordGroups(line.cells).map { wordKey(it.spelling) }

/** The word keys of the line's word groups joined by single spaces; `""` without syllables. */
fun lineKey(line: GridLine): String = wordKeys(line).joinToString(" ")

/** A longest common subsequence as index pairs; `""` never matches; ties skip the element of [a]. */
fun lcsPairs(a: List<String>, b: List<String>): List<Pair<Int, Int>> {
    val n = a.size
    val m = b.size
    fun eq(i: Int, j: Int) = a[i] == b[j] && a[i].isNotEmpty()
    val table = Array(n + 1) { IntArray(m + 1) }
    for (i in n - 1 downTo 0) {
        for (j in m - 1 downTo 0) {
            table[i][j] = if (eq(i, j)) table[i + 1][j + 1] + 1 else maxOf(table[i + 1][j], table[i][j + 1])
        }
    }
    val pairs = mutableListOf<Pair<Int, Int>>()
    var i = 0
    var j = 0
    while (i < n && j < m) {
        when {
            eq(i, j) -> { pairs += i to j; i++; j++ }
            table[i + 1][j] >= table[i][j + 1] -> i++
            else -> j++
        }
    }
    return pairs
}
