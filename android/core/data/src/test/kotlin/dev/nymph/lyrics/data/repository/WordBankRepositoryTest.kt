package dev.nymph.lyrics.data.repository

import dev.nymph.lyrics.data.MockServerTest
import dev.nymph.lyrics.model.BankFamiliesRequest
import dev.nymph.lyrics.model.BankRhymeAnchor
import dev.nymph.lyrics.model.BankRhymesRequest
import dev.nymph.lyrics.model.BankWord
import dev.nymph.lyrics.model.BankWordId
import dev.nymph.lyrics.model.Tag
import dev.nymph.lyrics.model.TagId
import dev.nymph.lyrics.model.TagLinkOp
import dev.nymph.lyrics.model.error.ApiError
import dev.nymph.lyrics.model.error.ApiResult
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class WordBankRepositoryTest : MockServerTest() {
    private val repo by lazy { HttpWordBankRepository(api) }

    private val wordBody = """{"id":"w1","text":"night","tags":[{"id":"t1","name":"dark","color":"#000000"}],"created_at":"2026-11-04T10:00:00Z"}"""
    private val word = BankWord(BankWordId("w1"), "night", listOf(Tag(TagId("t1"), "dark", "#000000")), "2026-11-04T10:00:00Z")

    @Test
    fun `list gets the whole bank`() = runTest {
        enqueue(200, "[$wordBody]")
        assertEquals(ApiResult.Success(listOf(word)), repo.list())
        assertRequest(take(), "GET", "/api/word-bank")
    }

    @Test
    fun `add posts words and tag ids`() = runTest {
        enqueue(201, """{"created":[$wordBody],"existing":[]}""")
        val result = repo.add(listOf("night", "Rain"), listOf(TagId("t1")))
        assertEquals(listOf(word), (result as ApiResult.Success).value.created)
        assertRequest(take(), "POST", "/api/word-bank", """{"words":["night","Rain"],"tag_ids":["t1"]}""")
        enqueue(201, """{"created":[],"existing":[$wordBody]}""")
        repo.add(listOf("night"))
        assertRequest(take(), "POST", "/api/word-bank", """{"words":["night"],"tag_ids":[]}""")
    }

    @Test
    fun `add maps an invalid word with its index`() = runTest {
        enqueue(422, """{"detail":{"error":"invalid_word","message":"bad word","index":2}}""")
        assertEquals(ApiResult.Failure(ApiError.Validation(422, "bad word", "invalid_word", index = 2)), repo.add(listOf("a", "b", "c3")))
    }

    @Test
    fun `rename puts the text`() = runTest {
        enqueue(200, wordBody)
        assertEquals(ApiResult.Success(word), repo.rename(BankWordId("w1"), "night"))
        assertRequest(take(), "PUT", "/api/word-bank/w1", """{"text":"night"}""")
        enqueue(409, """{"detail":{"error":"word_exists","message":"taken"}}""")
        assertEquals(ApiResult.Failure(ApiError.Server(409, "taken", "word_exists")), repo.rename(BankWordId("w1"), "rain"))
    }

    @Test
    fun `delete posts the ids and accepts 204`() = runTest {
        enqueue(204)
        assertEquals(ApiResult.Success(Unit), repo.delete(listOf(BankWordId("w1"), BankWordId("w2"))))
        assertRequest(take(), "POST", "/api/word-bank/delete", """{"ids":["w1","w2"]}""")
    }

    @Test
    fun `setTag links or unlinks and returns the updated words`() = runTest {
        enqueue(200, """{"words":[$wordBody]}""")
        assertEquals(ApiResult.Success(listOf(word)), repo.setTag(listOf(BankWordId("w1")), TagId("t1"), TagLinkOp.LINK))
        assertRequest(take(), "POST", "/api/word-bank/tags", """{"word_ids":["w1"],"tag_id":"t1","op":"link"}""")
        enqueue(200, """{"words":[]}""")
        repo.setTag(listOf(BankWordId("w1")), TagId("t1"), TagLinkOp.UNLINK)
        assertRequest(take(), "POST", "/api/word-bank/tags", """{"word_ids":["w1"],"tag_id":"t1","op":"unlink"}""")
        enqueue(404, """{"detail":{"error":"word_not_found","message":"no"}}""")
        assertEquals(ApiResult.Failure(ApiError.NotFound("no", "word_not_found")), repo.setTag(listOf(BankWordId("x")), TagId("t1"), TagLinkOp.LINK))
    }

    @Test
    fun `rhymes sends exactly one anchor`() = runTest {
        val body = """{"anchor":{"text":"night","rhyme_key":["AY","T"],"syllable_count":1},
            "matches":[{"word":$wordBody,"score":0.93,"match_depth":2}]}"""
        enqueue(200, body)
        val result = repo.rhymes(BankRhymesRequest.of(BankRhymeAnchor.Word(BankWordId("w1")), tagFilter = "dark"))
        val response = (result as ApiResult.Success).value
        assertEquals(listOf("AY", "T"), response.anchor.rhymeKey)
        assertEquals(0.93, response.matches.single().score)
        assertEquals(2, response.matches.single().matchDepth)
        assertRequest(take(), "POST", "/api/word-bank/rhymes", """{"word_id":"w1","tag_filter":"dark","min_score":0.5,"k":2,"limit":100}""")
        enqueue(200, body)
        repo.rhymes(BankRhymesRequest.of(BankRhymeAnchor.Text("moon"), minScore = 0.7))
        assertRequest(take(), "POST", "/api/word-bank/rhymes", """{"text":"moon","min_score":0.7,"k":2,"limit":100}""")
    }

    @Test
    fun `families posts the filter and min score`() = runTest {
        enqueue(200, """{"families":[{"label":"night","rhyme_key":["AY","T"],"words":[$wordBody],"cohesion":0.9}],"unmatched":[$wordBody]}""")
        val response = (repo.families(BankFamiliesRequest(tagFilter = "dark")) as ApiResult.Success).value
        assertEquals("night", response.families.single().label)
        assertEquals(listOf(word), response.unmatched)
        assertRequest(take(), "POST", "/api/word-bank/families", """{"tag_filter":"dark","min_score":0.8,"k":2}""")
        enqueue(422, """{"detail":{"error":"too_many_words","message":"many"}}""")
        assertEquals(ApiResult.Failure(ApiError.Validation(422, "many", "too_many_words")), repo.families(BankFamiliesRequest()))
    }

    @Test
    fun `related gets with query parameters`() = runTest {
        enqueue(200, """{"query":"ocean","results":[{"text":"sea","similarity":0.8,"bank_word_id":null},{"text":"wave","similarity":0.7,"bank_word_id":"w9"}]}""")
        val response = (repo.related("ocean waves") as ApiResult.Success).value
        assertEquals(listOf(null, BankWordId("w9")), response.results.map { it.bankWordId })
        assertRequest(take(), "GET", "/api/word-bank/related?q=ocean%20waves&limit=30")
    }

    @Test
    fun `related warming carries Retry-After`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(503).setHeader("Retry-After", "10").setHeader("Content-Type", "application/json")
                .setBody("""{"detail":{"error":"related_words_warming","message":"warming"}}"""),
        )
        assertEquals(ApiResult.Failure(ApiError.Server(503, "warming", "related_words_warming", 10)), repo.related("x", 5))
        assertRequest(take(), "GET", "/api/word-bank/related?q=x&limit=5")
    }
}
