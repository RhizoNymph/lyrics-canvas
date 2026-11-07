"""Voice takes and snippet recordings (docs/features/voice_capture.md, contract section 2), plus the
recording dataset routes: transcript verification, stats and the zip export
(docs/features/voice_dataset.md).

Errors are ``HTTPException`` with ``detail = {"error": <code>, "message": <text>}`` so the clients
can switch on the code. Audio bytes, transcript text and prompt text are never logged.

Route order: the fixed ``/api/recordings/stats`` and ``/api/recordings/export`` paths are declared
before the ``/api/recordings/{recording_id}...`` routes so they are never read as an id.
"""

from __future__ import annotations

import logging
import uuid
from collections.abc import Iterator
from datetime import datetime, timezone
from typing import IO

from fastapi import APIRouter, Depends, File, Form, HTTPException, Query, Response, UploadFile
from fastapi.responses import StreamingResponse
from pydantic import ValidationError
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.canvases import _snippet_response
from app.api.deps import get_current_user
from app.config import settings
from app.db.database import get_db
from app.models.user import User
from app.schemas.voice import (
    AppendTarget,
    NewSnippetTarget,
    RecordingResponse,
    RecordingStatsResponse,
    TimedWordResponse,
    VerificationRequest,
    VoiceErrorResponse,
    VoiceTakeMeta,
    VoiceTakeResponse,
)
from app.services import recording_service
from app.services.voice.audio import accepted_mime, read_bounded
from app.services.voice.dataset_export import build_export, export_filename
from app.services.voice.engine_oracle import EngineOracle
from app.services.voice.errors import (
    CanvasNotFoundError,
    EmptyTranscriptError,
    InvalidMetaError,
    SnippetNotFoundError,
    VoiceTakeError,
)
from app.services.voice.hints import build_hint_prompt, eligible_words
from app.services.voice.line_assembly import LineAssemblyError, assemble_lines
from app.services.voice.stt_client import SttClient, Transcriber
from app.services.voice.timings import Timings
from app.services.voice.verification import normalize_verification

logger = logging.getLogger(__name__)

router = APIRouter(tags=["voice"])

AUDIO_CACHE_CONTROL = "private, max-age=0"
EXPORT_CHUNK_BYTES = 1024 * 1024


def get_transcriber() -> Transcriber:
    """The STT client from settings; tests override this dependency with a fake."""
    return SttClient(settings.stt_url, settings.stt_timeout_seconds)


def _http_error(exc: VoiceTakeError) -> HTTPException:
    return HTTPException(status_code=exc.status, detail={"error": exc.code.value, "message": exc.message})


def _errors(*statuses: int) -> dict[int | str, dict[str, object]]:
    return {status: {"model": VoiceErrorResponse} for status in statuses}


def _owner(user: User) -> uuid.UUID:
    return user.id if isinstance(user.id, uuid.UUID) else uuid.UUID(str(user.id))


def parse_meta(raw: str | None) -> VoiceTakeMeta:
    if raw is None or not raw.strip():
        raise InvalidMetaError("the meta field is required")
    try:
        return VoiceTakeMeta.model_validate_json(raw)
    except ValidationError as exc:
        # loc + message only: never echo the submitted values
        problems = "; ".join(
            f"{'.'.join(str(part) for part in error['loc']) or 'meta'}: {error['msg']}"
            for error in exc.errors(include_input=False, include_url=False)[:5]
        )
        raise InvalidMetaError(f"invalid meta: {problems}") from exc


def _recording_response(row) -> RecordingResponse:
    timings = recording_service.timings_of(row)
    return RecordingResponse(
        id=str(row.id),
        snippet_id=str(row.snippet_id),
        mime_type=row.mime_type,
        duration_ms=row.duration_ms,
        byte_size=row.byte_size,
        transcript=row.transcript,
        split_mode=row.split_mode,
        created_at=str(row.created_at),
        lines=row.transcript.split("\n"),
        has_timings=timings is not None,
        words=None if timings is None else [
            TimedWordResponse(text=w.text, start_ms=w.start_ms, end_ms=w.end_ms, probability=w.probability)
            for w in timings.words
        ],
        line_spans=None if timings is None else [tuple(span) for span in timings.spans],
        language=row.language,
        initial_prompt=row.initial_prompt,
        verified_text=row.verified_text,
        verified_at=row.verified_at,
    )


