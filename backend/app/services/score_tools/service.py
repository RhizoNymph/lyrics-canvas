"""Database side of the score draft and export endpoints (docs/features/score_draft_export.md).

Ownership follows the other snippet routes: a snippet is the caller's when ``Snippet.owner_id`` is
the caller (whatever canvas it is placed on); a recording must also belong to that snippet. Nothing
here writes: a draft is returned for the client to save with ``PUT /api/snippets/{id}``.
"""

from __future__ import annotations

import logging
import uuid
from dataclasses import dataclass
from enum import StrEnum

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.recording import SnippetRecording
from app.models.snippet import Snippet
from app.services.score import Score, ScoreInvalid, check_score
from app.services.score.types import GridJson
from app.services.score_tools.draft import DraftResult, draft_from_recording, draft_grid
from app.services.score_tools.errors import (
    RecordingHasNoTimingsError,
    RecordingNotFoundError,
    ScoreNotFoundError,
    SnippetNotFoundError,
    StoredScoreInvalidError,
)
from app.services.score_tools.export import export_filename, export_title, write_midi, write_musicxml
from app.services.voice.timings import Timings, TimingsError, stored_timings

logger = logging.getLogger(__name__)


class ExportFormat(StrEnum):
    midi = "midi"
    musicxml = "musicxml"

    @property
    def extension(self) -> str:
        return "mid" if self is ExportFormat.midi else "musicxml"

    @property
    def media_type(self) -> str:
        return "audio/midi" if self is ExportFormat.midi else "application/vnd.recordare.musicxml+xml"


@dataclass(frozen=True, slots=True)
class SnippetContent:
    id: uuid.UUID
    raw_text: str
    grid: GridJson
    score: dict | None


@dataclass(frozen=True, slots=True)
class GridDraft:
    meter: dict[str, object]
    bpm: object
    note: object


@dataclass(frozen=True, slots=True)
class RecordingDraft:
    recording_id: uuid.UUID
    meter: dict[str, object]
    bpm: object | None


DraftRequest = GridDraft | RecordingDraft


@dataclass(frozen=True, slots=True)
class ExportFile:
    content: bytes
    media_type: str
    filename: str


async def load_snippet(db: AsyncSession, snippet_id: uuid.UUID, owner_id: uuid.UUID) -> SnippetContent:
    result = await db.execute(
        select(Snippet.id, Snippet.raw_text, Snippet.grid_data, Snippet.score).where(
            Snippet.id == snippet_id, Snippet.owner_id == owner_id
        )
    )
    row = result.one_or_none()
    if row is None:
        raise SnippetNotFoundError(f"snippet {snippet_id} not found")
    return SnippetContent(id=row.id, raw_text=row.raw_text or "", grid=row.grid_data, score=row.score)


async def load_timings(db: AsyncSession, recording_id: uuid.UUID, snippet_id: uuid.UUID, owner_id: uuid.UUID) -> Timings:
    owned = select(Snippet.id).where(Snippet.id == snippet_id, Snippet.owner_id == owner_id)
    result = await db.execute(
        select(SnippetRecording.words, SnippetRecording.line_spans).where(
            SnippetRecording.id == recording_id,
            SnippetRecording.snippet_id == snippet_id,
            SnippetRecording.snippet_id.in_(owned),
        )
    )
    row = result.one_or_none()
    if row is None:
        raise RecordingNotFoundError(f"recording {recording_id} not found on snippet {snippet_id}")
    try:
        timings = stored_timings(row.words, row.line_spans)
    except TimingsError as exc:
        logger.warning("score.draft.timings_malformed recording_id=%s error=%s", recording_id, exc)
        raise RecordingHasNoTimingsError(f"recording {recording_id} has unreadable word timings") from exc
    if timings is None:
        raise RecordingHasNoTimingsError(f"recording {recording_id} has no word timings")
    return timings


async def draft(db: AsyncSession, snippet_id: uuid.UUID, owner_id: uuid.UUID, request: DraftRequest) -> DraftResult:
    """Raises SnippetNotFoundError, RecordingNotFoundError, RecordingHasNoTimingsError,
    DraftEmptyError or ScoreInvalid."""
    snippet = await load_snippet(db, snippet_id, owner_id)
    match request:
        case GridDraft(meter=meter, bpm=bpm, note=note):
            result = draft_grid(snippet.grid, meter, bpm, note)
            logger.info("score.draft.grid snippet_id=%s notes=%d", snippet_id, len(result.score.all_notes()))
            return result
        case RecordingDraft(recording_id=recording_id, meter=meter, bpm=bpm):
            timings = await load_timings(db, recording_id, snippet_id, owner_id)
            return draft_from_recording(snippet.grid, timings, meter, bpm)


def stored_score(snippet: SnippetContent) -> Score:
    if snippet.score is None:
        raise ScoreNotFoundError(f"snippet {snippet.id} has no score")
    try:
        return check_score(snippet.score, snippet.grid)
    except ScoreInvalid as exc:
        logger.error("score.export.stored_invalid snippet_id=%s reason=%s", snippet.id, exc.reason.value)
        raise StoredScoreInvalidError(f"the stored score of snippet {snippet.id} is not valid") from exc


def render(snippet: SnippetContent, export_format: ExportFormat) -> ExportFile:
    score = stored_score(snippet)
    title = export_title(snippet.raw_text)
    match export_format:
        case ExportFormat.midi:
            content = write_midi(score, snippet.grid, title)
        case ExportFormat.musicxml:
            content = write_musicxml(score, snippet.grid, title)
    logger.info(
        "score.export snippet_id=%s format=%s notes=%d bytes=%d",
        snippet.id, export_format.value, len(score.all_notes()), len(content),
    )
    return ExportFile(content, export_format.media_type, export_filename(snippet.raw_text, snippet.id, export_format.extension))


async def export(db: AsyncSession, snippet_id: uuid.UUID, owner_id: uuid.UUID, export_format: ExportFormat) -> ExportFile:
    """Raises SnippetNotFoundError, ScoreNotFoundError or StoredScoreInvalidError."""
    return render(await load_snippet(db, snippet_id, owner_id), export_format)
