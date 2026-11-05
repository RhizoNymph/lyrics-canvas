package dev.nymph.lyrics.logic.wordbank

import dev.nymph.lyrics.logic.tags.TagExprParse
import dev.nymph.lyrics.logic.tags.TagFilter
import dev.nymph.lyrics.logic.tags.parseTagExpression
import dev.nymph.lyrics.model.BankRhymeAnchor
import dev.nymph.lyrics.model.BankWord
import dev.nymph.lyrics.model.BankWordId
import dev.nymph.lyrics.model.Tag
import dev.nymph.lyrics.model.TagId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WordBankListTest {
    private val love = Tag(TagId("t1"), "Love", "#6366f1")
    private val dark = Tag(TagId("t2"), "dark", "#000000")

    private fun word(id: String, text: String, vararg tags: Tag) = BankWord(BankWordId(id), text, tags.toList(), "2026-11-04")

    private val night = word("w1", "night", dark)
    private val heart = word("w2", "heart", love)
    private val light = word("w3", "light", love, dark)
    private val rain = word("w4", "rain")
    private val words = listOf(night, heart, light, rain)

    private fun filter(text: String) = (parseTagExpression(text) as TagExprParse.Valid).filter

    @Test
    fun `words match like typeless snippets`() {
        assertTrue(filter("love").matches(heart))
        assertTrue(filter("LOVE & dark").matches(light))
        assertFalse(filter("type:verse").matches(heart))
        assertTrue(filter("!type:verse").matches(rain))
        assertTrue(filter("love | type:hook").matches(heart))
        assertTrue(TagFilter.Everything.matches(rain))
    }

    @Test
    fun `visible words keep the list order`() {
        assertEquals(listOf(heart, light), visibleWords(words, filter("love")))
        assertEquals(words, visibleWords(words, TagFilter.Everything))
    }

    @Test
    fun `count label`() {
        assertEquals("4 words (2 shown)", countLabel(4, 2))
        assertEquals("1 word (1 shown)", countLabel(1, 1))
        assertEquals("0 words (0 shown)", countLabel(0, 0))
    }

    @Test
    fun `selection is read in list order`() {
        val selected = setOf(rain.id, night.id, light.id)
        assertEquals(listOf(night, light, rain), selectedInOrder(words, selected))
    }

    @Test
    fun `draft is one selected word per line in list order`() {
        assertEquals("night\nlight\nrain", draftText(words, setOf(rain.id, light.id, night.id)))
        assertEquals("", draftText(words, emptySet()))
    }

    @Test
    fun `selection prune keeps only visible ids`() {
        assertEquals(setOf(heart.id), pruneSelection(setOf(heart.id, night.id), listOf(heart, light)))
    }

    @Test
    fun `delete asks first above five words`() {
        assertFalse(needsDeleteConfirm(5))
        assertTrue(needsDeleteConfirm(6))
    }

    @Test
    fun `tag filter parameter is the normalized expression or none`() {
        assertNull(tagFilterParam(TagFilter.Everything))
        assertEquals("love & dark", tagFilterParam(filter("love   dark")))
    }

    @Test
    fun `rhymes-with anchor prefers typed text, else exactly one selected word`() {
        assertEquals(BankRhymeAnchor.Text("moon"), rhymesWithAnchor("  moon ", setOf(night.id, heart.id)))
        assertEquals(BankRhymeAnchor.Word(night.id), rhymesWithAnchor("   ", setOf(night.id)))
        assertNull(rhymesWithAnchor("", setOf(night.id, heart.id)))
        assertNull(rhymesWithAnchor("", emptySet()))
    }

    @Test
    fun `tag edits and deletes refresh word pills`() {
        val renamed = Tag(love.id, "romance", "#ff0000")
        val edited = words.withTagEdited(renamed)
        assertEquals(listOf(renamed), edited[1].tags)
        assertEquals(listOf(dark, renamed), edited[2].tags.sortedBy { it.name })
        val deleted = words.withTagDeleted(dark.id)
        assertEquals(emptyList<Tag>(), deleted[0].tags)
        assertEquals(listOf(love), deleted[2].tags)
    }

    @Test
    fun `edited pills stay sorted by lowercase name`() {
        val w = word("w9", "x", Tag(TagId("a"), "alpha", "#000000"), Tag(TagId("b"), "beta", "#000000"))
        assertEquals(listOf("beta", "Zulu"), listOf(w).withTagEdited(Tag(TagId("a"), "Zulu", "#000000"))[0].tags.map { it.name })
    }

    @Test
    fun `merging server words replaces by id and new words go first`() {
        val renamed = night.copy(text = "Night")
        val added = word("w5", "snow")
        assertEquals(listOf(added, renamed, heart, light, rain), words.mergedWith(listOf(renamed, added)))
    }

    @Test
    fun `removing words`() {
        assertEquals(listOf(night, rain), words.withoutIds(setOf(heart.id, light.id)))
    }

    @Test
    fun `tag links of the picked words`() {
        assertEquals(mapOf(night.id to setOf(dark.id), rain.id to emptySet()), wordTagLinks(words, setOf(night.id, rain.id, BankWordId("gone"))))
    }

    @Test
    fun `add result summary`() {
        assertEquals("Added 2, 1 already in bank", addSummary(2, 1))
        assertEquals("Added 0, 3 already in bank", addSummary(0, 3))
    }
}
