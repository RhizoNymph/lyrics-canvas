package dev.nymph.lyrics.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Requests encode with the field names the backend's pydantic models declare
 * (backend/app/schemas) and decode back to the same value.
 */
class RequestEncodingTest {
    private fun <T> encode(serializer: KSerializer<T>, value: T): JsonObject =
        LyricsJson.encodeToJsonElement(serializer, value).jsonObject

    private fun <T> assertRoundTrip(serializer: KSerializer<T>, value: T) {
        assertEquals(value, LyricsJson.decodeFromString(serializer, LyricsJson.encodeToString(serializer, value)))
    }

    private fun json(text: String): JsonElement = LyricsJson.parseToJsonElement(text)

    @Test
    fun `login and register`() {
        val login = LoginRequest("a@b.c", "pw")
        assertEquals(json("""{"email":"a@b.c","password":"pw"}"""), encode(LoginRequest.serializer(), login))
        assertRoundTrip(LoginRequest.serializer(), login)
        val register = RegisterRequest("a@b.c", "pw", "Nym")
        assertEquals(json("""{"email":"a@b.c","password":"pw","display_name":"Nym"}"""), encode(RegisterRequest.serializer(), register))
        assertRoundTrip(RegisterRequest.serializer(), register)
    }

    @Test
    fun `canvas create and viewport update`() {
        assertEquals(json("""{"title":"Untitled Canvas"}"""), encode(CanvasCreateRequest.serializer(), CanvasCreateRequest()))
        val update = CanvasUpdateRequest(viewportState = ViewportState(1.0, 2.0, 0.5))
        assertEquals(json("""{"viewport_state":{"x":1.0,"y":2.0,"zoom":0.5}}"""), encode(CanvasUpdateRequest.serializer(), update))
        assertRoundTrip(CanvasUpdateRequest.serializer(), update)
        val all = CanvasUpdateRequest("t", true, ViewportState())
        assertEquals(setOf("title", "is_public", "viewport_state"), encode(CanvasUpdateRequest.serializer(), all).keys)
    }

