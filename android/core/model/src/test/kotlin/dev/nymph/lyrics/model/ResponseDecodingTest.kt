package dev.nymph.lyrics.model

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ResponseDecodingTest {
    @Test
    fun `token response`() {
        val token = LyricsJson.decodeFromString<TokenResponse>(Samples.TOKEN)
        assertEquals("eyJhbGciOiJIUzI1NiJ9.x.y", token.accessToken)
        assertEquals("bearer", token.tokenType)
    }

    @Test
    fun `user response`() {
        val user = LyricsJson.decodeFromString<User>(Samples.USER)
        assertEquals(UserId("6f1c0f5e-1111-4000-8000-000000000001"), user.id)
        assertEquals("Nym", user.displayName)
        assertNull(user.oauthProvider)
        assertEquals("2026-09-01 10:00:00.123456+00:00", user.createdAt)
    }

    @Test
    fun `canvas list with and without a viewport`() {
        val list = LyricsJson.decodeFromString<List<Canvas>>(Samples.CANVAS_LIST)
        assertEquals(2, list.size)
        assertEquals(ViewportState(-120.5, 40.0, 1.25), list[0].viewportState)
        assertEquals(CanvasId("c0ffee00-0000-4000-8000-000000000001"), list[0].id)
        assertEquals(false, list[0].isPublic)
        assertNull(list[1].viewportState)
        assertEquals("tok", list[1].shareToken)
    }

    @Test
    fun `canvas viewport with missing keys falls back to identity`() {
        val canvas = LyricsJson.decodeFromString<Canvas>(Samples.CANVAS.replace(""""x":-120.5,"y":40,"zoom":1.25""", """"zoom":2"""))
        assertEquals(ViewportState(0.0, 0.0, 2.0), canvas.viewportState)
    }

    @Test
    fun `canvas detail with snippets, groups and tags`() {
        val detail = LyricsJson.decodeFromString<CanvasDetail>(Samples.CANVAS_DETAIL)
        assertEquals("Night drive", detail.title)
        assertEquals(2, detail.snippets.size)
        assertEquals(listOf(SnippetId("5a1e0000-0000-4000-8000-000000000001")), detail.groups.single().snippetIds)
        assertEquals("Verse 1", detail.groups.single().name)
        assertEquals(TagId("7a000000-0000-4000-8000-000000000001"), detail.tags.single().id)
        assertEquals(detail.title, detail.toCanvas().title)
    }

    @Test
    fun `full snippet including grid, tags, phonetics passthrough and derived_from`() {
        val s = LyricsJson.decodeFromString<Snippet>(Samples.SNIPPET)
        assertEquals(SnippetType.VERSE, s.snippetType)
        assertEquals(GridMode.FIXED, s.gridMode)
        assertEquals(8, s.beatsPerLine)
        assertEquals(200.5, s.positionY)
        assertEquals(3, s.zIndex)
        assertEquals("2026-09-02 10:00:00+00:00", s.updatedAt)
        assertEquals("2026-09-01 12:30:00.250000+00:00", s.contentUpdatedAt)
        assertEquals(GroupId("9a000000-0000-4000-8000-000000000001"), s.groupId)
        val line = s.gridData!!.lines.single()
        assertEquals(4, line.cells.size)
        assertEquals(GridCell(syllable = "hel", stress = 0, wordIndex = 0, word = "hello"), line.cells[0])
        assertTrue(line.cells[2].isRest)
        assertEquals(GridCell(syllable = "world", stress = 1, wordIndex = 1), line.cells[3])
        assertEquals("hello world", line.rawText)
        assertEquals("hook idea", s.tags.single().name)
        val phonetics = s.phonetics as JsonObject
        assertEquals("abc123", phonetics["dialect_digest"]!!.jsonPrimitive.content)
        assertEquals(
            LyricsJson.parseToJsonElement(Samples.PHONETICS),
            LyricsJson.parseToJsonElement(LyricsJson.encodeToString(Snippet.serializer(), s)).jsonObject["phonetics"],
        )
        assertEquals(
            DerivedFrom(Provenance.DICTIONARY, "dict:tin", 0.9, 1, 2, "note:a:0:0", "hinge"),
            s.derivedFrom,
        )
    }

    @Test
    fun `bare snippet with nulls`() {
        val s = LyricsJson.decodeFromString<Snippet>(Samples.SNIPPET_BARE)
        assertEquals(SnippetType.FREESTYLE, s.snippetType)
        assertEquals(GridMode.AUTO, s.gridMode)
        assertNull(s.beatsPerLine)
        assertEquals(GridData(emptyList()), s.gridData)
        assertNull(s.groupId)
        assertNull(s.phonetics)
        assertNull(s.derivedFrom)
        assertEquals(emptyList<Tag>(), s.tags)
    }

    @Test
    fun `snippet with null grid_data and grid without lines`() {
        val nullGrid = LyricsJson.decodeFromString<Snippet>(Samples.SNIPPET_BARE.replace(""""grid_data":{"lines":[]}""", """"grid_data":null"""))
        assertNull(nullGrid.gridData)
        val emptyGrid = LyricsJson.decodeFromString<Snippet>(Samples.SNIPPET_BARE.replace(""""grid_data":{"lines":[]}""", """"grid_data":{}"""))
        assertEquals(GridData(), emptyGrid.gridData)
    }

    @Test
    fun `batch position response`() {
        assertEquals(BatchPositionResponse(2), LyricsJson.decodeFromString<BatchPositionResponse>(Samples.BATCH_POSITION))
    }

    @Test
    fun `syllabify response is a grid`() {
        val grid = LyricsJson.decodeFromString<GridData>(Samples.SYLLABIFY)
        assertEquals(listOf("hel", "lo"), grid.lines.single().cells.map { it.syllable })
        assertEquals(2, grid.lines.single().syllableCount)
    }

    @Test
    fun `rhyme suggest response`() {
        val r = LyricsJson.decodeFromString<RhymeSuggestResponse>(Samples.RHYME_SUGGEST)
        assertEquals(41, r.total)
        assertEquals(12.5, r.elapsedMs)
        val anchor = r.anchors.single()
        assertEquals(Provenance.NOTE, anchor.provenance)
        assertEquals(SnippetId("5a1e0000-0000-4000-8000-000000000001"), anchor.snippetId)
        assertEquals(1, anchor.wordOffset)
        assertEquals(3, anchor.wordEnd)
        assertEquals(listOf("D"), anchor.syllables[0].onset)
        val c = r.candidates.single()
        assertEquals(SourceName.DICTIONARY, c.source)
        assertEquals(0.87, c.score)
        assertEquals(2, c.matchDepth)
        assertEquals("note:a:1:1", c.bestAnchorId)
        assertTrue(c.fitsSlot)
        assertNull(c.snippetId)
        assertNull(c.wordEnd)
        assertEquals(listOf("rare"), c.tags)
        assertEquals(SyllableAlignment(0, null, 0.0, 0.0, 0.0, 0.0), c.alignment[1])
        assertEquals("four inch", c.gridLine.rawText)
    }

    @Test
    fun `merge response`() {
        val m = LyricsJson.decodeFromString<MergeResponse>(Samples.MERGE)
        assertEquals(SnippetId("5a1e0000-0000-4000-8000-000000000001"), m.snippet.id)
        assertEquals(
            listOf(SnippetId("5a1e0000-0000-4000-8000-000000000003"), SnippetId("5a1e0000-0000-4000-8000-000000000004")),
            m.deletedIds,
        )
    }

    @Test
    fun `unknown keys are ignored`() {
        val user = LyricsJson.decodeFromString<User>(Samples.USER.replace("\"email\"", "\"avatar\":\"x\",\"email\""))
        assertEquals("a@b.c", user.email)
    }
}
