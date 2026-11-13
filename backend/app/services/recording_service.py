"""Persistence for voice takes and snippet recordings.

Every write here is one transaction: the snippet change (new snippet, or appended lines) and the
recording insert commit together or not at all. Canvas-scoped checks (a take on a canvas) go
through the placement's ``Canvas.owner_id``; snippet and recording ownership through
``Snippet.owner_id`` (docs/features/snippet_placements.md). Audio bytes are never logged and never loaded except by ``get_audio``.
Transcript, checked-transcript and prompt text are never logged.

Dataset columns (migration 007, docs/features/voice_dataset.md): a take stores its STT words,
line spans, language and prompt; ``set_verification`` / ``clear_verification`` keep
``verified_text`` and ``verified_at`` both set or both null; ``recording_stats`` and
``export_recordings`` cover all of the user's canvases.
"""

from __future__ import annotations

import logging
import uuid
from collections.abc import Sequence
from dataclasses import dataclass

from sqlalchemy import and_, delete, func, select, update
from sqlalchemy.exc import SQLAlchemyError
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.canvas import Canvas
from app.models.placement import SnippetPlacement
from app.models.recording import LANGUAGE_MAX_LENGTH, SnippetRecording, SplitMode
from app.models.snippet import GridMode, Snippet, SnippetType
from app.models.word_bank import BankWord
from app.schemas.phonetics import SnippetPhonetics
from app.services.placements.queries import placed_by_placement_id, placed_snippet
from app.services.placements.view import PlacedSnippet
from app.services.snippet_content import content_for, derive_new_content
from app.services.sync.bump import bump
from app.services.voice.append import append_lines
from app.services.voice.dataset_export import ExportRecording
from app.services.voice.errors import (
    CanvasNotFoundError,
    ExportTooLargeError,
    RecordingNotFoundError,
    SnippetNotFoundError,
    VoicePersistError,
)
from app.services.voice.hints import HintWord
from app.services.voice.timings import Timings, TimingsError, spans_json, stored_timings, words_json
from app.services.voice.verification import VerifiedText

logger = logging.getLogger(__name__)


@dataclass(frozen=True, slots=True)
class NewRecording:
    mime_type: str
    duration_ms: int
    audio: bytes
    transcript: str
    split_mode: SplitMode
    timings: Timings | None = None  # the take's STT words and the spans behind ``transcript``'s lines
    language: str | None = None
    initial_prompt: str | None = None  # exactly what was sent to STT

    def row(self, snippet_id: uuid.UUID) -> SnippetRecording:
        return SnippetRecording(
            snippet_id=snippet_id,
            mime_type=self.mime_type,
            duration_ms=self.duration_ms,
            byte_size=len(self.audio),
            audio=self.audio,
            transcript=self.transcript,
            split_mode=self.split_mode,
            words=None if self.timings is None else words_json(self.timings.words),
            line_spans=None if self.timings is None else spans_json(self.timings.spans),
            language=stored_language(self.language),
            initial_prompt=self.initial_prompt,
        )


def stored_language(language: str | None) -> str | None:
    """The language as stored; a code longer than the column is not a language code, so None."""
    if language is None or not language.strip():
        return None
    code = language.strip()
    if len(code) > LANGUAGE_MAX_LENGTH:
        logger.warning("recording.language_dropped length=%d", len(code))
        return None
    return code


def timings_of(row: SnippetRecording) -> Timings | None:
    """The row's stored timings; malformed data (never written by this code) reads as none."""
    try:
        return stored_timings(row.words, row.line_spans)
    except TimingsError as exc:
        logger.warning("recording.timings_unreadable recording_id=%s error=%s", row.id, exc)
        return None


@dataclass(frozen=True, slots=True)
class RecordingStats:
    total: int
    verified: int
    total_duration_ms: int
    verified_duration_ms: int
    with_timings: int


@dataclass(frozen=True, slots=True)
class StoredAudio:
    mime_type: str
    audio: bytes


# ---- ownership lookups ----


async def canvas_owned(db: AsyncSession, canvas_id: uuid.UUID, owner_id: uuid.UUID) -> bool:
    result = await db.execute(select(Canvas.id).where(Canvas.id == canvas_id, Canvas.owner_id == owner_id))
    return result.scalar_one_or_none() is not None


