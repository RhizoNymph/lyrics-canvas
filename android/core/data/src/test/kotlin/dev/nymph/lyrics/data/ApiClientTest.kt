package dev.nymph.lyrics.data

import dev.nymph.lyrics.model.User
import dev.nymph.lyrics.model.UserId
import dev.nymph.lyrics.model.error.ApiError
import dev.nymph.lyrics.model.error.ApiResult
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.SocketPolicy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ApiClientTest : MockServerTest() {
    private val me = listOf("api", "auth", "me")

    @Test
    fun `sends the bearer token when one is stored`() = runTest {
        tokens.save("tok-1")
        enqueue(200, Bodies.USER)
        val result = api.get(me, User.serializer())
        assertEquals(UserId("u1"), (result as ApiResult.Success).value.id)
        val request = take()
        assertEquals("Bearer tok-1", request.getHeader("Authorization"))
        assertEquals("application/json", request.getHeader("Accept"))
    }

    @Test
    fun `sends no authorization header without a token`() = runTest {
        enqueue(200, Bodies.USER)
        api.get(me, User.serializer())
        assertNull(take().getHeader("Authorization"))
    }

    @Test
    fun `401 maps to Unauthorized and clears the stored token`() = runTest {
        tokens.save("expired")
        enqueue(401, """{"detail":"Invalid token"}""")
        assertEquals(ApiResult.Failure(ApiError.Unauthorized(401, "Invalid token")), api.get(me, User.serializer()))
        assertNull(tokens.token())
        assertNull(tokens.tokens.value)
    }

    @Test
    fun `403 maps to Unauthorized but keeps the token`() = runTest {
        tokens.save("tok-1")
        enqueue(403, """{"detail":"Not authenticated"}""")
        assertEquals(ApiResult.Failure(ApiError.Unauthorized(403, "Not authenticated")), api.get(me, User.serializer()))
        assertEquals("tok-1", tokens.token())
    }

    @Test
    fun `404 maps to NotFound with the detail`() = runTest {
        enqueue(404, """{"detail":"Canvas not found"}""")
        assertEquals(ApiResult.Failure(ApiError.NotFound("Canvas not found")), api.get(me, User.serializer()))
    }

    @Test
    fun `422 maps to Validation with joined pydantic messages`() = runTest {
        enqueue(422, """{"detail":[{"loc":["body","k"],"msg":"Input should be less than or equal to 4","type":"x"},{"msg":"bad stress"}]}""")
        assertEquals(
            ApiResult.Failure(ApiError.Validation(422, "Input should be less than or equal to 4; bad stress")),
            api.get(me, User.serializer()),
        )
    }

    @Test
    fun `400 maps to Validation`() = runTest {
        enqueue(400, """{"detail":"Registration failed: duplicate"}""")
        assertEquals(ApiResult.Failure(ApiError.Validation(400, "Registration failed: duplicate")), api.get(me, User.serializer()))
    }

    @Test
    fun `5xx maps to Server with the status`() = runTest {
        enqueue(500, "Internal Server Error")
        assertEquals(ApiResult.Failure(ApiError.Server(500, null)), api.get(me, User.serializer()))
        enqueue(503, """{"detail":"warming up"}""")
        assertEquals(ApiResult.Failure(ApiError.Server(503, "warming up")), api.get(me, User.serializer()))
    }

    @Test
    fun `a dropped connection maps to Network`() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        val result = api.get(me, User.serializer())
        assertTrue((result as ApiResult.Failure).error is ApiError.Network, "was $result")
    }

    @Test
    fun `an unreachable server maps to Network`() = runTest {
        val url = server.url("/")
        server.shutdown()
        val result = ApiClient(url, tokens, http).get(me, User.serializer())
        assertTrue((result as ApiResult.Failure).error is ApiError.Network, "was $result")
    }

    @Test
    fun `a 2xx body of the wrong shape maps to Decoding`() = runTest {
        enqueue(200, """{"id":"u1"}""")
        val missing = api.get(me, User.serializer())
        assertTrue((missing as ApiResult.Failure).error is ApiError.Decoding, "was $missing")
        enqueue(200, "not json")
        val garbage = api.get(me, User.serializer())
        assertTrue((garbage as ApiResult.Failure).error is ApiError.Decoding, "was $garbage")
    }

    @Test
    fun `path segments are appended to a base url with a path prefix and percent-encoded`() = runTest {
        val prefixed = ApiClient(server.url("/lyrics/"), tokens, http)
        enqueue(200, Bodies.USER)
        prefixed.get(listOf("api", "snippets", "a b/c"), User.serializer())
        assertEquals("/lyrics/api/snippets/a%20b%2Fc", take().path)
    }

    @Test
    fun `create parses the base url or refuses a bad one`() {
        assertNotNull(ApiClient.create("http://10.0.2.2:8000", tokens, http))
        assertNull(ApiClient.create("not a url", tokens, http))
    }
}
