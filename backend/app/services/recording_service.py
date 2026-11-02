"""Persistence for voice takes and snippet recordings.

Every write here is one transaction: the snippet change (new snippet, or appended lines) and the
recording insert commit together or not at all. Ownership is always checked through
``Canvas.owner_id``. Audio bytes are never logged and never loaded except by ``get_audio``.
"""

from __future__ import annotations

import logging
import uuid
from collections.abc import Sequence
from dataclasses import dataclass

from sqlalchemy import delete, func, select, update
from sqlalchemy.exc import SQLAlchemyError
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import selectinload

from app.models.canvas import Canvas
from app.models.recording import SnippetRecording, SplitMode
from app.models.snippet import GridMode, Snippet, SnippetType
from app.schemas.phonetics import SnippetPhonetics
from app.services.snippet_content import content_for, derive_new_content
from app.services.voice.append import append_lines
from app.services.voice.errors import (
    CanvasNotFoundError,
    RecordingNotFoundError,
    SnippetNotFoundError,
    VoicePersistError,
)

logger = logging.getLogger(__name__)


@dataclass(frozen=True, slots=True)
class NewRecording:
    mime_type: str
    duration_ms: int
    audio: bytes
    transcript: str
    split_mode: SplitMode

    def row(self, snippet_id: uuid.UUID) -> SnippetRecording:
        return SnippetRecording(
            snippet_id=snippet_id,
            mime_type=self.mime_type,
            duration_ms=self.duration_ms,
            byte_size=len(self.audio),
            audio=self.audio,
            transcript=self.transcript,
            split_mode=self.split_mode,
        )


@dataclass(frozen=True, slots=True)
class StoredAudio:
    mime_type: str
    audio: bytes


# ---- ownership lookups ----


async def canvas_owned(db: AsyncSession, canvas_id: uuid.UUID, owner_id: uuid.UUID) -> bool:
    result = await db.execute(select(Canvas.id).where(Canvas.id == canvas_id, Canvas.owner_id == owner_id))
    return result.scalar_one_or_none() is not None


async def snippet_in_canvas(db: AsyncSession, snippet_id: uuid.UUID, canvas_id: uuid.UUID, owner_id: uuid.UUID) -> bool:
    result = await db.execute(
        select(Snippet.id).join(Canvas)
        .where(Snippet.id == snippet_id, Snippet.canvas_id == canvas_id, Canvas.owner_id == owner_id)
    )
    return result.scalar_one_or_none() is not None


async def _snippet_with_tags(db: AsyncSession, snippet_id: uuid.UUID) -> Snippet:
    result = await db.execute(
        select(Snippet).options(selectinload(Snippet.tags)).where(Snippet.id == snippet_id)
        .execution_options(populate_existing=True)
    )
    return result.scalar_one()


async def _commit(db: AsyncSession, event: str, **context: object) -> None:
    try:
        await db.commit()
    except SQLAlchemyError as exc:
        await db.rollback()
        logger.error("%s.failed %s error=%s", event, " ".join(f"{k}={v}" for k, v in context.items()), type(exc).__name__)
        raise VoicePersistError("could not store the voice take; nothing changed") from exc


# ---- voice takes ----


async def create_snippet_with_recording(
    db: AsyncSession,
    canvas_id: uuid.UUID,
    owner_id: uuid.UUID,
    *,
    position_x: float,
    position_y: float,
    lines: Sequence[str],
    recording: NewRecording,
) -> tuple[Snippet, SnippetRecording]:
    """A new freestyle snippet holding ``lines`` (same derivation as POST /snippets) plus the recording."""
    if not await canvas_owned(db, canvas_id, owner_id):
        raise CanvasNotFoundError("canvas not found")
    content = derive_new_content("\n".join(lines))
    snippet = Snippet(
        canvas_id=canvas_id,
        snippet_type=SnippetType.freestyle,
        grid_mode=GridMode.auto,
        raw_text=content.raw_text,
        grid_data=content.grid_data,
        phonetics=content.phonetics,
        position_x=position_x,
        position_y=position_y,
        width=content.width,
        height=content.height,
    )
    try:
        db.add(snippet)
        await db.flush()
        row = recording.row(snippet.id)
        db.add(row)
    except SQLAlchemyError as exc:
        await db.rollback()
        logger.error("voice_take.create.failed canvas_id=%s error=%s", canvas_id, type(exc).__name__)
        raise VoicePersistError("could not store the voice take; nothing changed") from exc
    await _commit(db, "voice_take.create", canvas_id=canvas_id)
    logger.info(
        "voice_take.created canvas_id=%s snippet_id=%s recording_id=%s lines=%d bytes=%d split_mode=%s",
        canvas_id, snippet.id, row.id, len(lines), row.byte_size, row.split_mode.value,
    )
    return await _snippet_with_tags(db, snippet.id), row


