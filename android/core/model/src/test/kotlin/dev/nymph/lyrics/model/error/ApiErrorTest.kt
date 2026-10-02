package dev.nymph.lyrics.model.error

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ApiErrorTest {
    @Test
    fun `maps statuses to kinds`() {
        assertEquals(ApiError.Unauthorized(401, "Invalid token"), ApiError.fromHttp(401, """{"detail":"Invalid token"}"""))
        assertEquals(ApiError.Unauthorized(403, "Not authenticated"), ApiError.fromHttp(403, """{"detail":"Not authenticated"}"""))
        assertEquals(ApiError.NotFound("Canvas not found"), ApiError.fromHttp(404, """{"detail":"Canvas not found"}"""))
        assertEquals(ApiError.Validation(400, "Registration failed: dup"), ApiError.fromHttp(400, """{"detail":"Registration failed: dup"}"""))
        assertEquals(ApiError.Server(500, null), ApiError.fromHttp(500, "Internal Server Error"))
        assertEquals(ApiError.Server(409, "conflict"), ApiError.fromHttp(409, """{"detail":"conflict"}"""))
    }

    @Test
    fun `joins pydantic validation messages`() {
        val e = ApiError.fromHttp(422, """{"detail":[{"loc":["body","k"],"msg":"k must be 1..4","type":"x"},{"msg":"bad stress"}]}""")
        assertEquals(ApiError.Validation(422, "k must be 1..4; bad stress"), e)
        assertEquals("k must be 1..4; bad stress", e.message)
    }

    @Test
    fun `validation without a readable detail still has a message`() {
        assertEquals(ApiError.Validation(422, "Invalid request (422)"), ApiError.fromHttp(422, """{"detail":[{"nomsg":1}]}"""))
        assertEquals(ApiError.Validation(400, "Invalid request (400)"), ApiError.fromHttp(400, null))
    }

    @Test
    fun `detail parsing tolerates junk`() {
        assertNull(ApiError.detailFromBody(null))
        assertNull(ApiError.detailFromBody(""))
        assertNull(ApiError.detailFromBody("<html>"))
        assertNull(ApiError.detailFromBody("[1,2]"))
        assertNull(ApiError.detailFromBody("""{"detail":3}"""))
        assertNull(ApiError.detailFromBody("""{"detail":{"a":1}}"""))
    }

    @Test
    fun `result helpers`() {
        val ok: ApiResult<Int> = ApiResult.Success(2)
        val bad: ApiResult<Int> = ApiResult.Failure(ApiError.NotFound(null))
        assertEquals(ApiResult.Success(4), ok.map { it * 2 })
        assertEquals(bad, bad.map { it * 2 })
        assertEquals(2, ok.valueOrNull())
        assertNull(bad.valueOrNull())
        assertEquals(ApiError.NotFound(null), bad.errorOrNull())
        assertEquals("Not found", bad.fold({ "ok" }, { it.message }))
        assertEquals(ApiResult.Success("x"), ok.flatMap { ApiResult.Success("x") })
    }
}
