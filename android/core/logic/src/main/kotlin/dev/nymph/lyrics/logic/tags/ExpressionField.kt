package dev.nymph.lyrics.logic.tags

/**
 * An expression text field: text, caret (code points) and its parse. Shared by the canvas filter
 * bar and the rhyme sheet's "Only my lines matching" field. Equality is by text and caret.
 */
data class ExpressionField(val text: String = "", val caret: Int = 0) {
    val parse: TagExprParse = parseTagExpression(text)

    /** Why the text is invalid, or null. */
    val error: TagExprError? get() = (parse as? TagExprParse.Invalid)?.error

    /** The filter when the text is valid, else null. */
    val validFilter: TagFilter? get() = (parse as? TagExprParse.Valid)?.filter

    /** New text and caret (from the text field); the caret is clamped. */
    fun edit(text: String, caret: Int): ExpressionField = ExpressionField(text, caret.coerceIn(0, codePointLength(text)))

    fun completion(tagNames: List<String>, limit: Int = COMPLETION_LIMIT): Completion = completeAt(text, caret, tagNames, limit)

    fun accept(completion: Completion, suggestion: Suggestion): ExpressionField = completion.apply(text, suggestion).toField()

    fun insertTag(name: String): ExpressionField = insertTagAt(text, caret, name).toField()

    fun cleared(): ExpressionField = ExpressionField()

    /** Replace the whole text (a preset), caret at the end. */
    fun replaced(text: String): ExpressionField = ExpressionField(text, codePointLength(text))
}

private fun TextEdit.toField(): ExpressionField = ExpressionField(text, caret)

/**
 * The canvas filter bar. [applied] is the filter in force: the last valid text's filter
 * ([appliedText]); an invalid edit is shown ([invalid], `input.error`) but leaves it alone.
 */
data class FilterBarState(
    val input: ExpressionField,
    val applied: TagFilter,
    val appliedText: String,
) {
    val invalid: Boolean get() = input.error != null

    private fun with(next: ExpressionField): FilterBarState {
        val filter = next.validFilter ?: return copy(input = next)
        return FilterBarState(next, filter, next.text)
    }

    fun edit(text: String, caret: Int): FilterBarState = with(input.edit(text, caret))

    fun accept(completion: Completion, suggestion: Suggestion): FilterBarState = with(input.accept(completion, suggestion))

    fun insertTag(name: String): FilterBarState = with(input.insertTag(name))

    fun applyPreset(expression: String): FilterBarState = with(input.replaced(expression))

    fun clear(): FilterBarState = with(input.cleared())

    /**
     * After tag [from] was renamed to [to]: the bar with its valid filter rewritten (normalized),
     * or null when the text is invalid or does not mention the tag.
     */
    fun renameTag(from: String, to: String): FilterBarState? {
        val filter = input.validFilter ?: return null
        if (!filter.mentionsTag(from)) return null
        return applyPreset(filter.renameTag(from, to).format())
    }

    companion object {
        /** The bar for a remembered text, caret at the end (an invalid text applies nothing). */
        fun restored(text: String): FilterBarState = FilterBarState(ExpressionField(), TagFilter.Everything, "").applyPreset(text)
    }
}

/** What the rhyme request does with the "Only my lines matching" field. */
sealed interface RhymeTagFilter {
    /** Empty field, or the notes source is off: no `tag_filter`. */
    data object None : RhymeTagFilter

    /** Send this normalized expression as `tag_filter`. */
    data class Send(val expression: String) : RhymeTagFilter

    /** Known-invalid: do not call the server. */
    data class Blocked(val error: TagExprError) : RhymeTagFilter
}

fun rhymeTagFilter(field: ExpressionField, notesEnabled: Boolean): RhymeTagFilter {
    if (!notesEnabled) return RhymeTagFilter.None
    return when (val parse = field.parse) {
        is TagExprParse.Invalid -> RhymeTagFilter.Blocked(parse.error)
        is TagExprParse.Valid -> when (val filter = parse.filter) {
            TagFilter.Everything -> RhymeTagFilter.None
            is TagFilter.Where -> RhymeTagFilter.Send(filter.format())
        }
    }
}
