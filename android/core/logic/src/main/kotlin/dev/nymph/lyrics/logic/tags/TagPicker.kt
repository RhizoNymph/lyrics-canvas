package dev.nymph.lyrics.logic.tags

import dev.nymph.lyrics.model.TagId
import dev.nymph.lyrics.model.TaggedItemId
import dev.nymph.lyrics.model.UserTag

/** How many of the picked items (snippets or word bank words) carry a tag. */
enum class TagCheck { NONE, SOME, ALL }

data class PickerRow(val tag: UserTag, val check: TagCheck)

/**
 * The picker checklist: [tags] sorted by lowercase name, narrowed to names containing the
 * trimmed [query] (case-insensitively), each with its check state across [links] (the picked
 * items' tag ids, keyed by snippet or word id). No items: every row is [TagCheck.NONE].
 */
fun pickerRows(tags: List<UserTag>, links: Map<out TaggedItemId, Set<TagId>>, query: String): List<PickerRow> {
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

/** What tapping a row does: link to the items missing it, or (all have it) unlink from all. */
sealed interface TagToggle {
    val targets: List<TaggedItemId>

    data class Link(override val targets: List<TaggedItemId>) : TagToggle

    data class Unlink(override val targets: List<TaggedItemId>) : TagToggle
}

/** The targets of one kind (the picker only ever holds one kind at a time). */
inline fun <reified K : TaggedItemId> TagToggle.targetsOf(): List<K> = targets.filterIsInstance<K>()

fun planToggle(tag: TagId, links: Map<out TaggedItemId, Set<TagId>>): TagToggle {
    val missing = links.filterValues { tag !in it }.keys.toList()
    return if (links.isNotEmpty() && missing.isEmpty()) TagToggle.Unlink(links.keys.toList()) else TagToggle.Link(missing)
}

/** The name "Create '<query>'" would create: a valid name no tag has (case-insensitively), else null. */
fun createOffer(tags: List<UserTag>, query: String): String? {
    val name = (validateTagName(query) as? TagNameCheck.Ok)?.name ?: return null
    return if (tags.any { it.name.lowerRoot() == name.lowerRoot() }) null else name
}