async def _vocabulary_prompt(db: AsyncSession, owner: uuid.UUID, wanted: bool) -> str | None:
    """The STT initial_prompt from the user's word bank, or None (off, empty bank, nothing fits)."""
    if not (wanted and settings.voice_vocab_hints):
        return None
    words = await recording_service.bank_hint_words(db, owner)
    prompt = build_hint_prompt(words, settings.voice_vocab_hint_max_chars)
    logger.info(
        "voice.take.prompt words_offered=%d words_used=%d chars=%d",
        len(eligible_words(words)) if prompt is None else prompt.words_offered,
        0 if prompt is None else prompt.words_used,
        0 if prompt is None else prompt.chars,
    )
    return None if prompt is None else prompt.text


async def _voice_take(
    canvas_id: uuid.UUID, audio: UploadFile, meta: str | None, owner: uuid.UUID, db: AsyncSession, transcriber: Transcriber
) -> VoiceTakeResponse:
    parsed = parse_meta(meta)
    mime_type = accepted_mime(audio.content_type)
    data = await read_bounded(audio, settings.voice_max_upload_bytes)

    # Ownership before the (expensive) transcription.
    if not await recording_service.canvas_owned(db, canvas_id, owner):
        raise CanvasNotFoundError("canvas not found")
    match parsed.target:
        case AppendTarget(snippet_id=snippet_id):
            if not await recording_service.snippet_in_canvas(db, snippet_id, canvas_id, owner):
                raise SnippetNotFoundError("snippet not found in this canvas")
        case NewSnippetTarget():
            pass
    if not data:
        raise EmptyTranscriptError("the recording is empty")

    prompt = await _vocabulary_prompt(db, owner, parsed.vocabulary_hints)
    transcript = await transcriber.transcribe(data, mime_type, prompt)
    try:
        assembled = assemble_lines(transcript.words, parsed.breaks_ms, EngineOracle())
    except LineAssemblyError as exc:
        raise EmptyTranscriptError("no speech was recognised in the recording") from exc
    lines = list(assembled.lines)
    recording = recording_service.NewRecording(
        mime_type=mime_type,
        # the service's decoded duration is exact; the client's clock is the fallback
        duration_ms=transcript.duration_ms if transcript.duration_ms and transcript.duration_ms > 0 else parsed.duration_ms,
        audio=data,
        transcript="\n".join(lines),
        split_mode=assembled.split_mode,
        timings=Timings.of(assembled),
        language=transcript.language,
        initial_prompt=prompt,
    )
    logger.info(
        "voice_take.assembled canvas_id=%s target=%s bytes=%d words=%d breaks=%d lines=%d split_mode=%s",
        canvas_id, parsed.target.kind, len(data), len(transcript.words), len(parsed.breaks_ms), len(lines),
        assembled.split_mode.value,
    )
    match parsed.target:
        case NewSnippetTarget(position_x=x, position_y=y):
            snippet, row = await recording_service.create_snippet_with_recording(
                db, canvas_id, owner, position_x=x, position_y=y, lines=lines, recording=recording
            )
            created = True
        case AppendTarget(snippet_id=snippet_id):
            snippet, row = await recording_service.append_with_recording(
                db, canvas_id, owner, snippet_id, lines=lines, recording=recording
            )
            created = False
    return VoiceTakeResponse(
        snippet=_snippet_response(snippet),
        recording=_recording_response(row),
        lines=lines,
        split_mode=assembled.split_mode,
        created=created,
    )


@router.post(
    "/api/canvases/{canvas_id}/voice-takes",
    response_model=VoiceTakeResponse,
    status_code=201,
    responses=_errors(404, 413, 415, 422, 500, 502, 503),
)
async def create_voice_take(
    canvas_id: uuid.UUID,
    audio: UploadFile = File(..., description="The recording; audio/webm, ogg, mp4, aac, mpeg or wav"),
    meta: str | None = Form(None, description='JSON: {"breaks_ms": [int], "duration_ms": int, "target": {...}}'),
    user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
    transcriber: Transcriber = Depends(get_transcriber),
):
    """Transcribe a recording into a new snippet or onto the end of one, and keep the audio."""
    try:
        return await _voice_take(canvas_id, audio, meta, _owner(user), db, transcriber)
    except VoiceTakeError as exc:
        logger.info("voice_take.rejected canvas_id=%s error=%s status=%d", canvas_id, exc.code.value, exc.status)
        raise _http_error(exc) from exc


