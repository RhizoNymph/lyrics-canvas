package dev.nymph.lyrics.logic.placements

import dev.nymph.lyrics.model.PlacementErrorCode
import dev.nymph.lyrics.model.error.ApiError
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.IOException

class PlacementErrorTest {
    @Test
    fun `coded refusals keep their code and status`() {
        assertEquals(
            PlacementError.Rejected(PlacementErrorCode.INVALID_CANVAS_TITLE, 422, "bad"),
            toPlacementError(ApiError.fromHttp(422, """{"detail":{"error":"invalid_canvas_title","message":"bad"}}""")),
        )
        assertEquals(
            PlacementError.Rejected(PlacementErrorCode.PLACEMENT_NOT_FOUND, 404, "gone"),
            toPlacementError(ApiError.fromHttp(404, """{"detail":{"error":"placement_not_found","message":"gone"}}""")),
        )
        assertEquals(
            PlacementError.Rejected(PlacementErrorCode.CANVAS_NOT_FOUND, 404, "no"),
            toPlacementError(ApiError.fromHttp(404, """{"detail":{"error":"canvas_not_found","message":"no"}}""")),
        )
        assertEquals(
            PlacementError.Rejected(PlacementErrorCode.SAME_CANVAS, 422, "same"),
            toPlacementError(ApiError.fromHttp(422, """{"detail":{"error":"same_canvas","message":"same"}}""")),
        )
    }

    @Test
    fun `uncoded failures by kind`() {
        assertEquals(PlacementError.SessionExpired, toPlacementError(ApiError.Unauthorized(401, null)))
        assertEquals(PlacementError.Http(403, "Not authorized"), toPlacementError(ApiError.Unauthorized(403, null)))
        assertEquals(PlacementError.Http(404, "Snippet not found"), toPlacementError(ApiError.NotFound("Snippet not found")))
        assertEquals(PlacementError.Http(422, "field required"), toPlacementError(ApiError.Validation(422, "field required")))
        assertEquals(PlacementError.Http(500, "boom"), toPlacementError(ApiError.Server(500, "boom", code = "unknown_code")))
        assertEquals(PlacementError.Network("down"), toPlacementError(ApiError.Network(IOException("down"))))
        assertEquals(PlacementError.Unexpected("bad json"), toPlacementError(ApiError.Decoding(IllegalArgumentException("bad json"))))
    }
}
