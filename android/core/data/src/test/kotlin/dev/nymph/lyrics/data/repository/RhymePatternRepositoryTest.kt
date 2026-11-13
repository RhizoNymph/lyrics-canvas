package dev.nymph.lyrics.data.repository

import dev.nymph.lyrics.data.MockServerTest
import dev.nymph.lyrics.model.CanvasId
import dev.nymph.lyrics.model.PatternDetectRequest
import dev.nymph.lyrics.model.PatternErrorCode
import dev.nymph.lyrics.model.PatternSuggestRequest
import dev.nymph.lyrics.model.RhymeK
import dev.nymph.lyrics.model.SlotSpec
import dev.nymph.lyrics.model.SnippetId
import dev.nymph.lyrics.model.SourceName
import dev.nymph.lyrics.model.SpanRef
import dev.nymph.lyrics.model.error.ApiError
import dev.nymph.lyrics.model.error.ApiResult
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RhymePatternRepositoryTest : MockServerTest() {
    private val repo by lazy { HttpRhymePatternRepository(api) }

    private val span = """{"line_index":2,"word_start":3,"word_end":4,"text":"steady","syllable_count":2,"stress":"10",
        "rhyme_key":["EH","D","IY"],"syllables":[{"onset":["S","T"],"nucleus":"EH","coda":[],"stress":1},{"onset":["D"],"nucleus":"IY","coda":[],"stress":0}]}"""
    private val slot = """{"id":"s0","members":[$span],"coverage":1,"full":false,"default_on":true,"k":2,"cohesion":1.0,"syllable_count":2,"stress":"10"}"""
    private val gridLine = """{"cells":[{"syllable":"stead","stress":1,"word_index":0},{"syllable":"y","stress":0,"word_index":0}],"syllable_count":2,"raw_text":"steady"}"""

    private val detectBody = """{"lines":[{"line_index":2,"text":"I keep it steady","words":[{"word_index":0,"text":"I","syllable_count":1,"stress":"1"}],
        "grid_line":$gridLine}],"slots":[$slot],"elapsed_ms":4.5}"""

    private val candidate = """{"id":"dict:ready","text":"ready","provenance":"dictionary","ephemeral":true,"syllable_count":2,"stress":"10",
        "rhyme_key":["EH","D","IY"],"syllables":[],"grid_line":$gridLine,"tags":[],"score":0.8,"match_depth":2,
        "best_anchor_id":"pattern:s1:2:3-4","fits_slot":true,"alignment":[],"source":"dictionary","member_scores":[0.8,0.9]}"""

    private val suggestBody = """{"slots":[{"slot":$slot,"candidates":[$candidate],"total":12}],
        "line_matches":[{"unit":{"id":"note:n1:0:0","text":"ready for the weather","provenance":"note","ephemeral":false,"syllable_count":6,
        "stress":"101010","rhyme_key":["EH","D","IY"],"syllables":[],"grid_line":$gridLine,"snippet_id":"n1","line_index":0,"word_offset":0},
        "hits":[{"slot_id":"s0","word_start":0,"word_end":1,"text":"ready","score":0.9},{"slot_id":"s1","word_start":3,"word_end":4,"text":"weather","score":0.7}],
        "slots_hit":2,"score":0.8}],"line_match_total":1,"elapsed_ms":9.0}"""

    @Test
    fun `detect posts the range and decodes lines and slots`() = runTest {
        enqueue(200, detectBody)
        val response = (repo.detect(PatternDetectRequest(CanvasId("c1"), SnippetId("s1"), 1, 4)) as ApiResult.Success).value
        assertEquals(listOf("s0"), response.slots.map { it.id })
        assertEquals(RhymeK.K2, response.slots.single().k)
        assertEquals("steady", response.slots.single().members.single().text)
        assertEquals(2, response.lines.single().lineIndex)
        assertRequest(take(), "POST", "/api/nlp/rhyme-pattern/detect", """{"canvas_id":"c1","snippet_id":"s1","line_start":1,"line_end":4,"min_score":0.75}""")
    }

    @Test
    fun `suggest posts slots and offsets and decodes columns and line matches`() = runTest {
        enqueue(200, suggestBody)
        val request = PatternSuggestRequest(
            canvasId = CanvasId("c1"),
            snippetId = SnippetId("s1"),
            lineStart = 1,
            lineEnd = 4,
            slots = listOf(SlotSpec("s0", listOf(SpanRef(1, 0, 1), SpanRef(2, 3, 4))), SlotSpec("u0", listOf(SpanRef(3, 2, 3)), RhymeK.K1)),
            sources = listOf(SourceName.DICTIONARY),
            tagFilter = "hook",
            excludeIds = listOf("dict:x"),
            offsets = mapOf("s0" to 30),
            lineMatches = false,
        )
        val response = (repo.suggest(request) as ApiResult.Success).value
        val column = response.slots.single()
        assertEquals(12, column.total)
        assertEquals(listOf(0.8, 0.9), column.candidates.single().memberScores)
        val match = response.lineMatches.single()
        assertEquals(listOf("s0", "s1"), match.hits.map { it.slotId })
        assertEquals(2, match.slotsHit)
        assertEquals(1, response.lineMatchTotal)
        assertRequest(
            take(),
            "POST",
            "/api/nlp/rhyme-pattern/suggest",
            """{"canvas_id":"c1","snippet_id":"s1","line_start":1,"line_end":4,
               "slots":[{"id":"s0","members":[{"line_index":1,"word_start":0,"word_end":1},{"line_index":2,"word_start":3,"word_end":4}]},
                        {"id":"u0","members":[{"line_index":3,"word_start":2,"word_end":3}],"k":1}],
               "min_score":0.5,"sources":["dictionary"],"include_rare":false,"tag_filter":"hook","exclude_ids":["dict:x"],"limit":30,
               "offsets":{"s0":30},"line_matches":false,"line_match_limit":30,"line_match_offset":0}""",
        )
    }

    @Test
    fun `pattern error codes arrive as typed validation errors`() = runTest {
        for (code in listOf("pattern_range_invalid", "pattern_span_invalid", "pattern_slot_order")) {
            enqueue(422, """{"detail":{"error":"$code","message":"bad $code"}}""")
            val failure = repo.detect(PatternDetectRequest(CanvasId("c1"), SnippetId("s1"), 0, 1)) as ApiResult.Failure
            val error = failure.error as ApiError.Validation
            assertEquals(code, PatternErrorCode.parse(error.code)?.wire)
            assertEquals("bad $code", error.message)
            take()
        }
    }

    @Test
    fun `an invalid tag filter carries its location and a missing snippet is not found`() = runTest {
        enqueue(422, """{"detail":{"error":"invalid_tag_expression","message":"Unexpected end","expression_error":{"code":"unexpected_end","position":4,"message":"Unexpected end"}}}""")
        val request = PatternSuggestRequest(CanvasId("c1"), SnippetId("s1"), 0, 2, listOf(SlotSpec("s0", listOf(SpanRef(0, 0, 1)))))
        val error = (repo.suggest(request) as ApiResult.Failure).error as ApiError.Validation
        assertEquals(PatternErrorCode.INVALID_TAG_EXPRESSION, PatternErrorCode.parse(error.code))
        assertEquals(4, error.expressionError?.position)
        take()
        enqueue(404, """{"detail":"Snippet not found"}""")
        val missing = (repo.suggest(request) as ApiResult.Failure).error
        assertTrue(missing is ApiError.NotFound)
    }
}
