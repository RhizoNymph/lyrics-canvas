package dev.nymph.lyrics.logic.tags

/** True when [name] can be written without quotes: a bare word that is not a keyword or a `type:` term. */
fun isBareTagName(name: String): Boolean =
    name.isNotEmpty() && name.codePoints().allMatch(::isWordChar) && !isKeyword(name) && !hasTypePrefix(name)

/** [name] as it is written in an expression: bare when possible, else quoted with `\` and `"` escaped. */
fun formatTagName(name: String): String =
    if (isBareTagName(name)) name else "\"" + name.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

/** The normalized text of the filter (`""` for [TagFilter.Everything]); `format(parse(format(x))) == format(x)`. */
fun TagFilter.format(): String = when (this) {
    TagFilter.Everything -> ""
    is TagFilter.Where -> expr.format()
}

fun TagExpr.format(): String = when (this) {
    is TagExpr.Tag -> formatTagName(name)
    is TagExpr.Type -> "type:${value.wire}"
    is TagExpr.Not -> "!" + if (child is TagExpr.And || child is TagExpr.Or) "(${child.format()})" else child.format()
    is TagExpr.And -> children.joinToString(" & ") { if (it is TagExpr.Or) "(${it.format()})" else it.format() }
    is TagExpr.Or -> children.joinToString(" | ") { it.format() }
}
