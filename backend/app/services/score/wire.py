"""Pydantic wire model of score format v1, for responses and the OpenAPI document.

Request bodies should take the raw JSON object and run ``check_score(raw, grid)``: it reports the
spec's ``reason`` codes in the spec's order, which a pydantic validation error cannot. This model is
what a stored (valid) score serializes as; notes keep their extra keys.
"""

from __future__ import annotations

from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field, SerializerFunctionWrapHandler, model_serializer

from app.services.score.codec import score_to_json
from app.services.score.types import GridJson, Score
from app.services.score.validate import check_score

SnapValue = Literal["1/8", "1/16", "1/32", "1/8t", "1/16t"]


class ScoreNoteModel(BaseModel):
    """One placed syllable. Unknown keys (a future pitch) are kept and serialized."""

    model_config = ConfigDict(extra="allow", json_schema_mode_override="validation")

    s: int = Field(ge=0, description="syllable ordinal in the grid line (rest cells not counted)")
    t: int = Field(ge=0, description="start tick from tick 0 (ppq 48)")
    d: int = Field(ge=1, description="duration in ticks")
    a: Literal[0, 1, 2] = Field(description="accent: 0 none, 1 accent, 2 strong")

    @model_serializer(mode="wrap")
    def _with_extras(self, handler: SerializerFunctionWrapHandler):
        data = handler(self)
        data.update(self.model_extra or {})
        return data


class ScoreLineModel(BaseModel):
    model_config = ConfigDict(extra="forbid")

    notes: list[ScoreNoteModel]


class ScoreMeterModel(BaseModel):
    model_config = ConfigDict(extra="forbid")

    bar: int = Field(ge=0, le=2048)
    num: int = Field(ge=1, le=32)
    den: Literal[1, 2, 4, 8, 16, 32]


class ScoreTempoModel(BaseModel):
    model_config = ConfigDict(extra="forbid")

    bar: int = Field(ge=0, le=2048)
    bpm: float | int = Field(ge=20, le=400, description="quarter notes per minute, at most 2 decimals")


class ScoreModel(BaseModel):
    """Score format v1 (spec/score_format.md)."""

    model_config = ConfigDict(extra="forbid")

    v: Literal[1]
    ppq: Literal[48]
    pickup: int = Field(ge=0, description="ticks before bar 0")
    meters: list[ScoreMeterModel] = Field(min_length=1, max_length=256)
    tempos: list[ScoreTempoModel] = Field(min_length=1, max_length=256)
    snap: SnapValue
    lines: list[ScoreLineModel] = Field(description="one entry per grid line")

    @classmethod
    def from_score(cls, score: Score) -> ScoreModel:
        return cls.model_validate(score_to_json(score))

    def to_json(self) -> dict[str, Any]:
        return self.model_dump(mode="json")

    def to_score(self, grid: GridJson) -> Score:
        """Validate against ``grid`` (raises ScoreInvalid)."""
        return check_score(self.to_json(), grid)
