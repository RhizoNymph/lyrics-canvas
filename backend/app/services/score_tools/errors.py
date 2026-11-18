"""Typed failures of the score draft and export endpoints (docs/features/score_draft_export.md).

Each carries its wire code and HTTP status; the API answers
``HTTPException(status, {"error": code, "message": message})``. Score format failures
(``ScoreInvalid``: bad meter, bpm, note, ``exceeds_max_ticks``) are not here: they keep the shared
``invalid_score`` body ``{"error": "invalid_score", "reason", "line"?, "note"?}``.
"""

from __future__ import annotations

from enum import StrEnum
from typing import ClassVar


class ScoreToolErrorCode(StrEnum):
    snippet_not_found = "snippet_not_found"
    recording_not_found = "recording_not_found"
    recording_has_no_timings = "recording_has_no_timings"
    draft_empty = "draft_empty"
    score_not_found = "score_not_found"
    stored_score_invalid = "stored_score_invalid"


class ScoreToolError(Exception):
    code: ClassVar[ScoreToolErrorCode]
    status: ClassVar[int]

    def __init__(self, message: str) -> None:
        super().__init__(message)
        self.message = message


class SnippetNotFoundError(ScoreToolError):
    """The snippet does not exist or is not the caller's."""

    code = ScoreToolErrorCode.snippet_not_found
    status = 404


class RecordingNotFoundError(ScoreToolError):
    """The recording does not exist, is not the caller's, or belongs to another snippet."""

    code = ScoreToolErrorCode.recording_not_found
    status = 404


class RecordingHasNoTimingsError(ScoreToolError):
    """The recording predates word timings (``words`` or ``line_spans`` is null)."""

    code = ScoreToolErrorCode.recording_has_no_timings
    status = 422


class DraftEmptyError(ScoreToolError):
    """The draft would place no syllable (empty grid, or no take word matched a grid word)."""

    code = ScoreToolErrorCode.draft_empty
    status = 422


class ScoreNotFoundError(ScoreToolError):
    """Export of a snippet whose ``score`` is null."""

    code = ScoreToolErrorCode.score_not_found
    status = 404


class StoredScoreInvalidError(ScoreToolError):
    """The stored score does not validate against the stored grid (never expected: the save flow
    keeps them consistent). Logged as an error."""

    code = ScoreToolErrorCode.stored_score_invalid
    status = 500