async def snippet_in_canvas(db: AsyncSession, snippet_id: uuid.UUID, canvas_id: uuid.UUID, owner_id: uuid.UUID) -> bool:
    """The snippet is placed on the caller's canvas."""
    result = await db.execute(
        select(SnippetPlacement.id).join(Canvas, Canvas.id == SnippetPlacement.canvas_id)
        .where(SnippetPlacement.snippet_id == snippet_id, SnippetPlacement.canvas_id == canvas_id, Canvas.owner_id == owner_id)
    )
    return result.scalar_one_or_none() is not None


async def _commit(
    db: AsyncSession, event: str, message: str = "could not store the voice take; nothing changed", **context: object
) -> None:
    try:
        await db.commit()
    except SQLAlchemyError as exc:
        await db.rollback()
        logger.error("%s.failed %s error=%s", event, " ".join(f"{k}={v}" for k, v in context.items()), type(exc).__name__)
        raise VoicePersistError(message) from exc


# ---- vocabulary hints ----


async def bank_hint_words(db: AsyncSession, owner_id: uuid.UUID) -> list[HintWord]:
    """The user's bank words for the STT prompt. A failed read degrades to no hints (logged)."""
    try:
        result = await db.execute(select(BankWord.text, BankWord.created_at).where(BankWord.user_id == owner_id))
    except SQLAlchemyError as exc:
        await db.rollback()
        logger.warning("voice.take.prompt_words_failed error=%s", type(exc).__name__)
        return []
    return [HintWord(text=row.text, created_at=row.created_at) for row in result.all()]


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
) -> tuple[PlacedSnippet, SnippetRecording]:
    """A new freestyle snippet holding ``lines`` (same derivation as POST /snippets), placed on the
    take's canvas, plus the recording."""
    if not await canvas_owned(db, canvas_id, owner_id):
        raise CanvasNotFoundError("canvas not found")
    content = derive_new_content("\n".join(lines))
    snippet = Snippet(
        id=uuid.uuid4(),
        owner_id=owner_id,
        snippet_type=SnippetType.freestyle,
        grid_mode=GridMode.auto,
        raw_text=content.raw_text,
        grid_data=content.grid_data,
        phonetics=content.phonetics,
    )
    placement = SnippetPlacement(
        id=uuid.uuid4(),
        snippet_id=snippet.id,
        canvas_id=canvas_id,
        position_x=position_x,
        position_y=position_y,
        width=content.width,
        height=content.height,
    )
    try:
        db.add(snippet)
        db.add(placement)
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
    return await placed_by_placement_id(db, placement.id), row


async def append_with_recording(
    db: AsyncSession,
    canvas_id: uuid.UUID,
    owner_id: uuid.UUID,
    snippet_id: uuid.UUID,
    *,
    lines: Sequence[str],
    recording: NewRecording,
) -> tuple[PlacedSnippet, SnippetRecording]:
    """Append ``lines`` after the snippet's last line (existing grid verbatim) plus the recording.

    The snippet must be placed on the take's canvas; that placement is resized to the new grid
    (other canvases keep their size). The snippet row is locked (SELECT ... FOR UPDATE) so a
    concurrent edit cannot interleave.
    """
    placed = await placed_snippet(db, snippet_id, canvas_id, owner_id, lock=True)
    if placed is None:
        raise SnippetNotFoundError("snippet not found")
    snippet = placed.snippet
    appended = append_lines(snippet.raw_text or "", snippet.grid_data, lines)
    overrides = SnippetPhonetics.model_validate(snippet.phonetics).overrides if snippet.phonetics else None
    content = content_for(appended.raw_text, appended.grid_data, overrides)
    snippet.raw_text = content.raw_text
    snippet.grid_data = content.grid_data
    snippet.phonetics = content.phonetics
    placed.placement.width = content.width
    placed.placement.height = content.height
    # SQL now(): the transaction clock, equal to the updated_at written by the same UPDATE.
    snippet.content_updated_at = func.now()
    bump(snippet)  # an append is a content change (docs/features/sync_versions.md#versions)
    row = recording.row(snippet.id)
    db.add(row)
    await _commit(db, "voice_take.append", canvas_id=canvas_id, snippet_id=snippet_id)
    logger.info(
        "voice_take.appended canvas_id=%s snippet_id=%s recording_id=%s lines=%d bytes=%d split_mode=%s",
        canvas_id, snippet_id, row.id, len(lines), row.byte_size, row.split_mode.value,
    )
    return await placed_by_placement_id(db, placed.placement_id), row


# ---- recordings ----


