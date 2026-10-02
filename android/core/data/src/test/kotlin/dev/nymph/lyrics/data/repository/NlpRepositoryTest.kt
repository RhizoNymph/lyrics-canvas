package dev.nymph.lyrics.data.repository

import dev.nymph.lyrics.data.Bodies
import dev.nymph.lyrics.data.MockServerTest
import dev.nymph.lyrics.model.CanvasId
import dev.nymph.lyrics.model.RhymeK
import dev.nymph.lyrics.model.RhymeSuggestRequest
import dev.nymph.lyrics.model.SnippetAnchorRef
import dev.nymph.lyrics.model.SnippetId
import dev.nymph.lyrics.model.TextAnchorRef
import dev.nymph.lyrics.model.error.ApiResult
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class NlpRepositoryTest : MockServerTest() {
    private val repo by lazy { HttpNlpRepository(api) }

    @Test
    fun `syllabify posts the text and returns a grid`() = runTest {
        enqueue(200, """{"lines":[{"cells":[{"syllable":"hel","stress":0,"word_index":0},{"syllable":"lo","stress":1,"word_index":0}],"syllable_count":2,"raw_text":"hello"}]}""")
        val grid = (repo.syllabify("hello") as ApiResult.Success).value
        assertEquals(listOf("hel", "lo"), grid.lines.single().cells.map { it.syllable })
        assertRequest(take(), "POST", "/api/nlp/syllabify", """{"text":"hello"}""")
    }

    @Test
    fun `rhymeSuggest posts the request and decodes candidates`() = runTest {
        enqueue(200, Bodies.RHYME)
        val request = RhymeSuggestRequest(
            canvasId = CanvasId("c1"),
            anchors = listOf(SnippetAnchorRef(SnippetId("a"), 0, 1, 3), TextAnchorRef("tin")),
            k = RhymeK.K1,
        )
        val response = (repo.rhymeSuggest(request) as ApiResult.Success).value
        assertEquals(listOf("dict:tin"), response.candidates.map { it.id })
        assertEquals(1, response.total)
        assertRequest(
            take(),
            "POST",
            "/api/nlp/rhyme-suggest",
            """{"canvas_id":"c1","anchors":[{"kind":"snippet","snippet_id":"a","line_index":0,"word_start":1,"word_end":3},
               {"kind":"text","text":"tin"}],"context":[],"k":1,"min_score":0.5,"sources":["notes","dictionary"],
               "include_rare":false,"exclude_ids":[],"limit":40,"offset":0}""",
        )
    }
}
