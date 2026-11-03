package dev.nymph.lyrics.logic.tags

/** Why an expression is invalid; the wire codes are shared with the backend (`expression_error.code`). */
enum class TagExprErrorCode(val wire: String) {
    /** More than [MAX_EXPRESSION_LENGTH] code points (at that limit). */
    TOO_LONG("too_long"),

    /** A backslash in a quoted name not followed by `"` or `\` (at the backslash). */
    INVALID_ESCAPE("invalid_escape"),

    /** A quoted name without its closing quote (at the opening quote). */
    UNTERMINATED_STRING("unterminated_string"),

    /** A quoted name that is empty or only whitespace (at the opening quote). */
    EMPTY_NAME("empty_name"),

    /** A backslash outside quotes. */
    UNEXPECTED_CHARACTER("unexpected_character"),

    /** `type:` followed by something other than a snippet type (at the word). */
    UNKNOWN_TYPE("unknown_type"),

    /** A token where it cannot go. */
    UNEXPECTED_TOKEN("unexpected_token"),

    /** The input ended where a term was expected (at the input length). */
    UNEXPECTED_END("unexpected_end"),

    /** A `(` never closed (at the `(`). */
    UNCLOSED_PAREN("unclosed_paren"),

    /** `()` (at the `(`). */
    EMPTY_GROUP("empty_group"),

    /** Parentheses nested deeper than [MAX_PAREN_DEPTH] (at the offending `(`). */
    TOO_DEEP("too_deep"),
    ;

    companion object {
        fun parse(wire: String?): TagExprErrorCode? = entries.firstOrNull { it.wire == wire }
    }
}

/** A located expression error; [position] is a code point offset into the input. */
data class TagExprError(val code: TagExprErrorCode, val position: Int, val message: String)

const val MAX_EXPRESSION_LENGTH: Int = 500
const val MAX_PAREN_DEPTH: Int = 32

internal fun exprError(code: TagExprErrorCode, position: Int): TagExprError = TagExprError(code, position, defaultMessage(code))

/** The client's message for a code (the server's text is not part of the contract). */
fun defaultMessage(code: TagExprErrorCode): String = when (code) {
    TagExprErrorCode.TOO_LONG -> "The filter is too long (at most $MAX_EXPRESSION_LENGTH characters)"
    TagExprErrorCode.INVALID_ESCAPE -> "Only \\\" and \\\\ can be escaped inside quotes"
    TagExprErrorCode.UNTERMINATED_STRING -> "Missing closing quote"
    TagExprErrorCode.EMPTY_NAME -> "A quoted tag name cannot be empty"
    TagExprErrorCode.UNEXPECTED_CHARACTER -> "A backslash is only allowed inside quotes"
    TagExprErrorCode.UNKNOWN_TYPE -> "Unknown type (use verse, chorus, bridge, hook or freestyle)"
    TagExprErrorCode.UNEXPECTED_TOKEN -> "Unexpected symbol here"
    TagExprErrorCode.UNEXPECTED_END -> "The filter ends too early"
    TagExprErrorCode.UNCLOSED_PAREN -> "This parenthesis is never closed"
    TagExprErrorCode.EMPTY_GROUP -> "Empty parentheses"
    TagExprErrorCode.TOO_DEEP -> "Too many nested parentheses (at most $MAX_PAREN_DEPTH)"
}
