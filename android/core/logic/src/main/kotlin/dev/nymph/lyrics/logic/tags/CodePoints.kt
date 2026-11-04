package dev.nymph.lyrics.logic.tags

/**
 * Tag-expression positions are Unicode code point offsets (the contract shared with the Python
 * backend and the web client), never UTF-16 indices. These helpers convert at the UI boundary.
 */

/** The text as code points (one Int per Unicode scalar, so an emoji is one element). */
internal fun String.toCodePoints(): IntArray = codePoints().toArray()

internal fun IntArray.slice(from: Int, to: Int): String = String(this, from, to - from)

/** Code point length of [text]. */
fun codePointLength(text: String): Int = text.codePointCount(0, text.length)

/** The code point offset of UTF-16 index [index] (clamped to the text). */
fun codePointOffsetOf(text: String, index: Int): Int = text.codePointCount(0, index.coerceIn(0, text.length))

/** The UTF-16 index of code point offset [offset] (clamped to the text). */
fun utf16IndexOf(text: String, offset: Int): Int = text.offsetByCodePoints(0, offset.coerceIn(0, codePointLength(text)))

/**
 * Python's `str.isspace` (the reference rule): Unicode whitespace including the no-break spaces
 * and U+0085, which `Character.isWhitespace` leaves out.
 */
internal fun isExprSpace(cp: Int): Boolean = Character.isWhitespace(cp) || Character.isSpaceChar(cp) || cp == 0x85

internal const val QUOTE: Int = '"'.code
internal const val BACKSLASH: Int = '\\'.code

internal fun isOperatorChar(cp: Int): Boolean = cp == '('.code || cp == ')'.code || cp == '&'.code || cp == '|'.code || cp == '!'.code

/** Part of a bare word: not whitespace, not an operator, not a quote or backslash. */
internal fun isWordChar(cp: Int): Boolean = !isExprSpace(cp) && !isOperatorChar(cp) && cp != QUOTE && cp != BACKSLASH
