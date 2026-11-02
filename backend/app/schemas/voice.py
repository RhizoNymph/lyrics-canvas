"""Voice takes: the multipart ``meta`` document, responses and the error body (contract section 2)."""

from __future__ import annotations

import uuid
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
