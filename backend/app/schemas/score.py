"""Request field type and error bodies of the snippet score (docs/features/score_backend.md#api).

Responses carry a stored score as ``ScoreModel`` (app/services/score/wire.py). Requests take the
score as raw JSON (``ScoreInput``) and the save flow runs ``check_score`` on it, so a bad score is a
typed ``invalid_score`` 422 with the spec's ``reason`` (spec/score_format.md section 4) rather than
a generic validation error.
"""

from __future__ import annotations

from typing import Annotated, Any, Literal

from pydantic import BaseModel, Field, WithJsonSchema

from app.services.score import ScoreReason

# A score object (format v1, the ScoreModel schema) or null. Validated by check_score, not pydantic.
ScoreInput = Annotated[
    Any,
    WithJsonSchema({"anyOf": [{"$ref": "#/components/schemas/ScoreModel"}, {"type": "null"}]}),
    Field(description="score format v1 (spec/score_format.md), or null to remove the score"),
]


class ScoreInvalidBody(BaseModel):
    error: Literal["invalid_score"]
    reason: ScoreReason
    line: int | None = Field(default=None, description="0-based score line, when the failure is in a line")
    note: int | None = Field(default=None, description="0-based note of that line, when the failure is in a note")


class ScoreInvalidResponse(BaseModel):
    detail: ScoreInvalidBody


class ScoreErrorBody(BaseModel):
    """``score_text_mismatch`` (422): a rest-linked grid line has no text line (the sent grid and
    text disagree); ``stored_score_invalid`` (500): the stored score no longer fits its grid."""

    error: Literal["score_text_mismatch", "stored_score_invalid"]
    message: str


class ScoreErrorResponse(BaseModel):
    detail: ScoreErrorBody
