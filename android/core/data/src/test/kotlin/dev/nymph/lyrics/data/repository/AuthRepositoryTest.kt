package dev.nymph.lyrics.data.repository

import dev.nymph.lyrics.data.Bodies
import dev.nymph.lyrics.data.MockServerTest
import dev.nymph.lyrics.model.error.ApiError
import dev.nymph.lyrics.model.error.ApiResult
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class AuthRepositoryTest : MockServerTest() {
    private val repo by lazy { HttpAuthRepository(api, tokens) }

    @Test
    fun `login posts credentials, stores the token and loads the user with it`() = runTest {
        enqueue(200, Bodies.TOKEN)
        enqueue(200, Bodies.USER)
        val result = repo.login("a@b.c", "pw")
        assertEquals("Nym", (result as ApiResult.Success).value.displayName)
        assertEquals("tok-1", tokens.token())
        val login = take()
        assertRequest(login, "POST", "/api/auth/login", """{"email":"a@b.c","password":"pw"}""")
        assertNull(login.getHeader("Authorization"))
        val me = take()
        assertRequest(me, "GET", "/api/auth/me")
        assertEquals("Bearer tok-1", me.getHeader("Authorization"))
    }

    @Test
    fun `bad credentials fail as Unauthorized without storing a token`() = runTest {
        enqueue(401, """{"detail":"Invalid credentials"}""")
        assertEquals(ApiResult.Failure(ApiError.Unauthorized(401, "Invalid credentials")), repo.login("a@b.c", "nope"))
        assertNull(tokens.token())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `register posts the display name and signs in`() = runTest {
        enqueue(200, Bodies.TOKEN)
        enqueue(200, Bodies.USER)
        repo.register("a@b.c", "pw", "Nym")
        assertRequest(take(), "POST", "/api/auth/register", """{"email":"a@b.c","password":"pw","display_name":"Nym"}""")
        assertRequest(take(), "GET", "/api/auth/me")
        assertEquals("tok-1", tokens.token())
    }

    @Test
    fun `register failure surfaces as Validation`() = runTest {
        enqueue(400, """{"detail":"Registration failed: exists"}""")
        assertEquals(ApiResult.Failure(ApiError.Validation(400, "Registration failed: exists")), repo.register("a@b.c", "pw", "Nym"))
    }

    @Test
    fun `logout clears the token`() = runTest {
        tokens.save("tok-1")
        repo.logout()
        assertNull(tokens.token())
    }
}
