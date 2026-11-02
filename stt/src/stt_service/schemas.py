"""Wire models (pydantic) for the HTTP contract."""

from __future__ import annotations

from typing import Literal

from pydantic import BaseModel

from .domain import Transcript


class WordOut(BaseModel):
    text: str
    start_ms: int
    end_ms: int
    probability: float


class TranscriptionResponse(BaseModel):
    language: str
    duration_ms: int
    words: list[WordOut]

    @classmethod
    def from_transcript(cls, t: Transcript) -> TranscriptionResponse:
        return cls(
            language=t.language,
            duration_ms=t.duration_ms,
            words=[
                WordOut(text=w.text, start_ms=w.start_ms, end_ms=w.end_ms, probability=w.probability)
                for w in t.words
            ],
        )


class ErrorBody(BaseModel):
    error: str
    message: str


class StatusBody(BaseModel):
    status: Literal["ok", "loading", "failed"]
    attempt: int | None = None
    message: str | None = None
