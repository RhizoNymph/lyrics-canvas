package dev.nymph.lyrics.sync.store

import dev.nymph.lyrics.model.BankWordId
import dev.nymph.lyrics.model.CanvasId
import dev.nymph.lyrics.model.CanvasRef
import dev.nymph.lyrics.sync.Fx
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ViewsAndSearchTest {
    private val d = Fx.data()

    @Test
    fun `canvas detail assembles snippets in that canvas's context, bottom to top`() {
        val detail = checkNotNull(Views.canvasDetail(d, Fx.c1))
        assertEquals(listOf(Fx.s1, Fx.s2), detail.snippets.map { it.id })
        val s1 = detail.snippets[0]
        assertEquals(Fx.c1, s1.canvasId)
        assertEquals(10.0, s1.positionX)
        assertEquals(listOf(CanvasRef(Fx.c1, "One"), CanvasRef(Fx.c2, "Two")), s1.canvases)
        assertEquals("Hook", detail.snippets[1].tags.single().name)
        assertNull(Views.canvasDetail(d, CanvasId("x")))
    }

    @Test
    fun `canvas list is newest updated first`() {
        val newer = d.withCanvas(Fx.canvas(Fx.c1, "One", updatedAt = Fx.T1))
        assertEquals(listOf(Fx.c1, Fx.c2), Views.canvasList(newer).map { it.id })
        assertEquals(listOf(Fx.c1, Fx.c2), Views.canvasList(d.withCanvas(Fx.canvas(Fx.c2, "Two", updatedAt = "2026-10-01 09:00:00.5+00:00"))).map { it.id })
    }

    @Test
    fun `tag counts come from the local links`() {
        val tagged = d.withWord(Fx.word(Fx.w1, "moon", tags = listOf(Fx.t1)))
        val tag = Views.tagList(tagged).single()
        assertEquals(1, tag.snippetCount)
        assertEquals(1, tag.wordCount)
    }

    @Test
    fun `words are newest first with embedded tags`() {
        val more = d.withWord(LocalWord(BankWordId("w2"), "sun", listOf(Fx.t1), Fx.T1, 1))
        val words = Views.words(more)
        assertEquals(listOf("sun", "moon"), words.map { it.text })
        assertEquals("Hook", words[0].tags.single().name)
    }

    @Test
    fun `search matches every term on display text, titles by substring`() {
        val r = LocalSearch.search(d, "  FIRST sec ")
        assertEquals(listOf(Fx.s1), r.snippets.map { it.snippetId })
        assertEquals("first line", r.snippets[0].excerpt)
        assertEquals(listOf(Fx.c1, Fx.c2), r.snippets[0].canvases.map { it.id })
        assertEquals(listOf(Fx.c1), LocalSearch.search(d, "on").canvases.map { it.id })
        assertTrue(LocalSearch.search(d, "   ").snippets.isEmpty())
    }

    @Test
    fun `rest marks are stripped before matching`() {
        val withRest = d.withSnippet(Fx.content(Fx.s2, "fly_away"))
        assertEquals(listOf(Fx.s2), LocalSearch.search(withRest, "fly away").snippets.map { it.snippetId })
    }

    @Test
    fun `excerpt windows long lines around the first term like the server`() {
        val long = "a".repeat(100) + " needle " + "b".repeat(300)
        val e = LocalSearch.excerpt(long, listOf("needle"))
        assertEquals(EXCERPT_MAX, e.codePointCount(0, e.length))
        assertTrue(e.startsWith("…") && e.endsWith("…"))
        assertTrue(e.contains("needle"))
        val head = LocalSearch.excerpt("x".repeat(200), listOf("zzz"))
        assertTrue(head.endsWith("…") && !head.startsWith("…"))
        assertEquals("", LocalSearch.excerpt("_ _", listOf("q")))
    }

    @Test
    fun `snippets are ordered by content_updated_at desc and limited`() {
        val newer = d.withSnippet(Fx.content(Fx.s2, "first too", contentUpdatedAt = Fx.T1))
        assertEquals(listOf(Fx.s2, Fx.s1), LocalSearch.search(newer, "first").snippets.map { it.snippetId })
        assertEquals(1, LocalSearch.search(newer, "first", limit = 1).snippets.size)
    }
}
