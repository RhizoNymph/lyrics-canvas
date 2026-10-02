package dev.nymph.lyrics.logic

import kotlin.math.max
import kotlin.math.min

/**
 * JavaScript string/array semantics the ported TypeScript relies on, so the Kotlin ports give the
 * same results for the same inputs. JS `\s` and `String.prototype.trim` cover Unicode spaces that
 * Java's `\s` does not.
 */
private const val JS_SPACE = "\\s\\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF"
private val JS_WHITESPACE_RUN = Regex("[$JS_SPACE]+")
private val JS_LEADING = Regex("^[$JS_SPACE]+")
private val JS_TRAILING = Regex("[$JS_SPACE]+$")

/** `s.trim()` in JS. */
internal fun jsTrim(s: String): String = s.replace(JS_LEADING, "").replace(JS_TRAILING, "")

/** `s.split(/\s+/)` in JS (callers trim first, as the TS does). */
internal fun jsSplitWhitespace(s: String): List<String> = s.split(JS_WHITESPACE_RUN)

/** `Array.prototype.slice(start, end)`: negative indexes count from the end; out-of-range clamps. */
internal fun <T> List<T>.jsSlice(start: Int, end: Int = size): List<T> {
    val from = if (start < 0) max(size + start, 0) else min(start, size)
    val to = if (end < 0) max(size + end, 0) else min(end, size)
    return if (to <= from) emptyList() else subList(from, to).toList()
}

/** `String.prototype.slice(start, end)` with the same clamping as [jsSlice]. */
internal fun String.jsSlice(start: Int, end: Int = length): String {
    val from = if (start < 0) max(length + start, 0) else min(start, length)
    val to = if (end < 0) max(length + end, 0) else min(end, length)
    return if (to <= from) "" else substring(from, to)
}
