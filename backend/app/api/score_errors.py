"""HTTP mapping of the score save flow's errors (docs/features/score_backend.md#errors).

* ``ScoreInvalid`` -> 422 ``{"error": "invalid_score", "reason", "line"?, "note"?}`` (a bad sent
  score, or a rest-only grid edit that would push notes past MAX_TICKS);
* ``ScoreTextMismatch`` -> 422 ``{"error": "score_text_mismatch", "message"}``;
* ``StoredScoreInvalid`` and any other ``ScoreError`` -> 500 ``{"error": "stored_score_invalid", "message"}``.
"""

from __future__ import annotations

from typing import Union

from fastapi import HTTPException

from app.schemas.placement import PlacementErrorResponse
from app.schemas.score import ScoreErrorBody, ScoreErrorResponse, ScoreInvalidResponse
from app.services.score import ScoreError, ScoreInvalid, ScoreTextMismatch


def score_http_error(exc: ScoreError) -> HTTPException:
    match exc:
        case ScoreInvalid():
            return HTTPException(status_code=422, detail=exc.as_dict())
        case ScoreTextMismatch():
            body = ScoreErrorBody(error="score_text_mismatch", message=str(exc))
            return HTTPException(status_code=422, detail=body.model_dump())
        case _:
            body = ScoreErrorBody(error="stored_score_invalid", message=str(exc))
            return HTTPException(status_code=500, detail=body.model_dump())


def snippet_write_errors() -> dict[int | str, dict[str, object]]:
    """OpenAPI ``responses`` of a snippet create / update: placement 404, placement or score 422,
    stored-score 500."""
    return {
        404: {"model": PlacementErrorResponse},
        422: {"model": Union[PlacementErrorResponse, ScoreInvalidResponse, ScoreErrorResponse]},
        500: {"model": ScoreErrorResponse},
    }
