package dev.nymph.lyrics.logic.tags

/**
 * Parse a tag expression (grammar in docs/features/tag_expressions.md):
 * ```
 * or      := and ( "|" and )*
 * and     := unary ( "&"? unary )*     -- juxtaposition is AND
 * unary   := "!" unary | primary
 * primary := "(" or ")" | TAG | TYPE
 * ```
 * Empty or whitespace-only input is [TagFilter.Everything]. Never throws.
 */
fun parseTagExpression(text: String): TagExprParse {
    val tokens = when (val lexed = lex(text)) {
        is LexResult.Failed -> return TagExprParse.Invalid(lexed.error)
        is LexResult.Tokens -> lexed.tokens
    }
    if (tokens.isEmpty()) return TagExprParse.Valid(TagFilter.Everything)
    return when (val result = Parser(tokens, codePointLength(text)).parse()) {
        is ParseStep.Ok -> TagExprParse.Valid(TagFilter.Where(result.expr))
        is ParseStep.Err -> TagExprParse.Invalid(result.error)
    }
}

private sealed interface ParseStep {
    data class Ok(val expr: TagExpr) : ParseStep

    data class Err(val error: TagExprError) : ParseStep
}

private class Parser(private val tokens: List<Token>, private val length: Int) {
    private var pos = 0

    private fun peek(): Token? = tokens.getOrNull(pos)

    private fun err(code: TagExprErrorCode, at: Int) = ParseStep.Err(exprError(code, at))

    fun parse(): ParseStep {
        val result = parseOr(0)
        if (result is ParseStep.Err) return result
        val extra = peek() ?: return result
        return err(TagExprErrorCode.UNEXPECTED_TOKEN, extra.start)
    }

    private fun parseOr(depth: Int): ParseStep {
        val items = ArrayList<TagExpr>()
        while (true) {
            when (val item = parseAnd(depth)) {
                is ParseStep.Err -> return item
                is ParseStep.Ok -> items += item.expr
            }
            if (peek() !is Token.OrOp) break
            pos++
        }
        return ParseStep.Ok(TagExpr.or(items))
    }

    private fun parseAnd(depth: Int): ParseStep {
        val items = ArrayList<TagExpr>()
        while (true) {
            when (val item = parseUnary(depth)) {
                is ParseStep.Err -> return item
                is ParseStep.Ok -> items += item.expr
            }
            val next = peek()
            when {
                next is Token.AndOp -> pos++
                next != null && next.beginsOperand -> Unit
                else -> break
            }
        }
        return ParseStep.Ok(TagExpr.and(items))
    }

    private fun parseUnary(depth: Int): ParseStep {
        if (peek() is Token.NotOp) {
            pos++
            return when (val child = parseUnary(depth)) {
                is ParseStep.Err -> child
                is ParseStep.Ok -> ParseStep.Ok(TagExpr.Not(child.expr))
            }
        }
        return parsePrimary(depth)
    }

    private fun parsePrimary(depth: Int): ParseStep {
        val token = peek() ?: return err(TagExprErrorCode.UNEXPECTED_END, length)
        pos++
        return when (token) {
            is Token.Name -> ParseStep.Ok(TagExpr.Tag(token.name))
            is Token.TypeTerm -> ParseStep.Ok(TagExpr.Type(token.type))
            is Token.LParen -> {
                if (depth + 1 > MAX_PAREN_DEPTH) return err(TagExprErrorCode.TOO_DEEP, token.start)
                if (peek() is Token.RParen) return err(TagExprErrorCode.EMPTY_GROUP, token.start)
                val inner = parseOr(depth + 1)
                if (inner is ParseStep.Err) return inner
                when (val close = peek()) {
                    null -> err(TagExprErrorCode.UNCLOSED_PAREN, token.start)
                    is Token.RParen -> inner.also { pos++ }
                    else -> err(TagExprErrorCode.UNEXPECTED_TOKEN, close.start)
                }
            }
            is Token.RParen, is Token.AndOp, is Token.OrOp, is Token.NotOp -> err(TagExprErrorCode.UNEXPECTED_TOKEN, token.start)
        }
    }
}
