package dev.nymph.lyrics.logic.tags

import dev.nymph.lyrics.logic.snippet
import dev.nymph.lyrics.model.SnippetId
import dev.nymph.lyrics.model.Tag
import dev.nymph.lyrics.model.TagId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SnippetTagsTest {
    private val love = Tag(TagId("t1"), "love", "#111111")
    private val night = Tag(TagId("t2"), "night", "#222222")

    @Test
    fun `link appends once and unlink removes`() {
        val s = snippet("a").copy(tags = listOf(love))
        assertEquals(listOf(love, night), s.withTagLinked(night).tags)
        assertEquals(listOf(love), s.withTagLinked(love.copy(name = "LOVE")).tags)
        assertEquals(emptyList<Tag>(), s.withTagUnlinked(love.id).tags)
    }

    @Test
    fun `edits and deletes reach every snippet carrying the tag`() {
        val list = listOf(snippet("a").copy(tags = listOf(love, night)), snippet("b").copy(tags = listOf(night)), snippet("c"))
        val renamed = night.copy(name = "dark", color = "#333333")
        assertEquals(listOf(listOf(love, renamed), listOf(renamed), emptyList()), list.withTagEdited(renamed).map { it.tags })
        assertEquals(listOf(listOf(love), emptyList(), emptyList()), list.withTagDeleted(night.id).map { it.tags })
    }

    @Test
    fun `tag links of a selection`() {
        val list = listOf(snippet("a").copy(tags = listOf(love)), snippet("b"))
        assertEquals(
            mapOf(SnippetId("a") to setOf(love.id), SnippetId("b") to emptySet()),
            tagLinks(list.associateBy { it.id }, setOf(SnippetId("a"), SnippetId("b"), SnippetId("gone"))),
        )
    }
}