    @Test
    fun `snippet create omits unset fields and names every field like SnippetCreate`() {
        val minimal = SnippetCreateRequest(positionX = 10.0, positionY = 20.0)
        assertEquals(json("""{"position_x":10.0,"position_y":20.0}"""), encode(SnippetCreateRequest.serializer(), minimal))

        val full = SnippetCreateRequest(
            positionX = 1.0,
            positionY = 2.0,
            snippetType = SnippetType.FREESTYLE,
            rawText = "tin",
            gridMode = GridMode.FIXED,
            beatsPerLine = 8,
            gridData = GridData(listOf(GridLine(listOf(GridCell("tin", 1, 0, word = "tin"), GridCell.REST), 1, "tin"))),
            groupId = GroupId("g"),
            derivedFrom = DerivedFrom(Provenance.DICTIONARY, "dict:tin", 0.9, 1, 2, "note:a:0:0", "hinge"),
        )
        val encoded = encode(SnippetCreateRequest.serializer(), full)
        assertEquals(
            setOf("snippet_type", "grid_mode", "beats_per_line", "raw_text", "grid_data", "position_x", "position_y", "group_id", "derived_from"),
            encoded.keys,
        )
        assertEquals("freestyle", (encoded["snippet_type"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertEquals(
            json("""{"lines":[{"cells":[{"syllable":"tin","stress":1,"word_index":0,"word":"tin"},{"rest":true}],"syllable_count":1,"raw_text":"tin"}]}"""),
            encoded["grid_data"],
        )
        assertEquals(
            setOf("provenance", "source_unit_id", "score", "match_depth", "k", "anchor_unit_id", "anchor_text"),
            encoded["derived_from"]!!.jsonObject.keys,
        )
        assertRoundTrip(SnippetCreateRequest.serializer(), full)
    }

    @Test
    fun `snippet update sends only the given fields`() {
        val textOnly = SnippetUpdateRequest(rawText = "a\nb", gridData = GridData(emptyList()))
        assertEquals(json("""{"raw_text":"a\nb","grid_data":{"lines":[]}}"""), encode(SnippetUpdateRequest.serializer(), textOnly))
        val all = SnippetUpdateRequest(SnippetType.HOOK, GridMode.AUTO, 4, GridData(), "x", 1.0, 2.0, 3.0, 4.0, 5, GroupId("g"))
        assertEquals(
            setOf(
                "snippet_type", "grid_mode", "beats_per_line", "grid_data", "raw_text", "position_x", "position_y",
                "width", "height", "z_index", "group_id",
            ),
            encode(SnippetUpdateRequest.serializer(), all).keys,
        )
        assertRoundTrip(SnippetUpdateRequest.serializer(), all)
    }

    @Test
    fun `batch position`() {
        val req = BatchPositionRequest(listOf(PositionUpdate(SnippetId("a"), 1.5, -2.0)))
        assertEquals(json("""{"updates":[{"id":"a","position_x":1.5,"position_y":-2.0}]}"""), encode(BatchPositionRequest.serializer(), req))
        assertRoundTrip(BatchPositionRequest.serializer(), req)
    }

    @Test
    fun `syllabify`() {
        assertEquals(json("""{"text":"hello"}"""), encode(SyllabifyRequest.serializer(), SyllabifyRequest("hello")))
    }

    @Test
    fun `rhyme suggest request matches the web client's request`() {
        val req = RhymeSuggestRequest(
            canvasId = CanvasId("c1"),
            anchors = listOf(SnippetAnchorRef(SnippetId("a")), TextAnchorRef("door hinge")),
            slot = Slot(4, "1010"),
            k = RhymeK.K2,
            minScore = 0.6,
            excludeIds = listOf("dict:orange"),
        )
        // The web sends weights/dialect as explicit nulls; absent is the same to pydantic.
        assertEquals(
            json(
                """{"canvas_id":"c1","anchors":[{"kind":"snippet","snippet_id":"a"},{"kind":"text","text":"door hinge"}],
                "context":[],"slot":{"syllable_count":4,"stress":"1010"},"k":2,"min_score":0.6,"sources":["notes","dictionary"],
                "include_rare":false,"exclude_ids":["dict:orange"],"limit":40,"offset":0}""",
            ),
            encode(RhymeSuggestRequest.serializer(), req),
        )
        assertRoundTrip(RhymeSuggestRequest.serializer(), req)
    }

    @Test
    fun `rhyme suggest request with ranges, weights and dialect`() {
        val req = RhymeSuggestRequest(
            canvasId = CanvasId("c1"),
            anchors = listOf(SnippetAnchorRef(SnippetId("a"), lineIndex = 0, wordStart = 1, wordEnd = 3)),
            context = listOf(TextAnchorRef("ctx")),
            k = RhymeK.K4,
            sources = listOf(SourceName.DICTIONARY),
            includeRare = true,
            offset = 40,
            weights = ScoreWeights(1.0, 0.5, 0.0),
            dialect = DialectMap(listOf(DialectRule(DialectRuleKind.FINAL_MERGE, listOf("IH"), listOf("EH")))),
        )
        val encoded = encode(RhymeSuggestRequest.serializer(), req)
        assertEquals(
            setOf(
                "canvas_id", "anchors", "context", "k", "min_score", "sources", "include_rare", "exclude_ids", "limit",
                "offset", "weights", "dialect",
            ),
            encoded.keys,
        )
        assertEquals(json("""{"kind":"snippet","snippet_id":"a","line_index":0,"word_start":1,"word_end":3}"""), encoded["anchors"]!!.jsonArray[0])
        assertEquals(json("""{"vowel":1.0,"coda":0.5,"onset":0.0}"""), encoded["weights"])
        assertEquals(json("""{"rules":[{"kind":"final_merge","source":["IH"],"target":["EH"]}]}"""), encoded["dialect"])
        assertEquals(json("4"), encoded["k"])
        assertRoundTrip(RhymeSuggestRequest.serializer(), req)
    }

    @Test
    fun `anchor refs decode by kind`() {
        val anchors = LyricsJson.decodeFromString<List<AnchorRef>>(
            """[{"kind":"text","text":"x"},{"kind":"snippet","snippet_id":"s","line_index":null}]""",
        )
        assertEquals(listOf(TextAnchorRef("x"), SnippetAnchorRef(SnippetId("s"))), anchors)
    }

    @Test
    fun `rhyme k rejects values outside 1 to 4`() {
        assertEquals(RhymeK.K3, LyricsJson.decodeFromString<RhymeK>("3"))
        val failure = runCatching { LyricsJson.decodeFromString<RhymeK>("5") }
        assertTrue(failure.isFailure)
        assertEquals(null, RhymeK.of(0))
    }

    @Test
    fun `merge request needs two distinct ids and encodes like the contract`() {
        assertEquals(null, MergeRequest.of(listOf(SnippetId("a")), MergeMode.VERTICAL))
        assertEquals(null, MergeRequest.of(listOf(SnippetId("a"), SnippetId("a")), MergeMode.VERTICAL))
        val req = MergeRequest.of(listOf(SnippetId("b"), SnippetId("a"), SnippetId("b")), MergeMode.HORIZONTAL)!!
        assertEquals(listOf(SnippetId("b"), SnippetId("a")), req.snippetIds)
        assertEquals(json("""{"snippet_ids":["b","a"],"mode":"horizontal"}"""), encode(MergeRequest.serializer(), req))
        assertRoundTrip(MergeRequest.serializer(), req)
        assertEquals("vertical", LyricsJson.encodeToJsonElement(MergeMode.serializer(), MergeMode.VERTICAL).toString().trim('"'))
    }

    @Test
    fun `grid cell omits unset keys`() {
        assertEquals(json("""{"rest":true}"""), LyricsJson.encodeToJsonElement(GridCell.serializer(), GridCell.REST))
        val cell = GridCell(syllable = "or", stress = 1, wordIndex = 0)
        assertEquals(json("""{"syllable":"or","stress":1,"word_index":0}"""), LyricsJson.encodeToJsonElement(GridCell.serializer(), cell))
        assertRoundTrip(GridCell.serializer(), cell)
        assertFalse(cell.isRest)
        assertTrue((LyricsJson.encodeToJsonElement(GridData.serializer(), GridData()) as JsonObject)["lines"] is JsonArray)
    }
}
