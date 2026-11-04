package dev.nymph.lyrics.logic.tags

import dev.nymph.lyrics.model.SnippetId
import dev.nymph.lyrics.model.TagId
import dev.nymph.lyrics.model.UserTag
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class TagPickerTest {
    private fun tag(id: String, name: String) = UserTag(TagId(id), name, "#6366f1", 0, "")

    private val love = tag("t1", "love")
    private val night = tag("t2", "Night")
    private val hook = tag("t3", "hook idea")
    private val tags = listOf(night, love, hook)
    private val a = SnippetId("a")
    private val b = SnippetId("b")

    @Test
    fun `rows are sorted, searched and show mixed state`() {
        val links = mapOf(a to setOf(love.id, night.id), b to setOf(love.id))
        val rows = pickerRows(tags, links, query = "")
        assertEquals(listOf("hook idea", "love", "Night"), rows.map { it.tag.name })
        assertEquals(listOf(TagCheck.NONE, TagCheck.ALL, TagCheck.SOME), rows.map { it.check })
        assertEquals(listOf("hook idea"), pickerRows(tags, links, query = " IDEA").map { it.tag.name })
    }

    @Test
    fun `no snippets means nothing is checked`() {
        assertEquals(listOf(TagCheck.NONE, TagCheck.NONE, TagCheck.NONE), pickerRows(tags, emptyMap(), "").map { it.check })
    }

    @Test
    fun `toggle links the snippets missing the tag unless all have it`() {
        val links = mapOf(a to setOf(love.id), b to emptySet())
        assertEquals(TagToggle.Link(listOf(b)), planToggle(love.id, links))
        assertEquals(TagToggle.Link(listOf(a, b)), planToggle(night.id, links))
        assertEquals(TagToggle.Unlink(listOf(a, b)), planToggle(love.id, mapOf(a to setOf(love.id), b to setOf(love.id))))
        assertEquals(TagToggle.Link(emptyList()), planToggle(love.id, emptyMap()))
    }

    @Test
    fun `create is offered for a valid query without an exact match`() {
        assertEquals("Love song", createOffer(tags, "  Love song "))
        assertNull(createOffer(tags, "LOVE"))
        assertNull(createOffer(tags, "   "))
        assertNull(createOffer(tags, "a\u0007b"))
    }

    @Test
    fun `tag names are validated like the server`() {
        assertEquals(TagNameCheck.Ok("love"), validateTagName("  love\t"))
        assertEquals(TagNameCheck.Invalid(TagNameProblem.EMPTY), validateTagName("   "))
        assertEquals(TagNameCheck.Invalid(TagNameProblem.TOO_LONG), validateTagName("🔥".repeat(101)))
        assertEquals(TagNameCheck.Ok("🔥".repeat(100)), validateTagName("🔥".repeat(100)))
        assertEquals(TagNameCheck.Invalid(TagNameProblem.CONTROL_CHARACTER), validateTagName("a\nb"))
    }

    @Test
    fun `colours`() {
        assertEquals(true, isValidTagColor("#A1b2C3"))
        assertEquals(false, isValidTagColor("#abc"))
        assertEquals(false, isValidTagColor("123456"))
        assertEquals(DEFAULT_TAG_COLOR, TAG_COLORS.first())
        assertEquals(true, TAG_COLORS.all(::isValidTagColor))
    }
}
