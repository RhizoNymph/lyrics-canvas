package dev.nymph.lyrics.logic.tags

import dev.nymph.lyrics.model.SnippetType

/** Operator keywords offered by completion. */
enum class ExprKeyword(val text: String) { AND("and"), OR("or"), NOT("not") }

/** One completion at the caret; [insertText] replaces the completed word. */
sealed interface Suggestion {
    val insertText: String

    data class TagName(val name: String) : Suggestion {
        override val insertText: String get() = formatTagName(name)
    }

    data class TypeValue(val type: SnippetType) : Suggestion {
        override val insertText: String get() = "type:${type.wire}"
    }

    data class Keyword(val keyword: ExprKeyword) : Suggestion {
        override val insertText: String get() = keyword.text
    }
}

/** A text and its caret, both in code points. */
data class TextEdit(val text: String, val caret: Int)

/**
 * The suggestions for the word at the caret; applying one replaces the code points
 * `[start, end)` (the whole word, or the open quoted name including its quotes).
 */
data class Completion(val start: Int, val end: Int, val suggestions: List<Suggestion>) {
    /** Replace the completed word with [suggestion]; a space follows unless whitespace or `)` already does. */
    fun apply(text: String, suggestion: Suggestion): TextEdit {
        val cps = text.toCodePoints()
        val from = start.coerceIn(0, cps.size)
        val to = end.coerceIn(from, cps.size)
        val next = cps.getOrNull(to)
        val space = if (next == null || !(isExprSpace(next) || next == ')'.code)) " " else ""
        val inserted = suggestion.insertText + space
        val before = cps.slice(0, from)
        return TextEdit(before + inserted + cps.slice(to, cps.size), from + codePointLength(inserted))
    }
}

/** Default number of suggestions shown. */
const val COMPLETION_LIMIT: Int = 12

/**
 * The index of the unclosed `"` before [caret] (honouring `\"` and `\\` escapes), or null when
 * the caret is not inside a quoted name.
 */
private fun openQuoteBefore(cps: IntArray, caret: Int): Int? {
    var open: Int? = null
    var i = 0
    while (i < caret) {
        val c = cps[i]
        if (open != null) {
            if (c == BACKSLASH) {
                i += 2
                continue
            }
            if (c == QUOTE) open = null
        } else if (c == QUOTE) {
            open = i
        }
        i++
    }
    return open
}

/** The index just past the quote that closes the name opened at [open], or null. */
private fun closingQuoteAfter(cps: IntArray, open: Int): Int? {
    var i = open + 1
    while (i < cps.size) {
        when (cps[i]) {
            BACKSLASH -> i++
            QUOTE -> return i + 1
        }
        i++
    }
    return null
}

private fun unescapeQuoted(raw: String): String = raw.replace("\\\"", "\"").replace("\\\\", "\\")

/**
 * Autocomplete at [caret] (code points): tag names from [tagNames] (prefix matches, then other
 * matches, case-insensitively), `type:` values, and `and` / `or` / `not` where an operator fits
 * (`not` wherever a term can start; `and` / `or` after a term, listed first when nothing is typed).
 */
fun completeAt(text: String, caret: Int, tagNames: List<String>, limit: Int = COMPLETION_LIMIT): Completion {
    val cps = text.toCodePoints()
    val at = caret.coerceIn(0, cps.size)
    val quote = openQuoteBefore(cps, at)
    val start: Int
    val end: Int
    val prefix: String
    if (quote != null) {
        start = quote
        end = closingQuoteAfter(cps, quote) ?: at
        prefix = unescapeQuoted(cps.slice(quote + 1, at))
    } else {
        var s = at
        while (s > 0 && isWordChar(cps[s - 1])) s--
        var e = at
        while (e < cps.size && isWordChar(cps[e])) e++
        start = s
        end = e
        prefix = cps.slice(s, at)
    }
    val previous = lexLenient(cps.slice(0, start)).lastOrNull()
    val afterOperand = previous != null && previous.endsOperand
    val lower = prefix.lowerRoot()
    val typed = cps.slice(start, end)

    val sortedTags = tagNames.distinct().sortedBy { it.lowerRoot() }.filter { formatTagName(it) != typed }
    val prefixTags = sortedTags.filter { it.lowerRoot().startsWith(lower) }
    val otherTags = if (lower.isEmpty()) emptyList() else sortedTags.filter { it !in prefixTags && lower in it.lowerRoot() }

    val types = if (quote != null) {
        emptyList()
    } else {
        SnippetType.entries.filter { type ->
            val term = "type:${type.wire}"
            term.startsWith(lower) && term != typed.lowerRoot()
        }
    }
    val keywords = if (quote != null) {
        emptyList()
    } else {
        val fitting = if (afterOperand) ExprKeyword.entries else listOf(ExprKeyword.NOT)
        fitting.filter { it.text.startsWith(lower) && it.text != typed.lowerRoot() }
    }
    // Right after a term with nothing typed, an operator is the likeliest next step.
    val keywordsFirst = afterOperand && lower.isEmpty()
    val suggestions = buildList {
        if (keywordsFirst) keywords.forEach { add(Suggestion.Keyword(it)) }
        prefixTags.forEach { add(Suggestion.TagName(it)) }
        types.forEach { add(Suggestion.TypeValue(it)) }
        if (!keywordsFirst) keywords.forEach { add(Suggestion.Keyword(it)) }
        otherTags.forEach { add(Suggestion.TagName(it)) }
    }
    return Completion(start, end, suggestions.take(limit))
}

/**
 * Insert tag [name] at [caret] (a tag pill tap), joined with ` & ` to a term on either side.
 * A caret inside a word or quoted name first moves past it. The new caret follows the name.
 */
fun insertTagAt(text: String, caret: Int, name: String): TextEdit {
    val cps = text.toCodePoints()
    var at = caret.coerceIn(0, cps.size)
    val quote = openQuoteBefore(cps, at)
    if (quote != null) {
        at = closingQuoteAfter(cps, quote) ?: cps.size
    } else {
        while (at < cps.size && at > 0 && isWordChar(cps[at - 1]) && isWordChar(cps[at])) at++
    }
    val left = cps.slice(0, at)
    val right = cps.slice(at, cps.size)
    val joinLeft = lexLenient(left).lastOrNull()?.endsOperand == true
    val joinRight = lexLenient(right).firstOrNull()?.beginsOperand == true
    val lastLeft = left.codePoints().toArray().lastOrNull()
    val firstRight = right.codePoints().toArray().firstOrNull()

    val leftGlue = when {
        lastLeft == null -> ""
        joinLeft -> if (isExprSpace(lastLeft)) "& " else " & "
        isExprSpace(lastLeft) || lastLeft == '('.code || lastLeft == '!'.code -> ""
        else -> " "
    }
    val rightGlue = when {
        firstRight == null -> ""
        joinRight -> if (isExprSpace(firstRight)) " &" else " & "
        isExprSpace(firstRight) || firstRight == ')'.code -> ""
        else -> " "
    }
    val head = left + leftGlue + formatTagName(name)
    return TextEdit(head + rightGlue + right, codePointLength(head))
}
