package dev.nymph.lyrics.logic.tags

import dev.nymph.lyrics.model.SnippetId
import dev.nymph.lyrics.model.TagId
import dev.nymph.lyrics.model.UserTag

/** How many of the picked snippets carry a tag. */
enum class TagCheck { NONE, SOME, ALL }

data class PickerRow(val tag: UserTag, val check: TagCheck)

/**
 * The picker checklist: [tags] sorted by lowercase name, narrowed to names containing the
 * trimmed [query] (case-insensitively), each with its check state across [links] (the picked
 * snippets' tag ids). No snippets: every row is [TagCheck.NONE].
 */
fun pickerRows(tags: List<UserTag>, links: Map<SnippetId, Set<TagId>>, query: String): List<PickerRow> {
    val needle = query.trimExpr().lowerRoot()
    return tags
        .filter { needle.isEmpty() || needle in it.name.lowerRoot() }
        .sortedBy { it.name.lowerRoot() }
        .map { tag ->
            val carrying = links.values.count { tag.id in it }
            val check = when {
                carrying == 0 -> TagCheck.NONE
                carrying == links.size -> TagCheck.ALL
                else -> TagCheck.SOME
            }
            PickerRow(tag, check)
        }
}

/** What tapping a row does: link to the snippets missing it, or (all have it) unlink from all. */
sealed interface TagToggle {
    val snippets: List<SnippetId>

    data class Link(override val snippets: List<SnippetId>) : TagToggle

    data class Unlink(override val snippets: List<SnippetId>) : TagToggle
}

fun planToggle(tag: TagId, links: Map<SnippetId, Set<TagId>>): TagToggle {
    val missing = links.filterValues { tag !in it }.keys.toList()
    return if (links.isNotEmpty() && missing.isEmpty()) TagToggle.Unlink(links.keys.toList()) else TagToggle.Link(missing)
}

/** The name "Create '<query>'" would create: a valid name no tag has (case-insensitively), else null. */
fun createOffer(tags: List<UserTag>, query: String): String? {
    val name = (validateTagName(query) as? TagNameCheck.Ok)?.name ?: return null
    return if (tags.any { it.name.lowerRoot() == name.lowerRoot() }) null else name
}
