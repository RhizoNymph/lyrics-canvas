package dev.nymph.lyrics.logic.tags

import dev.nymph.lyrics.model.SnippetType

/** The wire name of a snippet type (`verse`, ...), as written after `type:`. */
val SnippetType.wire: String get() = name.lowercase()

/**
 * A parsed tag expression. [And] and [Or] always have at least two children and never a child of
 * their own kind (nested same-kind groups are flattened by [and] / [or]); parentheses leave no node.
 */
sealed interface TagExpr {
    @ConsistentCopyVisibility
    data class Or internal constructor(val children: List<TagExpr>) : TagExpr {
        init {
            require(children.size >= 2 && children.none { it is Or })
        }
    }

    @ConsistentCopyVisibility
    data class And internal constructor(val children: List<TagExpr>) : TagExpr {
        init {
            require(children.size >= 2 && children.none { it is And })
        }
    }

    data class Not(val child: TagExpr) : TagExpr

    /** A tag name, case preserved; it matches case-insensitively. */
    data class Tag(val name: String) : TagExpr

    data class Type(val value: SnippetType) : TagExpr

    companion object {
        /** OR of [items], flattening nested ORs; a single item is itself. */
        fun or(items: List<TagExpr>): TagExpr = flatten(items, { it is Or }, { (it as Or).children }, ::Or)

        /** AND of [items], flattening nested ANDs; a single item is itself. */
        fun and(items: List<TagExpr>): TagExpr = flatten(items, { it is And }, { (it as And).children }, ::And)

        private inline fun flatten(
            items: List<TagExpr>,
            isKind: (TagExpr) -> Boolean,
            children: (TagExpr) -> List<TagExpr>,
            build: (List<TagExpr>) -> TagExpr,
        ): TagExpr {
            require(items.isNotEmpty())
            if (items.size == 1) return items[0]
            return build(items.flatMap { if (isKind(it)) children(it) else listOf(it) })
        }
    }
}

/** A valid filter: the empty expression matches everything. */
sealed interface TagFilter {
    data object Everything : TagFilter

    data class Where(val expr: TagExpr) : TagFilter
}

/** The outcome of [parseTagExpression]. */
sealed interface TagExprParse {
    data class Valid(val filter: TagFilter) : TagExprParse

    data class Invalid(val error: TagExprError) : TagExprParse
}
