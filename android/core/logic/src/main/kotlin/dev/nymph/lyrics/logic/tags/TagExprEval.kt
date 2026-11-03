package dev.nymph.lyrics.logic.tags

import dev.nymph.lyrics.model.Snippet
import dev.nymph.lyrics.model.SnippetType

/**
 * Whether a snippet of [type] carrying [tagNames] matches. Tag terms compare under
 * `lowercase(Locale.ROOT)`; unknown names match nothing; type terms compare exactly.
 */
fun TagFilter.matches(type: SnippetType, tagNames: Collection<String>): Boolean = when (this) {
    TagFilter.Everything -> true
    is TagFilter.Where -> expr.matches(type, tagNames.mapTo(HashSet()) { it.lowerRoot() })
}

fun TagFilter.matches(snippet: Snippet): Boolean = matches(snippet.snippetType, snippet.tags.map { it.name })

private fun TagExpr.matches(type: SnippetType, lowerNames: Set<String>): Boolean = when (this) {
    is TagExpr.Tag -> name.lowerRoot() in lowerNames
    is TagExpr.Type -> value == type
    is TagExpr.Not -> !child.matches(type, lowerNames)
    is TagExpr.And -> children.all { it.matches(type, lowerNames) }
    is TagExpr.Or -> children.any { it.matches(type, lowerNames) }
}

/** Replace every tag term equal to [from] (case-insensitively) with [to]; type terms are untouched. */
fun TagFilter.renameTag(from: String, to: String): TagFilter = when (this) {
    TagFilter.Everything -> this
    is TagFilter.Where -> TagFilter.Where(expr.renameTag(from.lowerRoot(), to))
}

private fun TagExpr.renameTag(fromLower: String, to: String): TagExpr = when (this) {
    is TagExpr.Tag -> if (name.lowerRoot() == fromLower) TagExpr.Tag(to) else this
    is TagExpr.Type -> this
    is TagExpr.Not -> TagExpr.Not(child.renameTag(fromLower, to))
    is TagExpr.And -> TagExpr.And(children.map { it.renameTag(fromLower, to) })
    is TagExpr.Or -> TagExpr.Or(children.map { it.renameTag(fromLower, to) })
}

/** True when some tag term equals [name] case-insensitively. */
fun TagFilter.mentionsTag(name: String): Boolean = this is TagFilter.Where && expr.mentions(name.lowerRoot())

private fun TagExpr.mentions(lower: String): Boolean = when (this) {
    is TagExpr.Tag -> name.lowerRoot() == lower
    is TagExpr.Type -> false
    is TagExpr.Not -> child.mentions(lower)
    is TagExpr.And -> children.any { it.mentions(lower) }
    is TagExpr.Or -> children.any { it.mentions(lower) }
}
