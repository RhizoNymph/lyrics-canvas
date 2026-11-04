package dev.nymph.lyrics.logic.tags

import dev.nymph.lyrics.model.SnippetType

/** A lexed token; [start] / [end] are code point offsets ([end] exclusive). */
internal sealed interface Token {
    val start: Int
    val end: Int

    data class LParen(override val start: Int) : Token {
        override val end: Int get() = start + 1
    }

    data class RParen(override val start: Int) : Token {
        override val end: Int get() = start + 1
    }

    /** `&` or `and`. */
    data class AndOp(override val start: Int, override val end: Int) : Token

    /** `|` or `or`. */
    data class OrOp(override val start: Int, override val end: Int) : Token

    /** `!` or `not`. */
    data class NotOp(override val start: Int, override val end: Int) : Token

    data class Name(val name: String, val quoted: Boolean, override val start: Int, override val end: Int) : Token

    data class TypeTerm(val type: SnippetType, override val start: Int, override val end: Int) : Token
}

/** True for tokens that end an operand (after which juxtaposition means AND). */
internal val Token.endsOperand: Boolean get() = this is Token.Name || this is Token.TypeTerm || this is Token.RParen

/** True for tokens that can begin an operand. */
internal val Token.beginsOperand: Boolean get() = this is Token.Name || this is Token.TypeTerm || this is Token.LParen || this is Token.NotOp

internal sealed interface LexResult {
    data class Tokens(val tokens: List<Token>) : LexResult

    /** [tokens] are those lexed before the error. */
    data class Failed(val error: TagExprError, val tokens: List<Token>) : LexResult
}

private val KEYWORDS = setOf("and", "or", "not")
private const val TYPE_PREFIX = "type:"

internal fun String.lowerRoot(): String = lowercase(java.util.Locale.ROOT)

internal fun isKeyword(word: String): Boolean = word.lowerRoot() in KEYWORDS

/** True when [word] (code points) starts with `type:` in any case. */
internal fun hasTypePrefix(word: String): Boolean {
    val cps = word.toCodePoints()
    return cps.size >= TYPE_PREFIX.length && cps.slice(0, TYPE_PREFIX.length).lowerRoot() == TYPE_PREFIX
}

/** The snippet type named by [value] (any case), or null. */
internal fun snippetTypeOf(value: String): SnippetType? = SnippetType.entries.firstOrNull { it.wire == value.lowerRoot() }

/** Lex [text] completely; the first error stops lexing (lexer errors win over parse errors). */
internal fun lex(text: String): LexResult {
    val cps = text.toCodePoints()
    if (cps.size > MAX_EXPRESSION_LENGTH) return LexResult.Failed(exprError(TagExprErrorCode.TOO_LONG, MAX_EXPRESSION_LENGTH), emptyList())
    val tokens = ArrayList<Token>()
    var i = 0
    fun fail(code: TagExprErrorCode, at: Int) = LexResult.Failed(exprError(code, at), tokens)
    while (i < cps.size) {
        val c = cps[i]
        when {
            isExprSpace(c) -> i++
            c == '('.code -> tokens += Token.LParen(i).also { i++ }
            c == ')'.code -> tokens += Token.RParen(i).also { i++ }
            c == '&'.code -> tokens += Token.AndOp(i, i + 1).also { i++ }
            c == '|'.code -> tokens += Token.OrOp(i, i + 1).also { i++ }
            c == '!'.code -> tokens += Token.NotOp(i, i + 1).also { i++ }
            c == QUOTE -> {
                val start = i
                val name = StringBuilder()
                i++
                while (true) {
                    if (i >= cps.size) return fail(TagExprErrorCode.UNTERMINATED_STRING, start)
                    val q = cps[i]
                    if (q == BACKSLASH) {
                        val next = cps.getOrNull(i + 1)
                        if (next == QUOTE || next == BACKSLASH) {
                            name.appendCodePoint(next)
                            i += 2
                            continue
                        }
                        return fail(TagExprErrorCode.INVALID_ESCAPE, i)
                    }
                    i++
                    if (q == QUOTE) break
                    name.appendCodePoint(q)
                }
                val value = name.toString()
                if (value.codePoints().allMatch(::isExprSpace)) return fail(TagExprErrorCode.EMPTY_NAME, start)
                tokens += Token.Name(value, quoted = true, start = start, end = i)
            }
            c == BACKSLASH -> return fail(TagExprErrorCode.UNEXPECTED_CHARACTER, i)
            else -> {
                val start = i
                while (i < cps.size && isWordChar(cps[i])) i++
                val word = cps.slice(start, i)
                val lower = word.lowerRoot()
                tokens += when {
                    lower == "and" -> Token.AndOp(start, i)
                    lower == "or" -> Token.OrOp(start, i)
                    lower == "not" -> Token.NotOp(start, i)
                    hasTypePrefix(word) -> {
                        val type = snippetTypeOf(cps.slice(start + TYPE_PREFIX.length, i))
                            ?: return fail(TagExprErrorCode.UNKNOWN_TYPE, start)
                        Token.TypeTerm(type, start, i)
                    }
                    else -> Token.Name(word, quoted = false, start = start, end = i)
                }
            }
        }
    }
    return LexResult.Tokens(tokens)
}

/** The tokens of [text] up to the first lexer error (for completion and insertion around a caret). */
internal fun lexLenient(text: String): List<Token> = when (val result = lex(text)) {
    is LexResult.Tokens -> result.tokens
    is LexResult.Failed -> result.tokens
}