async def list_recordings(db: AsyncSession, snippet_id: uuid.UUID, owner_id: uuid.UUID) -> list[SnippetRecording]:
    """The snippet's recordings, newest first, audio not loaded."""
    owned = await db.execute(
        select(Snippet.id).where(Snippet.id == snippet_id, Snippet.owner_id == owner_id)
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
    owned_snippets = select(Snippet.id).where(Snippet.owner_id == owner_id)
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


async def _owned_row(db: AsyncSession, recording_id: uuid.UUID, owner_id: uuid.UUID, *, lock: bool = False) -> SnippetRecording:
    query = select(SnippetRecording).where(*_owned_recording(recording_id, owner_id))
    if lock:
        query = query.with_for_update()
    result = await db.execute(query.execution_options(populate_existing=True))
    row = result.scalar_one_or_none()
    if row is None:
        raise RecordingNotFoundError("recording not found")
    return row


async def get_recording(db: AsyncSession, recording_id: uuid.UUID, owner_id: uuid.UUID) -> SnippetRecording:
    """One recording without its audio."""
    return await _owned_row(db, recording_id, owner_id)


async def set_verification(
    db: AsyncSession, recording_id: uuid.UUID, owner_id: uuid.UUID, text: VerifiedText
) -> SnippetRecording:
    """Store the checked transcript with ``verified_at = now()`` (the transaction clock)."""
    row = await _owned_row(db, recording_id, owner_id, lock=True)
    row.verified_text = text
    row.verified_at = func.now()
    await _commit(db, "recording.verify", "could not save the checked transcript", recording_id=recording_id)
    await db.refresh(row, ["verified_text", "verified_at"])
    logger.info("recording.verified recording_id=%s chars=%d", recording_id, len(text))
    return row


async def clear_verification(db: AsyncSession, recording_id: uuid.UUID, owner_id: uuid.UUID) -> SnippetRecording:
    """Clear both verification fields; idempotent."""
    row = await _owned_row(db, recording_id, owner_id, lock=True)
    row.verified_text = None
    row.verified_at = None
    await _commit(db, "recording.unverify", "could not clear the checked transcript", recording_id=recording_id)
    logger.info("recording.unverified recording_id=%s", recording_id)
    return row


def _owned_by(owner_id: uuid.UUID):
    return SnippetRecording.snippet_id.in_(select(Snippet.id).where(Snippet.owner_id == owner_id))


async def recording_stats(db: AsyncSession, owner_id: uuid.UUID) -> RecordingStats:
    verified = SnippetRecording.verified_text.is_not(None)
    timed = and_(SnippetRecording.words.is_not(None), SnippetRecording.line_spans.is_not(None))
    result = await db.execute(
        select(
            func.count(),
            func.count().filter(verified),
            func.coalesce(func.sum(SnippetRecording.duration_ms), 0),
            func.coalesce(func.sum(SnippetRecording.duration_ms).filter(verified), 0),
            func.count().filter(timed),
        ).where(_owned_by(owner_id))
    )
    total, verified_count, total_ms, verified_ms, with_timings = result.one()
    return RecordingStats(
        total=int(total), verified=int(verified_count), total_duration_ms=int(total_ms),
        verified_duration_ms=int(verified_ms), with_timings=int(with_timings),
    )


def _export_recording(row: SnippetRecording) -> ExportRecording:
    return ExportRecording(
        id=row.id,
        mime_type=row.mime_type,
        duration_ms=row.duration_ms,
        byte_size=row.byte_size,
        language=row.language,
        split_mode=row.split_mode,
        created_at=row.created_at,
        verified_at=row.verified_at,
        verified_text=row.verified_text,
        transcript=row.transcript,
        initial_prompt=row.initial_prompt,
        timings=timings_of(row),
    )


async def export_recordings(
    db: AsyncSession, owner_id: uuid.UUID, *, include_unverified: bool, max_audio_bytes: int
) -> list[ExportRecording]:
    """The recordings to export (no audio), oldest first; ``ExportTooLargeError`` over the limit."""
    query = select(SnippetRecording).where(_owned_by(owner_id))
    if not include_unverified:
        query = query.where(SnippetRecording.verified_text.is_not(None))
    result = await db.execute(query.order_by(SnippetRecording.created_at, SnippetRecording.id))
    records = [_export_recording(row) for row in result.scalars().all()]
    audio_bytes = sum(r.byte_size for r in records)
    if audio_bytes > max_audio_bytes:
        logger.info("voice.export.too_large recordings=%d bytes=%d limit=%d", len(records), audio_bytes, max_audio_bytes)
        raise ExportTooLargeError(
            f"the export would hold {audio_bytes} bytes of audio; the limit is {max_audio_bytes}"
        )
    return records


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