async def append_with_recording(
    db: AsyncSession,
    canvas_id: uuid.UUID,
    owner_id: uuid.UUID,
    snippet_id: uuid.UUID,
    *,
    lines: Sequence[str],
    recording: NewRecording,
) -> tuple[Snippet, SnippetRecording]:
    """Append ``lines`` after the snippet's last line (existing grid verbatim) plus the recording.

    The snippet row is locked (SELECT ... FOR UPDATE) so a concurrent edit cannot interleave.
    """
    result = await db.execute(
        select(Snippet).join(Canvas)
        .where(Snippet.id == snippet_id, Snippet.canvas_id == canvas_id, Canvas.owner_id == owner_id)
        .with_for_update(of=Snippet)
        .execution_options(populate_existing=True)
    )
    snippet = result.scalar_one_or_none()
    if snippet is None:
        raise SnippetNotFoundError("snippet not found")
    appended = append_lines(snippet.raw_text or "", snippet.grid_data, lines)
    overrides = SnippetPhonetics.model_validate(snippet.phonetics).overrides if snippet.phonetics else None
    content = content_for(appended.raw_text, appended.grid_data, overrides)
    snippet.raw_text = content.raw_text
    snippet.grid_data = content.grid_data
    snippet.phonetics = content.phonetics
    snippet.width = content.width
    snippet.height = content.height
    # SQL now(): the transaction clock, equal to the updated_at written by the same UPDATE.
    snippet.content_updated_at = func.now()
    row = recording.row(snippet.id)
    db.add(row)
    await _commit(db, "voice_take.append", canvas_id=canvas_id, snippet_id=snippet_id)
    logger.info(
        "voice_take.appended canvas_id=%s snippet_id=%s recording_id=%s lines=%d bytes=%d split_mode=%s",
        canvas_id, snippet_id, row.id, len(lines), row.byte_size, row.split_mode.value,
    )
    return await _snippet_with_tags(db, snippet.id), row


# ---- recordings ----


async def list_recordings(db: AsyncSession, snippet_id: uuid.UUID, owner_id: uuid.UUID) -> list[SnippetRecording]:
    """The snippet's recordings, newest first, audio not loaded."""
    owned = await db.execute(
        select(Snippet.id).join(Canvas).where(Snippet.id == snippet_id, Canvas.owner_id == owner_id)
    )
    if owned.scalar_one_or_none() is None:
        raise SnippetNotFoundError("snippet not found")
    result = await db.execute(
        select(SnippetRecording)
        .where(SnippetRecording.snippet_id == snippet_id)
        .order_by(SnippetRecording.created_at.desc(), SnippetRecording.id.desc())
    )
    return list(result.scalars().all())


def _owned_recording(recording_id: uuid.UUID, owner_id: uuid.UUID):
    owned_snippets = select(Snippet.id).join(Canvas).where(Canvas.owner_id == owner_id)
    return (SnippetRecording.id == recording_id, SnippetRecording.snippet_id.in_(owned_snippets))


async def get_audio(db: AsyncSession, recording_id: uuid.UUID, owner_id: uuid.UUID) -> StoredAudio:
    result = await db.execute(
        select(SnippetRecording.mime_type, SnippetRecording.audio).where(*_owned_recording(recording_id, owner_id))
    )
    row = result.one_or_none()
    if row is None:
        raise RecordingNotFoundError("recording not found")
    return StoredAudio(mime_type=row.mime_type, audio=row.audio)


async def delete_recording(db: AsyncSession, recording_id: uuid.UUID, owner_id: uuid.UUID) -> None:
    try:
        result = await db.execute(
            delete(SnippetRecording).where(*_owned_recording(recording_id, owner_id))
            .execution_options(synchronize_session=False)
        )
    except SQLAlchemyError as exc:
        await db.rollback()
        raise VoicePersistError("could not delete the recording") from exc
    if result.rowcount == 0:
        await db.rollback()
        raise RecordingNotFoundError("recording not found")
    await _commit(db, "recording.delete", recording_id=recording_id)
    logger.info("recording.deleted recording_id=%s", recording_id)


async def reassign_recordings(db: AsyncSession, to_snippet_id: uuid.UUID, from_snippet_ids: Sequence[uuid.UUID]) -> None:
    """Move the recordings of ``from_snippet_ids`` onto ``to_snippet_id`` (inside the caller's
    transaction; the merge calls this before deleting the originals so the cascade spares them)."""
    if not from_snippet_ids:
        return
    await db.execute(
        update(SnippetRecording)
        .where(SnippetRecording.snippet_id.in_(list(from_snippet_ids)))
        .values(snippet_id=to_snippet_id)
        .execution_options(synchronize_session=False)
    )
