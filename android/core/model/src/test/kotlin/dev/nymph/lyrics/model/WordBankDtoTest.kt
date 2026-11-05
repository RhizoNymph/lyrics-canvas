package dev.nymph.lyrics.model

import dev.nymph.lyrics.model.error.ApiError
import kotlinx.serialization.builtins.ListSerializer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class WordBankDtoTest {
    private fun encoded(text: String) = LyricsJson.parseToJsonElement(text)

    private val wordJson = """{"id":"w1","text":"Night","tags":[{"id":"t1","name":"dark","color":"#000000"}],"created_at":"2026-11-04T10:00:00Z","extra":1}"""
    private val word = BankWord(BankWordId("w1"), "Night", listOf(Tag(TagId("t1"), "dark", "#000000")), "2026-11-04T10:00:00Z")

    @Test
    fun `bank words decode, ignoring unknown fields`() {
        assertEquals(listOf(word), LyricsJson.decodeFromString(ListSerializer(BankWord.serializer()), "[$wordJson]"))
        assertEquals(emptyList<Tag>(), LyricsJson.decodeFromString(BankWord.serializer(), """{"id":"w2","text":"a","created_at":"x"}""").tags)
    }

    @Test
    fun `user tags gain a word count defaulting to zero`() {
        val withCount = LyricsJson.decodeFromString(UserTag.serializer(), """{"id":"t1","name":"a","color":"#000000","snippet_count":1,"word_count":4,"created_at":"x"}""")
        assertEquals(4, withCount.wordCount)
        assertEquals(1, withCount.snippetCount)
        assertEquals(0, LyricsJson.decodeFromString(UserTag.serializer(), """{"id":"t1","name":"a","color":"#000000","created_at":"x"}""").wordCount)
    }

    @Test
    fun `add response and request`() {
        val response = LyricsJson.decodeFromString(WordBankAddResponse.serializer(), """{"created":[$wordJson],"existing":[]}""")
        assertEquals(WordBankAddResponse(listOf(word), emptyList()), response)
        assertEquals(
            encoded("""{"words":["a","b c"],"tag_ids":[]}"""),
            encoded(LyricsJson.encodeToString(WordBankAddRequest.serializer(), WordBankAddRequest(listOf("a", "b c")))),
        )
    }

    @Test
    fun `tag op encodes as link or unlink`() {
        assertEquals(
            encoded("""{"word_ids":["w1","w2"],"tag_id":"t1","op":"unlink"}"""),
            encoded(LyricsJson.encodeToString(WordBankTagRequest.serializer(), WordBankTagRequest(listOf(BankWordId("w1"), BankWordId("w2")), TagId("t1"), TagLinkOp.UNLINK))),
        )
    }

    @Test
    fun `rhymes request carries exactly one anchor`() {
        val byWord = BankRhymesRequest.of(BankRhymeAnchor.Word(BankWordId("w1")))
        assertEquals(encoded("""{"word_id":"w1","min_score":0.5,"k":2,"limit":100}"""), encoded(LyricsJson.encodeToString(BankRhymesRequest.serializer(), byWord)))
        val byText = BankRhymesRequest.of(BankRhymeAnchor.Text("moon"), tagFilter = "a & b", minScore = 0.3, k = 3, limit = 20)
        assertEquals(
            encoded("""{"text":"moon","tag_filter":"a & b","min_score":0.3,"k":3,"limit":20}"""),
            encoded(LyricsJson.encodeToString(BankRhymesRequest.serializer(), byText)),
        )
        assertNull(byText.wordId)
    }

    @Test
    fun `rhymes and families responses decode`() {
        val rhymes = LyricsJson.decodeFromString(
            BankRhymesResponse.serializer(),
            """{"anchor":{"text":"night","rhyme_key":["AY","T"],"syllable_count":1},"matches":[{"word":$wordJson,"score":0.9,"match_depth":2}]}""",
        )
        assertEquals(BankRhymeAnchorInfo("night", listOf("AY", "T"), 1), rhymes.anchor)
        assertEquals(BankRhymeMatch(word, 0.9, 2), rhymes.matches.single())
        val families = LyricsJson.decodeFromString(
            BankFamiliesResponse.serializer(),
            """{"families":[{"label":"Night","rhyme_key":["AY","T"],"words":[$wordJson,$wordJson],"cohesion":0.85}],"unmatched":[]}""",
        )
        assertEquals(BankFamily("Night", listOf("AY", "T"), listOf(word, word), 0.85), families.families.single())
        assertEquals(encoded("""{"min_score":0.8,"k":2}"""), encoded(LyricsJson.encodeToString(BankFamiliesRequest.serializer(), BankFamiliesRequest())))
    }

    @Test
    fun `related response decodes a null bank id`() {
        val related = LyricsJson.decodeFromString(
            RelatedWordsResponse.serializer(),
            """{"query":"sea","results":[{"text":"ocean","similarity":0.71,"bank_word_id":null},{"text":"tide","similarity":0.6,"bank_word_id":"w3"}]}""",
        )
        assertEquals(listOf(RelatedWord("ocean", 0.71, null), RelatedWord("tide", 0.6, BankWordId("w3"))), related.results)
    }

    @Test
    fun `word bank codes parse`() {
        assertEquals(WordBankErrorCode.RELATED_WORDS_WARMING, WordBankErrorCode.parse("related_words_warming"))
        assertEquals(WordBankErrorCode.entries.size, WordBankErrorCode.entries.map { it.wire }.toSet().size)
        assertNull(WordBankErrorCode.parse("nope"))
    }

    @Test
    fun `error bodies expose the batch index and Retry-After`() {
        val invalid = ApiError.fromHttp(422, """{"detail":{"error":"invalid_word","message":"bad","index":4}}""")
        assertEquals(ApiError.Validation(422, "bad", "invalid_word", index = 4), invalid)
        assertNull((ApiError.fromHttp(422, """{"detail":{"error":"invalid_word","message":"bad","index":"4"}}""") as ApiError.Validation).index)
        assertEquals(ApiError.Server(503, "w", "related_words_warming", 10), ApiError.fromHttp(503, """{"detail":{"error":"related_words_warming","message":"w"}}""", " 10 "))
        assertNull(ApiError.retryAfterSeconds("Wed, 21 Oct 2026 07:28:00 GMT"))
        assertNull(ApiError.retryAfterSeconds("-1"))
    }
}
