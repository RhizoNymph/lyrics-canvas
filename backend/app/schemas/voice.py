"""Voice takes: the multipart ``meta`` document, responses and the error body (contract section 2)."""

from __future__ import annotations

import uuid
from datetime import datetime
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, NonNegativeInt

from app.models.recording import SplitMode
from app.schemas.snippet import SnippetResponse

# Generous: one tap per line; a 25 MiB take is far below this many lines.
MAX_BREAKS = 2000


class NewSnippetTarget(BaseModel):
    """Create a new freestyle snippet at this canvas position."""

    model_config = ConfigDict(allow_inf_nan=False)

    kind: Literal["new"]
    position_x: float
    position_y: float


class AppendTarget(BaseModel):
    """Append the lines after the last line of this snippet (in the same canvas)."""

    kind: Literal["append"]
    snippet_id: uuid.UUID


VoiceTakeTarget = Annotated[NewSnippetTarget | AppendTarget, Field(discriminator="kind")]


class VoiceTakeMeta(BaseModel):
    """The ``meta`` form field, a JSON string."""

    # ms since the recorder started of each confirmed single tap; sorted server-side
    breaks_ms: list[NonNegativeInt] = Field(default_factory=list, max_length=MAX_BREAKS)
    duration_ms: NonNegativeInt
    target: VoiceTakeTarget
    # send the user's word bank to STT as Whisper's initial_prompt (also needs VOICE_VOCAB_HINTS)
    vocabulary_hints: bool = True


class TimedWordResponse(BaseModel):
    """One STT word of a take; times in ms since the recording started."""

    text: str
    start_ms: int
    end_ms: int
    probability: float | None


class RecordingResponse(BaseModel):
    """A stored recording without its audio (fetch that from ``/api/recordings/{id}/audio``)."""

    id: str
    snippet_id: str
    mime_type: str
    duration_ms: int
    byte_size: int
    transcript: str  # the assembled lines joined by "\n"
    split_mode: SplitMode
    created_at: str
    lines: list[str]  # transcript.split("\n")
    has_timings: bool  # words and line_spans are stored
    words: list[TimedWordResponse] | None  # the STT words, in service order
    line_spans: list[tuple[int, int]] | None  # per line, [start, end) indices into words
    language: str | None
    initial_prompt: str | None  # exactly what was sent to STT; None when no prompt was sent
    verified_text: str | None  # the user's checked transcript, lines joined by "\n"
    verified_at: datetime | None


class VerificationRequest(BaseModel):
    """The checked transcript; normalized server-side (docs/features/voice_dataset.md)."""

    text: str


class RecordingStatsResponse(BaseModel):
    """The current user's recordings across all canvases."""

    total: int
    verified: int
    total_duration_ms: int
    verified_duration_ms: int
    with_timings: int


class VoiceTakeResponse(BaseModel):
    snippet: SnippetResponse
    recording: RecordingResponse
    lines: list[str]  # the lines this take added, in order
    split_mode: SplitMode
    created: bool  # True: a new snippet; False: appended to target.snippet_id


class VoiceErrorBody(BaseModel):
    error: str  # a VoiceErrorCode value
    message: str


class VoiceErrorResponse(BaseModel):
    detail: VoiceErrorBody