@router.get(
    "/api/snippets/{snippet_id}/recordings",
    response_model=list[RecordingResponse],
    responses=_errors(404),
)
async def list_recordings(
    snippet_id: uuid.UUID,
    user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    """The snippet's recordings, newest first (no audio bytes)."""
    try:
        rows = await recording_service.list_recordings(db, snippet_id, _owner(user))
    except VoiceTakeError as exc:
        raise _http_error(exc) from exc
    return [_recording_response(row) for row in rows]


@router.get("/api/recordings/stats", response_model=RecordingStatsResponse)
async def get_recording_stats(
    user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    """Counts and durations of the current user's recordings across all canvases."""
    stats = await recording_service.recording_stats(db, _owner(user))
    return RecordingStatsResponse(
        total=stats.total,
        verified=stats.verified,
        total_duration_ms=stats.total_duration_ms,
        verified_duration_ms=stats.verified_duration_ms,
        with_timings=stats.with_timings,
    )


def _file_chunks(file: IO[bytes]) -> Iterator[bytes]:
    """The finished zip in chunks; closes (and so deletes) the temp file when done or aborted."""
    try:
        while chunk := file.read(EXPORT_CHUNK_BYTES):
            yield chunk
    finally:
        file.close()


@router.get(
    "/api/recordings/export",
    response_class=StreamingResponse,
    responses={
        200: {"content": {"application/zip": {}}, "description": "The dataset zip (docs/features/voice_dataset.md)"},
        **_errors(404, 413),
    },
)
async def export_recordings(
    include_unverified: bool = Query(False, description="also export recordings without a checked transcript"),
    user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    """The user's recordings as a zip: original audio, metadata.jsonl and dataset.json."""
    owner = _owner(user)
    try:
        records = await recording_service.export_recordings(
            db, owner, include_unverified=include_unverified, max_audio_bytes=settings.voice_export_max_bytes
        )

        async def load_audio(recording_id: uuid.UUID) -> bytes:
            return (await recording_service.get_audio(db, recording_id, owner)).audio

        exported_at = datetime.now(timezone.utc)
        file = await build_export(records, load_audio, exported_at)
    except VoiceTakeError as exc:
        logger.info("voice.export.rejected error=%s status=%d", exc.code.value, exc.status)
        raise _http_error(exc) from exc
    size = file.seek(0, 2)
    file.seek(0)
    logger.info(
        "voice.export.built recordings=%d bytes=%d include_unverified=%s", len(records), size, include_unverified
    )
    return StreamingResponse(
        _file_chunks(file),
        media_type="application/zip",
        headers={
            "Content-Disposition": f'attachment; filename="{export_filename(exported_at)}"',
            "Content-Length": str(size),
            "Cache-Control": "no-store",
        },
    )


@router.put(
    "/api/recordings/{recording_id}/verification",
    response_model=RecordingResponse,
    responses=_errors(404, 422, 500),
)
async def put_recording_verification(
    recording_id: uuid.UUID,
    body: VerificationRequest,
    user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    """Save the user's checked transcript (normalized) and mark the recording checked."""
    try:
        text = normalize_verification(body.text)
        row = await recording_service.set_verification(db, recording_id, _owner(user), text)
    except VoiceTakeError as exc:
        raise _http_error(exc) from exc
    return _recording_response(row)


@router.delete(
    "/api/recordings/{recording_id}/verification",
    response_model=RecordingResponse,
    responses=_errors(404, 500),
)
async def delete_recording_verification(
    recording_id: uuid.UUID,
    user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    """Mark the recording unchecked (clears verified_text and verified_at); idempotent."""
    try:
        row = await recording_service.clear_verification(db, recording_id, _owner(user))
    except VoiceTakeError as exc:
        raise _http_error(exc) from exc
    return _recording_response(row)


@router.get(
    "/api/recordings/{recording_id}/audio",
    response_class=Response,
    responses={200: {"content": {"audio/*": {}}, "description": "The audio bytes, Content-Type as stored"}, **_errors(404)},
)
async def get_recording_audio(
    recording_id: uuid.UUID,
    user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    try:
        stored = await recording_service.get_audio(db, recording_id, _owner(user))
    except VoiceTakeError as exc:
        raise _http_error(exc) from exc
    return Response(content=stored.audio, media_type=stored.mime_type, headers={"Cache-Control": AUDIO_CACHE_CONTROL})


@router.delete("/api/recordings/{recording_id}", status_code=204, responses=_errors(404, 500))
async def delete_recording(
    recording_id: uuid.UUID,
    user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    try:
        await recording_service.delete_recording(db, recording_id, _owner(user))
    except VoiceTakeError as exc:
        raise _http_error(exc) from exc
    return Response(status_code=204)
