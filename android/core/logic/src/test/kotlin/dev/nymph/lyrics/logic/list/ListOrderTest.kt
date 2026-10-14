package dev.nymph.lyrics.logic.list

import dev.nymph.lyrics.logic.snippet
import dev.nymph.lyrics.model.Snippet
import dev.nymph.lyrics.model.SnippetId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Instant

class ListOrderTest {
    private fun s(id: String, content: String, created: String = "2026-01-01T00:00:00Z"): Snippet =
        snippet(id, createdAt = created, contentUpdatedAt = content)

    private fun ids(snippets: List<Snippet>) = snippets.map { it.id.value }

    // ---- parseServerTimestamp ----

    @Test
    fun `parses ISO-8601 with Z`() {
        assertEquals(Instant.parse("2026-09-01T10:00:00Z"), parseServerTimestamp("2026-09-01T10:00:00Z"))
    }

    @Test
    fun `parses the backend's str(datetime) form with and without microseconds`() {
        assertEquals(Instant.parse("2026-09-01T10:00:00Z"), parseServerTimestamp("2026-09-01 10:00:00+00:00"))
        assertEquals(Instant.parse("2026-09-01T10:00:00.250Z"), parseServerTimestamp("2026-09-01 10:00:00.250000+00:00"))
    }

    @Test
    fun `honours the offset`() {
        assertEquals(Instant.parse("2026-09-01T08:00:00Z"), parseServerTimestamp("2026-09-01T10:00:00+02:00"))
    }

    @Test
    fun `a timestamp without offset is UTC`() {
        assertEquals(Instant.parse("2026-09-01T10:00:00Z"), parseServerTimestamp("2026-09-01T10:00:00"))
    }

    @Test
    fun `a bare date is midnight UTC`() {
        assertEquals(Instant.parse("2026-09-01T00:00:00Z"), parseServerTimestamp("2026-09-01"))
    }

    @Test
    fun `garbage and empty strings are null`() {
        assertNull(parseServerTimestamp(""))
        assertNull(parseServerTimestamp("yesterday"))
        assertNull(parseServerTimestamp("2026-13-45 99:00:00"))
    }

    // ---- sortForList ----

    @Test
    fun `last content edit first`() {
        val a = s("a", "2026-09-01T10:00:00Z")
        val b = s("b", "2026-09-03T10:00:00Z")
        val c = s("c", "2026-09-02T10:00:00Z")
        assertEquals(listOf("b", "c", "a"), ids(sortForList(listOf(a, b, c))))
    }

    @Test
    fun `compares instants, not strings`() {
        // As strings "...00+00:00" < "...00.5+00:00" is false ('+' < '.'), and the offset hides the real order.
        val whole = s("whole", "2026-09-01 10:00:00+00:00")
        val half = s("half", "2026-09-01 10:00:00.500000+00:00")
        val east = s("east", "2026-09-01 11:00:00.900000+02:00") // 09:00:00.9 UTC, the oldest
        assertEquals(listOf("half", "whole", "east"), ids(sortForList(listOf(east, whole, half))))
    }

    @Test
    fun `equal content times fall back to newest created first`() {
        val t = "2026-09-01T10:00:00Z"
        val old = s("old", t, created = "2026-08-01T00:00:00Z")
        val new = s("new", t, created = "2026-08-20T00:00:00Z")
        assertEquals(listOf("new", "old"), ids(sortForList(listOf(old, new))))
    }

    @Test
    fun `full ties fall back to id ascending`() {
        val t = "2026-09-01T10:00:00Z"
        val list = listOf(s("c", t), s("a", t), s("b", t))
        assertEquals(listOf("a", "b", "c"), ids(sortForList(list)))
        assertEquals(listOf("a", "b", "c"), ids(sortForList(list.reversed())))
    }

    @Test
    fun `unparseable content times sort after every parseable one`() {
        val good = s("good", "2020-01-01T00:00:00Z")
        val bad = s("bad", "")
        assertEquals(listOf("good", "bad"), ids(sortForList(listOf(bad, good))))
    }

    @Test
    fun `empty list stays empty`() {
        assertEquals(emptyList<Snippet>(), sortForList(emptyList()))
    }

    // ---- ListOrder ----

    @Test
    fun `an order captures the sorted ids`() {
        val a = s("a", "2026-09-01T10:00:00Z")
        val b = s("b", "2026-09-02T10:00:00Z")
        assertEquals(listOf(SnippetId("b"), SnippetId("a")), ListOrder.of(listOf(a, b)).ids)
    }

    @Test
    fun `arrange keeps the captured order when content times change`() {
        val a = s("a", "2026-09-01T10:00:00Z")
        val b = s("b", "2026-09-02T10:00:00Z")
        val order = ListOrder.of(listOf(a, b))
        val aEdited = a.copy(contentUpdatedAt = "2026-09-09T10:00:00Z")
        assertEquals(listOf("b", "a"), ids(order.arrange(listOf(aEdited, b))))
    }

    @Test
    fun `arrange shows the current copy of each snippet`() {
        val a = s("a", "2026-09-01T10:00:00Z")
        val order = ListOrder.of(listOf(a))
        val moved = a.copy(positionX = 99.0)
        assertEquals(listOf(moved), order.arrange(listOf(moved)))
    }

    @Test
    fun `arrange drops snippets that are gone`() {
        val a = s("a", "2026-09-01T10:00:00Z")
        val b = s("b", "2026-09-02T10:00:00Z")
        val order = ListOrder.of(listOf(a, b))
        assertEquals(listOf("a"), ids(order.arrange(listOf(a))))
    }

    @Test
    fun `arrange puts snippets the order has not seen first, sorted among themselves`() {
        val a = s("a", "2026-09-01T10:00:00Z")
        val b = s("b", "2026-09-02T10:00:00Z")
        val order = ListOrder.of(listOf(a, b))
        val n1 = s("n1", "2026-09-05T10:00:00Z")
        val n2 = s("n2", "2026-09-06T10:00:00Z")
        assertEquals(listOf("n2", "n1", "b", "a"), ids(order.arrange(listOf(a, n1, b, n2))))
    }
}
