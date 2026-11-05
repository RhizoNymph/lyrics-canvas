package dev.nymph.lyrics.logic.wordbank

import dev.nymph.lyrics.logic.tags.TagFilter
import dev.nymph.lyrics.logic.tags.format
import dev.nymph.lyrics.logic.tags.matches
import dev.nymph.lyrics.model.BankRhymeAnchor
import dev.nymph.lyrics.model.BankWord
import dev.nymph.lyrics.model.BankWordId
import dev.nymph.lyrics.model.Tag
import dev.nymph.lyrics.model.TagId
import java.util.Locale

/** A word matches as a typeless subject: tag terms by its tag names, every `type:` term false. */
fun TagFilter.matches(word: BankWord): Boolean = matches(null, word.tags.map { it.name })

/** The words the filter shows, in list order. */
fun visibleWords(words: List<BankWord>, filter: TagFilter): List<BankWord> = words.filter { filter.matches(it) }

/** "N words (M shown)". */
fun countLabel(total: Int, shown: Int): String = "$total ${if (total == 1) "word" else "words"} ($shown shown)"

/** The selected words in list order. */
fun selectedInOrder(words: List<BankWord>, selected: Set<BankWordId>): List<BankWord> = words.filter { it.id in selected }

/** The snippet draft: the selected words in list order, one per line. */
fun draftText(words: List<BankWord>, selected: Set<BankWordId>): String = selectedInOrder(words, selected).joinToString("\n") { it.text }

/** The selection without words the list no longer shows. */
fun pruneSelection(selected: Set<BankWordId>, visible: List<BankWord>): Set<BankWordId> {
    val shown = visible.mapTo(HashSet()) { it.id }
    return selected.filterTo(LinkedHashSet()) { it in shown }
}

/** Deleting more than this many words asks first. */
const val DELETE_CONFIRM_ABOVE: Int = 5

fun needsDeleteConfirm(count: Int): Boolean = count > DELETE_CONFIRM_ABOVE

/** The `tag_filter` to send for the applied filter: its normalized text, or none for everything. */
fun tagFilterParam(filter: TagFilter): String? = when (filter) {
    TagFilter.Everything -> null
    is TagFilter.Where -> filter.format()
}

/** The rhymes-with anchor: the typed text when there is one, else the one selected word, else none. */
fun rhymesWithAnchor(typed: String, selected: Set<BankWordId>): BankRhymeAnchor? {
    val text = typed.trim()
    return when {
        text.isNotEmpty() -> BankRhymeAnchor.Text(text)
        selected.size == 1 -> BankRhymeAnchor.Word(selected.single())
        else -> null
    }
}

private fun List<Tag>.sortedPills(): List<Tag> = sortedBy { it.name.lowercase(Locale.ROOT) }

/** After a tag rename / recolour: every word carrying it shows the new name and colour (pills re-sorted). */
fun List<BankWord>.withTagEdited(tag: Tag): List<BankWord> = map { word ->
    if (word.tags.none { it.id == tag.id }) word else word.copy(tags = word.tags.map { if (it.id == tag.id) tag else it }.sortedPills())
}

/** After a tag delete: no word carries it. */
fun List<BankWord>.withTagDeleted(tag: TagId): List<BankWord> = map { word ->
    if (word.tags.none { it.id == tag }) word else word.copy(tags = word.tags.filter { it.id != tag })
}

/** The server's copies of [updated] replace the old ones in place; words not in the list go first, in the given order. */
fun List<BankWord>.mergedWith(updated: List<BankWord>): List<BankWord> {
    val byId = updated.associateBy { it.id }
    val known = mapTo(HashSet()) { it.id }
    val fresh = updated.filter { it.id !in known }.distinctBy { it.id }
    return fresh + map { byId[it.id] ?: it }
}

fun List<BankWord>.withoutIds(ids: Set<BankWordId>): List<BankWord> = filter { it.id !in ids }

/** Tag ids of each picked word that is still in the list. */
fun wordTagLinks(words: List<BankWord>, ids: Set<BankWordId>): Map<BankWordId, Set<TagId>> =
    words.filter { it.id in ids }.associate { word -> word.id to word.tags.mapTo(HashSet()) { it.id } }

/** The add result line. */
fun addSummary(created: Int, existing: Int): String = "Added $created, $existing already in bank"
