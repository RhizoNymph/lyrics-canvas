package dev.nymph.lyrics.logic.tags

/** Longest tag or preset name, in code points (the server's limit). */
const val MAX_TAG_NAME_LENGTH: Int = 100

enum class TagNameProblem { EMPTY, TOO_LONG, CONTROL_CHARACTER }

sealed interface TagNameCheck {
    /** The trimmed name the server will store. */
    data class Ok(val name: String) : TagNameCheck

    data class Invalid(val problem: TagNameProblem) : TagNameCheck
}

/** Trim with the Python whitespace rule (the server's `str.strip`). */
internal fun String.trimExpr(): String {
    val cps = toCodePoints()
    var s = 0
    var e = cps.size
    while (s < e && isExprSpace(cps[s])) s++
    while (e > s && isExprSpace(cps[e - 1])) e--
    return cps.slice(s, e)
}

/** The server's tag / preset name rule: trimmed, 1–100 code points, no control characters. */
fun validateTagName(raw: String): TagNameCheck {
    val name = raw.trimExpr()
    return when {
        name.isEmpty() -> TagNameCheck.Invalid(TagNameProblem.EMPTY)
        codePointLength(name) > MAX_TAG_NAME_LENGTH -> TagNameCheck.Invalid(TagNameProblem.TOO_LONG)
        name.codePoints().anyMatch { Character.getType(it) == Character.CONTROL.toInt() } -> TagNameCheck.Invalid(TagNameProblem.CONTROL_CHARACTER)
        else -> TagNameCheck.Ok(name)
    }
}

/** The server's default tag colour. */
const val DEFAULT_TAG_COLOR: String = "#6366f1"

/** The colours offered when creating or recolouring a tag (all `#rrggbb`). */
val TAG_COLORS: List<String> = listOf(
    DEFAULT_TAG_COLOR, "#ef4444", "#f97316", "#eab308", "#22c55e", "#14b8a6", "#3b82f6", "#a855f7", "#ec4899", "#64748b",
)

private val COLOR = Regex("^#[0-9a-fA-F]{6}$")

fun isValidTagColor(hex: String): Boolean = COLOR.matches(hex)
