"""Wire shapes of the score draft and export endpoints (docs/features/score_draft_export.md)."""

from __future__ import annotations

import uuid
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field

from app.services.score.wire import ScoreModel


class DraftMeter(BaseModel):
    """The time signature of bar 0. Values outside the format (num 1..32, den 1/2/4/8/16/32) are
    422 ``invalid_score`` ``meter_invalid``."""

    model_config = ConfigDict(extra="forbid")

    num: int
    den: int


class GridDraftRequest(BaseModel):
    """Each syllable one ``note`` long, each rest cell one beat unit, each line on a new bar."""

    model_config = ConfigDict(extra="forbid")

    source: Literal["grid"]
    note: str = Field(description="1/8, 1/16, 1/32, 1/8t or 1/16t; anything else is invalid_score snap_invalid")
    meter: DraftMeter
    bpm: float | int = Field(description="20..400, at most 2 decimals; else invalid_score tempo_invalid")


class RecordingDraftRequest(BaseModel):
    """Timing from a voice take on this snippet; ``bpm`` null asks the server to estimate it."""

    model_config = ConfigDict(extra="forbid")

    source: Literal["recording"]
    recording_id: uuid.UUID
    meter: DraftMeter
    bpm: float | int | None = None


ScoreDraftRequest = Annotated[GridDraftRequest | RecordingDraftRequest, Field(discriminator="source")]


class ScoreDraftResponse(BaseModel):
    """A score for the snippet's current grid. Not saved: the client saves it with PUT."""

    score: ScoreModel
    bpm_estimated: float | int | None = Field(description="the tempo the server estimated (recording, bpm null); else null")
    unplaced: int = Field(description="grid syllables without a note")
    unmatched_words: int = Field(description="take words that matched no grid word (0 for a grid draft)")


class ScoreToolErrorBody(BaseModel):
    error: Literal[
        "snippet_not_found",
        "recording_not_found",
        "recording_has_no_timings",
        "draft_empty",
        "score_not_found",
        "stored_score_invalid",
    ]
    message: str


class ScoreToolErrorResponse(BaseModel):
    detail: ScoreToolErrorBody
